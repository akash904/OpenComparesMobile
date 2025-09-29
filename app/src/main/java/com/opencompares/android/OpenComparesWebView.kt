package com.opencompares.android

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A minimal WebView wrapper that:
 * - Loads exactly the targetUrl you pass in (no implicit URL building)
 * - Applies UA based on desktopMode (+ per-provider mobile UA when not desktop)
 * - Keeps WebView scrollable inside Compose grids
 * - Can force reload when reloadToken changes
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OpenComparesWebView(
    provider: Provider,
    targetUrl: String,                 // <- explicit URL to load (home OR search)
    zoomPercent: Int,
    desktopMode: Boolean,
    reloadToken: Int,                  // bump this int to force reload (e.g., when UA toggles)
    onUrlChanged: (String) -> Unit,
    onError: (String) -> Unit,
    onGeoPrompt: (origin: String, respond: (allow: Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val systemUa = remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.setSupportZoom(true)
                settings.displayZoomControls = false

                // Capture system UA once
                if (systemUa.value == null) systemUa.value = settings.userAgentString

                // Apply UA
                settings.userAgentString = when {
                    desktopMode -> systemUa.value ?: settings.userAgentString
                    provider.overrideUserAgent != null -> provider.overrideUserAgent
                    else -> systemUa.value ?: settings.userAgentString
                }

                // Keep WebView scrollable inside parent scroll containers
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                        (v.parent as? android.view.ViewParent)?.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let(onUrlChanged)
                        val scale = zoomPercent / 100.0
                        evaluateJavascript("document.body.style.zoom = '$scale';", null)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        onError(error?.description?.toString() ?: "Load error")
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?
                    ) {
                        if (origin == null || callback == null) return
                        onGeoPrompt(origin) { allow ->
                            callback.invoke(origin, allow, /*remember*/ true)
                        }
                    }
                }

                loadUrl(targetUrl)
            }
        },
        update = { wv ->
            // Apply UA on updates (e.g., desktopMode toggled)
            val sys = systemUa.value ?: wv.settings.userAgentString
            val desiredUa = when {
                desktopMode -> sys
                provider.overrideUserAgent != null -> provider.overrideUserAgent
                else -> sys
            }
            if (wv.settings.userAgentString != desiredUa) {
                wv.settings.userAgentString = desiredUa
            }

            // Navigate if targetUrl changed
            if (wv.url != targetUrl) {
                wv.loadUrl(targetUrl)
            }

            // Update zoom
            val scale = zoomPercent / 100.0
            wv.evaluateJavascript("document.body.style.zoom = '$scale';", null)

            // Force reload if requested (even if URL is same)
            // Reload occurs after UA/zoom applied above.
            if (reloadToken != 0) {
                // Use evaluateJavascript ping to avoid infinite reload in the same frame;
                // or simply call reload() which keeps the same URL.
                wv.reload()
            }
        }
    )
}
