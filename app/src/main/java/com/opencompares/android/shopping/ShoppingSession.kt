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
        /** Sites whose delivery address may not be the shopper's, in words. */
        val warnings: List<String> = emptyList(),
        /** Says which items reused earlier results instead of being searched again. */
        val reusedNote: String? = null
    ) : ChatEntry
}

/** What the assistant is busy with. */
sealed interface ShoppingWork {
    data object Idle : ShoppingWork
    data object Thinking : ShoppingWork
    data class Searching(val itemName: String, val number: Int, val total: Int) : ShoppingWork
    data object Matching : ShoppingWork
}

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

    /** Forget a site's saved results, e.g. after its login or address changed. */
    fun forgetSite(key: String) {
        pageCache.keys.removeAll { it.startsWith("$key|") }
    }

    init {
        add { ChatEntry.FromAssistant(it, GREETING) }
    }

    fun send(message: String) {
        val text = message.trim()
        if (text.isEmpty() || work != ShoppingWork.Idle) return
        add { ChatEntry.FromUser(it, text) }
        work = ShoppingWork.Thinking
        job = scope.launch {
            try {
                val reply = chatGpt.ask(ShoppingListPrompts.INSTRUCTIONS, ShoppingListPrompts.input(text, list))
                if (debugLog) Log.d(TAG, "list reply: $reply")
                val update = ShoppingListPrompts.parse(reply)
                val (items, next) = ListDiff.assignIds(list, update.items, nextItemId)
                nextItemId = next
                val diff = ListDiff.between(list, items)
                update.reply?.let { r -> add { ChatEntry.FromAssistant(it, r) } }
                if (!diff.isEmpty && items.isNotEmpty()) {
                    add { ChatEntry.ListCard(it, items, diff.takeIf { list.isNotEmpty() }) }
                }
                list = items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                work = ShoppingWork.Idle
            }
        }
    }

    fun findBestPrices(driver: PaneDriver) {
        val items = list
        if (work != ShoppingWork.Idle || items.isEmpty()) return
        val sites = driver.siteKeys
        if (sites.isEmpty()) {
            add { ChatEntry.FromAssistant(it, "No marketplaces are turned on. Pick some in the sites view first.", isError = true) }
            return
        }
        job = scope.launch {
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
                            if (page != null && !page.needsLogin) pageCache[cacheKey(key, item.search)] = CachedPage(page, System.currentTimeMillis())
                        }
                        val fresh = fetched.toMap()
                        ItemSearch(item, sites.mapNotNull { key -> fresh[key] ?: pageCache[cacheKey(key, item.search)]?.page })
                    } else {
                        ItemSearch(item, sites.mapNotNull { key -> pageCache[cacheKey(key, item.search)]?.page })
                    }
                }
                val pagesBySite = searches.flatMap { it.pages }.groupBy { it.providerKey }
                val needLogin = sites.filter { key -> pagesBySite[key]?.all { it.needsLogin } == true }
                val noResults = sites.filter { key -> key !in needLogin && pagesBySite[key].orEmpty().all { it.offers.isEmpty() } }

                val matches = matchAll(searches, driver)
                val used = sites.filter { it !in needLogin && it !in noResults }
                val result = BasketResult.from(searches, matches, used)
                val warnings = used.mapNotNull(driver::warning)
                val reused = items.size - toSearch.size
                val reusedNote = when {
                    reused == 0 -> null
                    toSearch.isEmpty() -> "Nothing changed that needed a new search, so these are the prices read earlier."
                    else -> "Searched again only for ${toSearch.joinToString { it.name }}; the other $reused reused earlier results."
                }
                add {
                    ChatEntry.ResultCard(it, result, needLogin.map(driver::siteName), noResults.map(driver::siteName), warnings, reusedNote)
                }
                hasResults = true
            } catch (e: CancellationException) {
                add { ChatEntry.FromAssistant(it, "Stopped.") }
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                work = ShoppingWork.Idle
            }
        }
    }

    fun stop() {
        job?.cancel()
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
        a.needsLogin && b.needsLogin ||
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
        /** Prices on quick-commerce sites change often; older results are searched again. */
        private const val CACHE_MS = 20 * 60 * 1000L
    }
}
