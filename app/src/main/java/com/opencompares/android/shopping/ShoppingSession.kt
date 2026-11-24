package com.opencompares.android.shopping

import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.opencompares.android.chatgpt.ChatGptAccount
import com.opencompares.android.chatgpt.SiwcApiException
import com.opencompares.android.prices.Offer
import com.opencompares.android.prices.formatRupees
import com.opencompares.android.web.PageScripts
import com.opencompares.android.web.runPageScript
import org.json.JSONObject
import com.opencompares.android.prices.PageOffers
import com.opencompares.android.prices.PaneReadResult
import com.opencompares.android.prices.PriceReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One message or card in the shopping chat. */
sealed interface ChatEntry {
    val id: Long

    data class FromUser(override val id: Long, val text: String) : ChatEntry
    data class FromAssistant(override val id: Long, val text: String, val isError: Boolean = false) : ChatEntry
    /** The list after a message, with what that message changed (null for no earlier list). */
    data class ListCard(override val id: Long, val items: List<WantedItem>, val diff: ListDiff? = null) : ChatEntry
    data class ResultCard(
        override val id: Long,
        val result: BasketResult,
        /** Sites that showed a login page instead of results. */
        val needLogin: List<String>,
        /** Sites that showed nothing usable for any item. */
        val noResults: List<String>,
        /** Sites that asked for a delivery address instead of showing results. */
        val needLocation: List<String> = emptyList(),
        /** Sites whose delivery address may not be the shopper's, in words. */
        val warnings: List<String> = emptyList(),
        /** Says which items reused earlier results instead of being searched again. */
        val reusedNote: String? = null
    ) : ChatEntry

    /** Carts the app filled, with each site's real bill. */
    data class CartCard(override val id: Long, val comparison: CartComparison) : ChatEntry
}

/** What the assistant is busy with. */
sealed interface ShoppingWork {
    data object Idle : ShoppingWork
    data object Thinking : ShoppingWork
    data class Searching(val itemName: String, val number: Int, val total: Int) : ShoppingWork
    data object Matching : ShoppingWork
    /** Putting items in a site's cart, or reading its bill. */
    data class Carting(
        val siteName: String,
        val step: String,
        val number: Int,
        val total: Int,
        /** What every site is doing, for the line above the per-site steps. */
        val title: String = "Filling carts"
    ) : ShoppingWork
}

/**
 * Auto mode: after a message changes the list, search, fill every site's cart and read the
 * bills in one go. [emptyFirst] empties those carts before filling them.
 */
class AutoRun(val driver: PaneDriver, val emptyFirst: Boolean)

/** Lets a shopping run search in the marketplace panes. */
interface PaneDriver {
    /** Sites to search, in pane order. */
    val siteKeys: List<String>
    fun siteName(key: String): String
    fun searchUrl(key: String, query: String): String?
    fun navigate(key: String, url: String)
    /** Finished page loads so far; see PaneState.loads. */
    fun loads(key: String): Int
    fun view(key: String): WebView?
    /** Why this site's prices may not suit the shopper, such as another delivery address. */
    fun warning(key: String): String?
    /** The site's cart page, if known. */
    fun cartUrl(key: String): String?
    /** Items already in the site's cart, read fresh from its page; null if unknown. */
    suspend fun cartCount(key: String): Int?
}

/**
 * The shopping chat: turns the shopper's messages into a list with ChatGPT, searches every
 * site for each item in its pane, and has ChatGPT say which listing is the item on each site.
 * Pack counts and totals are worked out here from the prices read off the pages.
 */
