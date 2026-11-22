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
    data class ListCard(override val id: Long, val items: List<WantedItem>) : ChatEntry
    data class ResultCard(
        override val id: Long,
        val result: BasketResult,
        /** Sites that showed a login page instead of results. */
        val needLogin: List<String>,
        /** Sites that showed nothing usable for any item. */
        val noResults: List<String>,
        /** Sites whose delivery address may not be the shopper's, in words. */
        val warnings: List<String> = emptyList()
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

    private var nextId = 0L
    private var job: Job? = null

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
                update.reply?.let { r -> add { ChatEntry.FromAssistant(it, r) } }
                if (update.items != list && update.items.isNotEmpty()) add { ChatEntry.ListCard(it, update.items) }
                list = update.items
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
                val searches = items.mapIndexed { index, item ->
                    work = ShoppingWork.Searching(item.name, index + 1, items.size)
                    val pages = coroutineScope {
                        sites.map { key -> async { searchSite(driver, key, item.search) } }.awaitAll()
                    }.filterNotNull()
                    ItemSearch(item, pages)
                }
                val pagesBySite = searches.flatMap { it.pages }.groupBy { it.providerKey }
                val needLogin = sites.filter { key -> pagesBySite[key]?.all { it.needsLogin } == true }
                val noResults = sites.filter { key -> key !in needLogin && pagesBySite[key].orEmpty().all { it.offers.isEmpty() } }

                work = ShoppingWork.Matching
                val prompt = MatchPrompts.build(searches, driver::siteName)
                val matches = if (prompt.candidates.isEmpty()) {
                    Matches(emptyMap(), emptyMap())
                } else {
                    val reply = chatGpt.ask(prompt.instructions, prompt.input)
                    if (debugLog) Log.d(TAG, "match reply: $reply")
                    MatchParser.parse(reply, prompt)
                }
                val used = sites.filter { it !in needLogin && it !in noResults }
                val result = BasketResult.from(searches, matches, used)
                val warnings = used.mapNotNull(driver::warning)
                add {
                    ChatEntry.ResultCard(it, result, needLogin.map(driver::siteName), noResults.map(driver::siteName), warnings)
                }
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
    }
}
