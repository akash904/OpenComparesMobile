package com.opencompares.android.prices

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** The outcome of reading one pane. */
sealed interface PaneReadResult {
    val providerKey: String

    data class Read(val page: PageOffers) : PaneReadResult {
        override val providerKey: String get() = page.providerKey
    }

    /** The pane has no live page (not loaded yet, or crashed). */
    data class NotLoaded(override val providerKey: String) : PaneReadResult

    data class Failed(override val providerKey: String, val reason: String) : PaneReadResult
}

/**
 * Reads product cards from marketplace panes by running the bundled extractor script
 * inside each page. Everything stays on the device; no network calls are made.
 */
class PriceReader(context: Context) {

    private val appContext = context.applicationContext
    private val script: String by lazy {
        appContext.assets.open(SCRIPT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private val logResults = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Reads all the given panes in parallel. [views] maps provider keys to their WebViews. */
    suspend fun readAll(views: Map<String, WebView?>): List<PaneReadResult> = coroutineScope {
        val js = withContext(Dispatchers.IO) { script }
        views.map { (key, view) -> async { readOne(key, view, js) } }.awaitAll()
    }

    /** Reads one pane. */
    suspend fun read(key: String, view: WebView?): PaneReadResult =
        readOne(key, view, withContext(Dispatchers.IO) { script })

    private suspend fun readOne(key: String, view: WebView?, js: String): PaneReadResult {
        if (view == null) return PaneReadResult.NotLoaded(key)
        val raw = withTimeoutOrNull(READ_TIMEOUT_MS) { evaluate(view, js) }
            ?: return PaneReadResult.Failed(key, "The page took too long to respond.")
        return try {
            // evaluateJavascript returns the script's string result JSON-encoded.
            val decoded = JSONTokener(raw).nextValue() as? String
                ?: return PaneReadResult.Failed(key, "The page didn't return any data.")
            val page = OfferParser.parse(key, JSONObject(decoded))
            if (logResults) logSummary(page)
            PaneReadResult.Read(page)
        } catch (e: Exception) {
            PaneReadResult.Failed(key, "Couldn't read this page.")
        }
    }

    private suspend fun evaluate(view: WebView, js: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(js) { result -> if (cont.isActive) cont.resume(result ?: "null") }
            }
        }

    /** Debug builds only: a short summary for checking extraction quality with adb logcat. */
    private fun logSummary(page: PageOffers) {
        Log.d(TAG, "${page.providerKey}: ${page.offers.size} offers from ${page.scannedPrices} prices")
        page.offers.take(5).forEach { o ->
            Log.d(TAG, "  ${o.name.take(60)} | ${formatRupees(o.price)} | mrp=${o.mrp} | qty=${o.quantity} | unit=${o.unitPrice} | stock=${o.inStock} add=${o.canAdd}")
        }
    }

    private companion object {
        const val SCRIPT_ASSET = "price_extractor.js"
        const val READ_TIMEOUT_MS = 8_000L
        const val TAG = "OCPrices"
    }
}
