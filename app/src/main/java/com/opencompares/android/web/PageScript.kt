package com.opencompares.android.web

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Loads the bundled page scripts once. */
class PageScripts(context: Context) {
    private val appContext = context.applicationContext
    private val cache = HashMap<String, String>()

    @Synchronized
    fun load(name: String): String = cache.getOrPut(name) {
        appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

/**
 * Runs [js] in [view]'s page and returns the string the script returned, or null if the page
 * didn't answer in time or returned something else. Scripts here return JSON strings.
 */
suspend fun runPageScript(view: WebView, js: String, timeoutMs: Long = 8_000L): String? {
    val raw = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { cont ->
                view.evaluateJavascript(js) { result -> if (cont.isActive) cont.resume(result ?: "null") }
            }
        }
    } ?: return null
    // evaluateJavascript hands back the result JSON-encoded.
    return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}
