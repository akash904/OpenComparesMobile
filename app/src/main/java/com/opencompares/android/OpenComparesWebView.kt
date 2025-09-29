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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OpenComparesWebView(
    provider: Provider,
    query: String,
    zoomPercent: Int,
    onUrlChanged: (String) -> Unit,
    onError: (String) -> Unit,
    onGeoPrompt: (origin: String, respond: (allow: Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val url = remember(provider.key, query) { provider.buildUrl(query) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.setSupportZoom(true)
                settings.displayZoomControls = false
                provider.overrideUserAgent?.let { settings.userAgentString = it }

                // Let WebView consume its own scroll gestures
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN ||
                        event.action == MotionEvent.ACTION_MOVE
                    ) {
                        // parent is a ViewParent; call the method on that type
                        (v.parent as? android.view.ViewParent)
                            ?.requestDisallowInterceptTouchEvent(true)
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
                            callback.invoke(origin, allow, /* remember */ true)
                        }
                    }
                }

                loadUrl(url)
            }
        },
        update = { wv ->
            if (wv.url != url) wv.loadUrl(url)
            provider.overrideUserAgent?.let {
                if (wv.settings.userAgentString != it) wv.settings.userAgentString = it
            }
            val scale = zoomPercent / 100.0
            wv.evaluateJavascript("document.body.style.zoom = '$scale';", null)
        }
    )
}