class ShoppingSession(
    private val scope: CoroutineScope,
    private val chatGpt: ChatGptAccount,
    private val reader: PriceReader,
    private val scripts: PageScripts,
    private val debugLog: Boolean
) {
    val entries = mutableStateListOf<ChatEntry>()

    var work by mutableStateOf<ShoppingWork>(ShoppingWork.Idle)
        private set

    /** The current shopping list, as last agreed in the chat. */
    var list by mutableStateOf<List<WantedItem>>(emptyList())
        private set

    /** True once any item has prices, so the next run only redoes what changed. */
    var hasResults by mutableStateOf(false)
        private set

    private var nextId = 0L
    private var nextItemId = 1
    private var job: Job? = null

    /** A site's results for one search phrase, so unchanged items aren't searched again. */
    private class CachedPage(val page: PageOffers, val atMillis: Long)

    /** ChatGPT's picks for one item, for the exact pages it was matched on. */
    private class ItemMatch(val picks: Map<String, Offer>, val similar: Map<String, Offer>, val note: String?)

    private val pageCache = HashMap<String, CachedPage>()
    private val matchCache = HashMap<String, ItemMatch>()

    /** What the app itself has put in site carts during this chat, so auto mode can undo it. */
    private var owned = listOf<OwnedUnits>()

    /** Forget a site's saved results, e.g. after its login or address changed. */
    fun forgetSite(key: String) {
        pageCache.keys.removeAll { it.startsWith("$key|") }
    }

    init {
        add { ChatEntry.FromAssistant(it, GREETING) }
    }

    /** Has ChatGPT update the list from [message]; with [auto], goes on to fill the carts. */
    fun send(message: String, auto: AutoRun? = null) {
        val text = message.trim()
        if (text.isEmpty() || work != ShoppingWork.Idle) return
        add { ChatEntry.FromUser(it, text) }
        work = ShoppingWork.Thinking
        job = scope.launch {
            try {
                // A question or a reply that leaves the list as it was needs no new carts.
                if (updateList(text) && list.isNotEmpty() && auto != null) runAuto(auto)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                work = ShoppingWork.Idle
            }
        }
    }

    /** Returns true if the list changed. */
    private suspend fun updateList(text: String): Boolean {
        val reply = chatGpt.ask(ShoppingListPrompts.INSTRUCTIONS, ShoppingListPrompts.input(text, list))
        if (debugLog) Log.d(TAG, "list reply: $reply")
        val update = ShoppingListPrompts.parse(reply)
        val (items, next) = ListDiff.assignIds(list, update.items, nextItemId)
        nextItemId = next
        val diff = ListDiff.between(list, items)
        update.reply?.let { r -> add { ChatEntry.FromAssistant(it, r) } }
        val changed = !diff.isEmpty && items.isNotEmpty()
        if (changed) add { ChatEntry.ListCard(it, items, diff.takeIf { list.isNotEmpty() }) }
        list = items
        return changed
    }

    /**
     * Auto mode after the list changed: searches every site, then fills each site that has
     * any of the items and reads its bill, without asking in between. Nothing is ordered.
     */
    private suspend fun runAuto(auto: AutoRun) {
        val driver = auto.driver
        val result = searchAll(driver) ?: return
        val sites = result.sites.filter { it.found.isNotEmpty() }.map { it.providerKey }
        if (sites.isEmpty()) {
            add { ChatEntry.FromAssistant(it, "No site had any of your items, so no carts were filled.", isError = true) }
            return
        }
        if (auto.emptyFirst) {
            clearCarts(driver, sites, "Emptied the carts first:")
        } else {
            // Take back what the app put in earlier for items that are off the list or changed.
            val removals = CartSync.removals(owned, result)
            if (removals.isNotEmpty()) takeOutAll(driver, removals)
        }
        fill(driver, result, sites)
    }

    fun findBestPrices(driver: PaneDriver) {
        if (work != ShoppingWork.Idle || list.isEmpty()) return
        job = scope.launch {
            try {
                searchAll(driver)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                work = ShoppingWork.Idle
            }
        }
    }

    /** Searches every site for the list and adds the prices card; null if it couldn't search. */
    private suspend fun searchAll(driver: PaneDriver): BasketResult? {
        val items = list
        val sites = driver.siteKeys
        if (sites.isEmpty()) {
            add { ChatEntry.FromAssistant(it, "No marketplaces are turned on. Pick some in the sites view first.", isError = true) }
            return null
        }
        try {
            val now = System.currentTimeMillis()
            pageCache.values.removeAll { now - it.atMillis > CACHE_MS }
            // Only items with a site lacking fresh results are searched; the rest reuse them.
            val toSearch = items.filter { item -> sites.any { pageCache[cacheKey(it, item.search)] == null } }
            val searches = items.map { item ->
                val missing = sites.filter { pageCache[cacheKey(it, item.search)] == null }
                if (missing.isNotEmpty()) {
                    work = ShoppingWork.Searching(item.name, toSearch.indexOf(item) + 1, toSearch.size)
                    val fetched = coroutineScope {
                        missing.map { key -> async { key to searchSite(driver, key, item.search) } }.awaitAll()
                    }
                    // A login wall is not a result worth keeping; try that site again next time.
                    fetched.forEach { (key, page) ->
                        if (page != null && !page.needsLogin && !page.needsLocation) pageCache[cacheKey(key, item.search)] = CachedPage(page, System.currentTimeMillis())
                    }
                    val fresh = fetched.toMap()
                    ItemSearch(item, sites.mapNotNull { key -> fresh[key] ?: pageCache[cacheKey(key, item.search)]?.page })
                } else {
                    ItemSearch(item, sites.mapNotNull { key -> pageCache[cacheKey(key, item.search)]?.page })
                }
            }
            val pagesBySite = searches.flatMap { it.pages }.groupBy { it.providerKey }
            val needLogin = sites.filter { key -> pagesBySite[key]?.all { it.needsLogin } == true }
            val needLocation = sites.filter { key -> pagesBySite[key]?.all { it.needsLocation } == true }
            val noResults = sites.filter { key ->
                key !in needLogin && key !in needLocation && pagesBySite[key].orEmpty().all { it.offers.isEmpty() }
            }

            val matches = matchAll(searches, driver)
            val used = sites.filter { it !in needLogin && it !in needLocation && it !in noResults }
            val result = BasketResult.from(searches, matches, used)
            val warnings = used.mapNotNull(driver::warning)
            val reused = items.size - toSearch.size
            val reusedNote = when {
                reused == 0 -> null
                toSearch.isEmpty() -> "Nothing changed that needed a new search, so these are the prices read earlier."
                else -> "Searched again only for ${toSearch.joinToString { it.name }}; the other $reused reused earlier results."
            }
            add {
                ChatEntry.ResultCard(
                    it, result, needLogin.map(driver::siteName), noResults.map(driver::siteName),
                    needLocation.map(driver::siteName), warnings, reusedNote
                )
            }
            hasResults = true
            return result
        } catch (e: CancellationException) {
            add { ChatEntry.FromAssistant(it, "Stopped.") }
            throw e
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** True once the chat has more than the greeting, so there is something to reset. */
    val canReset: Boolean get() = entries.size > 1 || work != ShoppingWork.Idle

    /** True if the app has put items in any site's cart during this chat. */
    val filledCarts: Boolean get() = entries.any { it is ChatEntry.CartCard }

    /**
     * Starts a new chat: stops whatever is running and clears the list and results.
     * Recent search results are kept, so searching the same items again is quick.
     * Items already put in site carts stay there.
     */
    fun reset() {
        job?.cancel()
        job = null
        entries.clear()
        list = emptyList()
        hasResults = false
        nextItemId = 1
        owned = emptyList()
        work = ShoppingWork.Idle
        add { ChatEntry.FromAssistant(it, GREETING) }
    }

    /**
     * Puts each chosen site's items in its cart, then reads the site's real bill. Only the
     * sites the shopper confirmed are touched, one at a time; nothing is ever ordered.
     */
    fun fillCarts(driver: PaneDriver, result: BasketResult, siteKeys: List<String>) {
        if (work != ShoppingWork.Idle || siteKeys.isEmpty()) return
        job = scope.launch {
            try {
                fill(driver, result, siteKeys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                work = ShoppingWork.Idle
            }
        }
    }

    /** Fills the sites' carts at the same time, then adds the card with their bills. */
    private suspend fun fill(driver: PaneDriver, result: BasketResult, siteKeys: List<String>) = coroutineScope {
        // Each site works in its own pane, so all of them fill at the same time.
        val show = progressShower(driver, "Filling carts")
        val carts = java.util.Collections.synchronizedList(mutableListOf<SiteCart>())
        try {
            val jobs = siteKeys.mapNotNull { site ->
                val options = result.items.mapNotNull { plan ->
                    plan.options.firstOrNull { it.providerKey == site }?.let { plan.item to it }
                }
                if (options.isEmpty()) return@mapNotNull null
                show(site, "waiting")
                async { fillOne(driver, site, options) { step -> show(site, step) }.also { carts += it } }
            }
            jobs.awaitAll()
            owned = CartSync.afterFill(owned, carts)
            // Same order as the sites were listed, whichever finished first.
            val ordered = siteKeys.mapNotNull { k -> carts.firstOrNull { it.providerKey == k } }
            add { ChatEntry.CartCard(it, CartComparison(ordered, result)) }
        } catch (e: CancellationException) {
            val done = carts.toList()
            owned = CartSync.afterFill(owned, done)
            if (done.isNotEmpty()) add { ChatEntry.CartCard(it, CartComparison(done, result)) }
            add { ChatEntry.FromAssistant(it, "Stopped. Items already added stay in those carts.") }
            throw e
        }
    }

    /** Shows each site's step, one line per site, while sites work in their panes at once. */
    private fun progressShower(driver: PaneDriver, title: String): (String, String) -> Unit {
        val progress = LinkedHashMap<String, String>()
        return { site, step ->
            progress[driver.siteName(site)] = step
            work = ShoppingWork.Carting(
                siteName = "",
                step = progress.entries.joinToString("\n") { (n, st) -> "$n: $st" },
                number = progress.values.count { it == DONE_STEP },
                total = progress.size,
                title = title
            )
        }
    }

    /**
     * Empties each chosen site's cart completely, the shopper's own items too, by pressing −
     * on its cart page until nothing is left. All sites work at the same time.
     */
    fun emptyCarts(driver: PaneDriver, siteKeys: List<String>) {
        if (work != ShoppingWork.Idle || siteKeys.isEmpty()) return
        job = scope.launch {
            try {
                clearCarts(driver, siteKeys, "Carts:")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                work = ShoppingWork.Idle
            }
        }
    }

    /** Empties the sites' carts at the same time, then says how each went under [title]. */
    private suspend fun clearCarts(driver: PaneDriver, siteKeys: List<String>, title: String) = coroutineScope {
        val show = progressShower(driver, "Emptying carts")
        val done = java.util.Collections.synchronizedList(mutableListOf<EmptiedCart>())
        fun report(heading: String) {
            // The app's own units are gone with everything else.
            owned = owned.filter { o -> done.none { it.providerKey == o.providerKey } }
            val lines = siteKeys.mapNotNull { k -> done.firstOrNull { it.providerKey == k } }
                .joinToString("\n") { it.describe(driver.siteName(it.providerKey)) }
            add { ChatEntry.FromAssistant(it, if (lines.isEmpty()) heading else "$heading\n$lines") }
        }
        try {
            siteKeys.map { site ->
                show(site, "waiting")
                async { clearCart(driver, site) { step -> show(site, step) }.also { done += it } }
            }.awaitAll()
            report(title)
        } catch (e: CancellationException) {
            report("Stopped emptying carts.")
            throw e
        }
    }

    /** Takes the given units out of each site's cart, all sites at once; see [CartSync.removals]. */
    private suspend fun takeOutAll(driver: PaneDriver, removals: Map<String, List<Pair<Offer, Int>>>) = coroutineScope {
        val show = progressShower(driver, "Taking out what's no longer on the list")
        removals.map { (site, list) ->
            show(site, "waiting")
            async {
                val removed = takeOut(driver, site, list) { step -> show(site, step) }
                show(site, DONE_STEP)
                if (removed == null) {
                    add { ChatEntry.FromAssistant(it, "Couldn't open ${driver.siteName(site)}'s cart to take out what's no longer on the list.", isError = true) }
                }
            }
        }.awaitAll()
    }

    /**
     * Presses − on the cart page for each listing, as many times as given, and keeps the
     * ledger of the app's own units up to date. Returns how many came out, or null without a cart page.
     */
    private suspend fun takeOut(driver: PaneDriver, site: String, list: List<Pair<Offer, Int>>, show: (String) -> Unit): Int? {
        show("opening the cart")
        if (!openCart(driver, site)) return null
        var removed = 0
        for ((offer, units) in list) {
            show("removing ${offer.name.take(30)}")
            // Cart pages draw their items a moment after loading: wait for this one.
            for (attempt in 0 until MAX_READS) {
                if (cardAction(driver, site, offer, "find", matchPrice = false)?.found == true) break
                delay(READ_INTERVAL_MS)
            }
            var out = 0
            repeat(units) {
                val state = cardAction(driver, site, offer, "minus", matchPrice = false)
                if (state?.found == true && state.count > 0) out++
                delay(STEP_DELAY_MS)
            }
            owned = CartSync.afterRemoval(owned, site, offer.name, out)
            removed += out
        }
        forgetSite(site)
        return removed
    }

    /** Takes every item out of one site's cart, reporting each step through [show]. */
    private suspend fun clearCart(driver: PaneDriver, site: String, show: (String) -> Unit): EmptiedCart {
        show("opening the cart")
        if (!openCart(driver, site)) return EmptiedCart(site, 0, 0, problem = "the app doesn't know its cart page.")
        // Cart pages draw their lines a moment after loading; an empty cart says so instead.
        var lines: CartLines? = null
        var emptyReads = 0
        for (attempt in 0 until MAX_READS) {
            delay(READ_INTERVAL_MS)
            val previous = lines
            lines = cartLines(driver, site, "lines")
            // Lines can draw one by one: wait until two reads in a row agree.
            if ((lines?.lines ?: 0) > 0 && lines == previous) break
            if ((lines?.lines ?: 0) == 0 && pageSaysEmpty(driver, site) && ++emptyReads >= 2) break
        }
        val start = lines?.units ?: 0
        if (start == 0) {
            show(DONE_STEP)
            return if (emptyReads >= 2) EmptiedCart(site, 0, 0, wasEmpty = true)
            else EmptiedCart(site, 0, 0, problem = "couldn't find any items on its cart page. If it has some, remove them in its pane.")
        }
        var left = start
        // A page can redraw a line just after the last − was pressed: look once more at the end.
        for (round in 0 until 2) {
            var stuck = 0
            var presses = 0
            while (left > 0 && stuck < MAX_STUCK_PRESSES && presses++ < MAX_EMPTY_PRESSES) {
                show("removing (${(start - left).coerceAtLeast(0)} of $start out)")
                if (cartLines(driver, site, "removeOne")?.pressed != true) break
                delay(STEP_DELAY_MS)
                val now = cartLines(driver, site, "lines")?.units ?: break
                if (now < left) stuck = 0 else stuck++
                left = now
            }
            if (left > 0) break
            delay(READ_INTERVAL_MS)
            left = cartLines(driver, site, "lines")?.units ?: 0
            if (left == 0) break
        }
        // Refresh what the app knows about this site's cart.
        driver.cartCount(site)
        show(DONE_STEP)
        return EmptiedCart(site, removed = (start - left).coerceAtLeast(0), left = left)
    }

    private suspend fun cartLines(driver: PaneDriver, site: String, mode: String): CartLines? {
        val view = driver.view(site) ?: return null
        val args = JSONObject().put("mode", mode).put("name", "").put("price", "")
        val raw = runPageScript(view, scripts.load(CART_SCRIPT).replace("__OC_ARGS__", args.toString())) ?: return null
        return runCatching { CartLines.parse(JSONObject(raw)) }.getOrNull()
    }

    /** True if the open cart page says the cart is empty. */
    private suspend fun pageSaysEmpty(driver: PaneDriver, site: String): Boolean {
        val view = driver.view(site) ?: return false
        val raw = runPageScript(view, scripts.load(BILL_SCRIPT)) ?: return false
        return runCatching { JSONObject(raw).optBoolean("empty") }.getOrDefault(false)
    }

    /** Fills one site's cart and reads its bill, reporting each step through [show]. */
    private suspend fun fillOne(
        driver: PaneDriver,
        site: String,
        options: List<Pair<WantedItem, ItemOption>>,
        show: (String) -> Unit
    ): SiteCart {
        val name = driver.siteName(site)
        show("checking the cart")
        val itemsBefore = driver.cartCount(site)
        val added = mutableListOf<AddedItem>()
        for ((i, pair) in options.withIndex()) {
            val (item, option) = pair
            show("adding ${item.name} (${i + 1} of ${options.size})")
            val one = addToCart(driver, site, item, option)
            added += one
            // A site that wants a login or an address won't take the rest either.
            if (one.problem == LOGIN_PROBLEM || one.problem == LOCATION_PROBLEM) {
                options.drop(i + 1).forEach { (it2, o2) -> added += AddedItem(it2, o2.offer, o2.packs, 0, 0, one.problem) }
                break
            }
        }
        val anyAdded = added.any { it.inCart > 0 }
        val read = if (anyAdded || (itemsBefore ?: 0) > 0) {
            show("reading the bill")
            readBill(driver, site)
        } else null
        val bill = read?.bill
        val emptied = read?.empty == true
        // A new cart can change which address or fees apply; search afresh next time.
        forgetSite(site)
        show(DONE_STEP)
        return SiteCart(
            providerKey = site,
            items = added,
            bill = bill,
            itemsBefore = itemsBefore,
            problem = when {
                added.all { it.problem == LOGIN_PROBLEM } -> "$name wants you to log in before it lets you add to its cart."
                added.all { it.problem == LOCATION_PROBLEM } -> "$name wants a delivery address first. Set it with Set up in Your sites."
                !anyAdded -> "Nothing could be added to $name's cart."
                emptied -> "$name's cart came up empty: as a guest it can drop items when it asks you to log in. Log in on $name to use its cart."
                bill == null -> "Couldn't read the bill on $name's cart page."
                else -> null
            }
        )
    }

    /** Takes out only what the app put in a site's cart; anything the shopper had stays. */
    fun emptyAdded(driver: PaneDriver, cart: SiteCart) {
        if (work != ShoppingWork.Idle) return
        val name = driver.siteName(cart.providerKey)
        job = scope.launch {
            try {
                val list = cart.items.filter { it.addedByApp > 0 }.map { it.offer to it.addedByApp }
                val removed = takeOut(driver, cart.providerKey, list) { step -> work = ShoppingWork.Carting(name, step, 0, 0) }
                if (removed == null) {
                    add { ChatEntry.FromAssistant(it, "Couldn't open $name's cart. Remove the items in its pane.", isError = true) }
                    return@launch
                }
                add { ChatEntry.FromAssistant(it, "Took $removed item${if (removed == 1) "" else "s"} the app added out of $name's cart.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                work = ShoppingWork.Idle
            }
        }
    }

    /** Finds the product on the site's search page and sets its quantity to the packs needed. */
    private suspend fun addToCart(driver: PaneDriver, site: String, item: WantedItem, option: ItemOption): AddedItem {
        val page = searchSite(driver, site, item.search)
        if (page?.needsLogin == true) return AddedItem(item, option.offer, option.packs, 0, 0, LOGIN_PROBLEM)
        if (page?.needsLocation == true) return AddedItem(item, option.offer, option.packs, 0, 0, LOCATION_PROBLEM)
        // Results can still be rendering; look once more before giving up.
        val first = cardAction(driver, site, option.offer, "find")?.takeIf { it.found }
            ?: run {
                delay(STEP_DELAY_MS)
                cardAction(driver, site, option.offer, "find")
            }
        if (first == null || !first.found) {
            if (debugLog) Log.d(TAG, "$site cart: ${option.offer.name} not found on the page")
            return AddedItem(item, option.offer, option.packs, 0, 0, "Couldn't find it on the page any more.")
        }
        var state: CardState = first
        // Some sites draw their cards before the buttons work (Flipkart): wait until they do.
        var waits = 0
        while (!state.live && waits++ < MAX_READS) {
            delay(READ_INTERVAL_MS)
            state = cardAction(driver, site, option.offer, "find") ?: state
        }
        // A page that never looks ready gets its presses anyway.
        val force = !state.live
        val before = state.count
        if (state.count == 0) {
            if (!state.canAdd) {
                val why = if (state.outOfStock) "Out of stock now." else "No ADD button on its card."
                return AddedItem(item, option.offer, option.packs, 0, 0, why)
            }
            // A page can redraw its list just as ADD is pressed (Flipkart), losing the press: after
            // a long enough look at a count still at 0 with ADD still there, press once more.
            for (attempt in 0 until ADD_ATTEMPTS) {
                pressCard(driver, site, option.offer, "add", force)
                delay(STEP_DELAY_MS)
                choosePack(driver, site, option.offer)
                state = cardAction(driver, site, option.offer, "find") ?: state
                // Some sites (Flipkart Minutes) show the new count a few seconds late, especially while
                // other panes are busy too: look again before calling it a failure.
                var checks = 0
                while (state.count == 0 && checks++ < LATE_COUNT_CHECKS) {
                    delay(STEP_DELAY_MS)
                    state = cardAction(driver, site, option.offer, "find") ?: state
                }
                if (state.count > 0 || !state.canAdd) break
            }
            if (state.count == 0) {
                if (debugLog) Log.d(TAG, "$site cart: ${option.offer.name} ADD pressed but count stayed 0")
                return AddedItem(item, option.offer, option.packs, 0, 0, "The site didn't add it. It may want you to log in.")
            }
        }
        // Raise the quantity one step at a time; stop if the site stops accepting more.
        var stuck = 0
        while (state.count < option.packs && state.canPlus && stuck < 2) {
            val was = state.count
            pressCard(driver, site, option.offer, "plus", force)
            delay(STEP_DELAY_MS)
            choosePack(driver, site, option.offer)
            state = cardAction(driver, site, option.offer, "find") ?: state
            if (state.count <= was) stuck++ else stuck = 0
        }
        val problem = if (state.count < option.packs) "Only ${state.count} of ${option.packs} could be added; the site may limit how many." else null
        if (debugLog) Log.d(TAG, "$site cart: ${option.offer.name} ${state.count}/${option.packs}")
        return AddedItem(item, option.offer, option.packs, before, state.count, problem)
    }

    /**
     * Presses ADD or + on a card. While the card isn't live yet the script presses nothing, so
     * it is safe to try again; after a while it presses anyway.
     */
    private suspend fun pressCard(driver: PaneDriver, site: String, offer: Offer, mode: String, force: Boolean): CardState? {
        var result = cardAction(driver, site, offer, mode, force = force)
        var tries = 0
        while (result != null && result.found && !result.live && tries++ < MAX_READS) {
            delay(READ_INTERVAL_MS)
            result = cardAction(driver, site, offer, mode, force = tries == MAX_READS)
        }
        return result
    }

    /**
     * Some sites (Instamart) open a pack-size picker after ADD or +. If one is open, add the
     * pack with this price in it and confirm, so the picker closes with exactly one more.
     */
    private suspend fun choosePack(driver: PaneDriver, site: String, offer: Offer) {
        val pick = cardAction(driver, site, offer, "variant") ?: return
        if (!pick.picker) return
        if (pick.found) delay(STEP_DELAY_MS)
        cardAction(driver, site, offer, "confirm")
        delay(STEP_DELAY_MS)
        if (debugLog) Log.d(TAG, "$site cart: picked the ${formatRupees(offer.price)} pack of ${offer.name} (${pick.found})")
    }

    private suspend fun cardAction(
        driver: PaneDriver,
        site: String,
        offer: Offer,
        mode: String,
        matchPrice: Boolean = true,
        force: Boolean = false
    ): CardState? {
        val view = driver.view(site) ?: return null
        val args = JSONObject()
            .put("mode", mode)
            .put("name", offer.name)
            .put("price", if (matchPrice) formatRupees(offer.price) else "")
            .put("force", force)
        val js = scripts.load(CART_SCRIPT).replace("__OC_ARGS__", args.toString())
        val raw = runPageScript(view, js) ?: return null
        return runCatching { CardState.parse(JSONObject(raw)) }.getOrNull()
    }

    /** Opens the site's cart page and waits for it to load. */
    private suspend fun openCart(driver: PaneDriver, site: String): Boolean {
        val url = driver.cartUrl(site) ?: return false
        val loadsBefore = driver.loads(site)
        driver.navigate(site, url)
        withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { snapshotFlow { driver.loads(site) }.first { it > loadsBefore } }
        return true
    }

    /** A cart page's bill, or that the page said the cart is empty. */
    private class BillRead(val bill: Bill?, val empty: Boolean)

    /** Opens the cart and reads its bill, giving the page a few seconds to fill it in. */
    private suspend fun readBill(driver: PaneDriver, site: String): BillRead? {
        if (!openCart(driver, site)) return null
        var emptyReads = 0
        repeat(MAX_READS) {
            delay(READ_INTERVAL_MS)
            val view = driver.view(site) ?: return null
            val raw = runPageScript(view, scripts.load(BILL_SCRIPT)) ?: return@repeat
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@repeat
            // Some cart pages show "empty" for a moment while loading; twice in a row is real.
            if (json.optBoolean("empty")) {
                if (++emptyReads >= 2) return BillRead(null, empty = true)
                return@repeat
            }
            emptyReads = 0
            val bill = runCatching { BillParser.parse(json) }.getOrNull()
            if (bill != null) {
                if (debugLog) Log.d(TAG, "$site bill: $raw")
                return BillRead(bill, empty = false)
            }
        }
        return null
    }

    /**
     * Has ChatGPT match only the items it hasn't matched on these exact pages before; a
     * changed amount or a fresh search counts as new. Returns matches for every item.
     */
    private suspend fun matchAll(searches: List<ItemSearch>, driver: PaneDriver): Matches {
        val keys = searches.map { matchKey(it) }
        val needed = searches.indices.filter { keys[it] !in matchCache }
        if (needed.isNotEmpty()) {
            work = ShoppingWork.Matching
            val sub = needed.map { searches[it] }
            val prompt = MatchPrompts.build(sub, driver::siteName)
            val m = if (prompt.candidates.isEmpty()) {
                Matches(emptyMap(), emptyMap())
            } else {
                val reply = chatGpt.ask(prompt.instructions, prompt.input)
                if (debugLog) Log.d(TAG, "match reply: $reply")
                MatchParser.parse(reply, prompt)
            }
            needed.forEachIndexed { j, i ->
                matchCache[keys[i]] = ItemMatch(
                    picks = m.picks[j].orEmpty().mapValues { it.value.offer },
                    similar = m.similar[j].orEmpty().mapValues { it.value.offer },
                    note = m.notes[j]
                )
            }
        }
        val cached = keys.map { matchCache.getValue(it) }
        return Matches(
            picks = cached.withIndex().associate { (i, c) -> i to c.picks.mapValues { (site, offer) -> Candidate("", i, site, offer) } },
            notes = cached.withIndex().mapNotNull { (i, c) -> c.note?.let { i to it } }.toMap(),
            similar = cached.withIndex().associate { (i, c) -> i to c.similar.mapValues { (site, offer) -> Candidate("", i, site, offer) } }
        )
    }

    private fun cacheKey(site: String, search: String) = "$site|${search.trim().lowercase()}"

    /** Same item wording and the very same pages: ChatGPT's earlier answer still holds. */
    private fun matchKey(s: ItemSearch): String =
        listOf(s.item.name, s.item.search, s.item.want.orEmpty()).joinToString("|") { it.trim().lowercase() } +
            "#" + s.pages.joinToString(",") { "${it.providerKey}@${it.readAtMillis}" }

    /** Searches one site, trying once more if it shows a login page instead of results. */
    private suspend fun searchSite(driver: PaneDriver, key: String, query: String): PageOffers? {
        val first = searchOnce(driver, key, query)
        if (first?.needsLogin != true) return first
        // Instamart, for one, shows its login wall for some searches and not for the next.
        delay(LOGIN_RETRY_DELAY_MS)
        return searchOnce(driver, key, query) ?: first
    }

    /**
     * Searches one site in its pane and waits for the results to settle: the page must
     * finish loading, then two reads in a row must agree, since sites fill in results late.
     */
    private suspend fun searchOnce(driver: PaneDriver, key: String, query: String): PageOffers? {
        val url = driver.searchUrl(key, query) ?: return null
        val loadsBefore = driver.loads(key)
        driver.navigate(key, url)
        withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { snapshotFlow { driver.loads(key) }.first { it > loadsBefore } }
        var last: PageOffers? = null
        repeat(MAX_READS) {
            delay(READ_INTERVAL_MS)
            val page = (reader.read(key, driver.view(key)) as? PaneReadResult.Read)?.page
            val previous = last
            if (page != null && previous != null && settled(previous, page)) {
                if (debugLog) Log.d(TAG, "$key '$query': ${page.offers.size} offers, login=${page.needsLogin}")
                return page
            }
            last = page
        }
        if (debugLog) Log.d(TAG, "$key '$query': unsettled, ${last?.offers?.size} offers")
        return last
    }

    private fun settled(a: PageOffers, b: PageOffers): Boolean =
        a.needsLogin && b.needsLogin || a.needsLocation && b.needsLocation ||
            a.offers.isNotEmpty() && a.offers.size == b.offers.size &&
            a.offers.take(3).map { it.name } == b.offers.take(3).map { it.name }

    private fun fail(e: Exception) {
        val message = when (e) {
            is SiwcApiException -> e.userMessage
            is ShoppingFormatException -> "ChatGPT's answer couldn't be read. Try again."
            else -> e.message ?: "Something went wrong. Try again."
        }
        add { ChatEntry.FromAssistant(it, message, isError = true) }
    }

    private fun add(make: (Long) -> ChatEntry) {
        entries += make(nextId++)
    }

    companion object {
        const val GREETING = "What do you want to buy? Tell me your list, like \"2 litres milk, a dozen eggs, 1 kg atta and Maggi\"."
        private const val TAG = "OCShop"
        private const val PAGE_LOAD_TIMEOUT_MS = 15_000L
        private const val READ_INTERVAL_MS = 1_500L
        private const val MAX_READS = 8
        private const val LOGIN_RETRY_DELAY_MS = 2_500L
        /** Time for a site to update after a tap on ADD, + or -. */
        private const val STEP_DELAY_MS = 1_500L
        private const val DONE_STEP = "done"
        private const val LATE_COUNT_CHECKS = 3
        /** Presses of ADD on one card, when an earlier press left no sign at all. */
        private const val ADD_ATTEMPTS = 2
        /** Presses of − in a row that take nothing out before a site is given up on. */
        private const val MAX_STUCK_PRESSES = 3
        /** At most this many presses per site, so a page that never empties can't hold things up. */
        private const val MAX_EMPTY_PRESSES = 60
        private const val CART_SCRIPT = "cart_actions.js"
        private const val LOGIN_PROBLEM = "The site asked you to log in."
        private const val LOCATION_PROBLEM = "The site asked for a delivery address."
        private const val BILL_SCRIPT = "bill_reader.js"
        /** Prices on quick-commerce sites change often; older results are searched again. */
        private const val CACHE_MS = 20 * 60 * 1000L
    }
}
