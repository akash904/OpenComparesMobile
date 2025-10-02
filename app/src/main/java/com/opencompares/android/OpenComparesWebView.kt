package com.opencompares.android

import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
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
    // A modern desktop Chrome UA to request desktop sites reliably
    val desktopUaString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    // Track last applied values to avoid redundant work in updates
    val lastAppliedZoomPercent = remember { mutableStateOf<Int?>(null) }
    val lastAppliedReloadToken = remember { mutableStateOf<Int?>(null) }
    // Track the last URL we explicitly loaded to avoid redundant reloads during recompositions
    val lastLoadedUrl = remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                settings.loadsImagesAutomatically = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.mediaPlaybackRequiresUserGesture = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.setOffscreenPreRaster(true)
                }
                
                // Apply initial zoom settings
                settings.setSupportZoom(true)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                
//                 // Apply initial zoom immediately
//                 val initialScale = 2 / 100.0
//                 settings.textZoom = zoomPercent

                // Allow mixed content (some providers load http assets)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                // Ensure cookies are enabled including 3P (identity/session flows)
                CookieManager.getInstance().setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }

                // Capture system UA once
                if (systemUa.value == null) systemUa.value = settings.userAgentString

                // Apply UA (true desktop UA when desktopMode is enabled)
                settings.userAgentString = when {
                    desktopMode -> desktopUaString
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
                        if (url != null) {
                            // Update tracked URL and notify parent only when we actually finished a page
                            lastLoadedUrl.value = url
                            onUrlChanged(url)
                        }
                        // Emulate desktop by widening CSS viewport based on desired zoom
                        val density = resources.displayMetrics.density
                        val cssWidth = ((this@apply.width / density) * (100f / zoomPercent)).coerceAtLeast(360f)
                        val js = """
                    (function(){
                      var m=document.querySelector('meta[name=\x76\x69\x65\x77\x70\x6f\x72\x74]');
                      if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');document.head.appendChild(m);} 
                      m.setAttribute('content','width=' + ${cssWidth.toInt()} + ', initial-scale=1.0');
                      try {
                        if (!window.__ocGeoInstalled && window.androidGeo) {
                          window.__ocGeoInstalled = true;
                          const parse = (s) => { try { return JSON.parse(s); } catch(e){ return null } };
                          const makePosition = (loc) => ({
                            coords: { latitude: loc.lat, longitude: loc.lon, accuracy: loc.acc || 50, altitude: null, altitudeAccuracy: null, heading: null, speed: null },
                            timestamp: Date.now()
                          });
                          const getLoc = () => parse(window.androidGeo.getLastLocation());
                          const geo = {
                            getCurrentPosition: function(success, error){
                              const l = getLoc();
                              if (l && typeof success === 'function') success(makePosition(l));
                              else if (typeof error === 'function') error({ code: 1, message: 'Location unavailable' });
                            },
                            watchPosition: function(success){
                              const id = Math.floor(Math.random()*1e9);
                              window.__ocWatchers = window.__ocWatchers || {};
                              window.__ocWatchers[id] = success;
                              return id;
                            },
                            clearWatch: function(id){ if (window.__ocWatchers) delete window.__ocWatchers[id]; }
                          };
                          if (!navigator.geolocation) navigator.geolocation = geo; else { try { Object.setPrototypeOf(navigator.geolocation, geo); } catch(e) { navigator.geolocation = geo; } }
                        }
                      } catch(e) {}
                    })();
                """.trimIndent()
                        evaluateJavascript(js, null)
                        lastAppliedZoomPercent.value = zoomPercent
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
                        // Auto-allow; app controls actual data via JS interface
                        callback.invoke(origin, true, true)
                    }

                    // Open target=_blank and popup requests in the same WebView
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        // Try to extract URL from the transport and load it here
                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        transport?.webView = view
                        resultMsg?.sendToTarget()
                        return true
                    }
                }

                // Keep initial scale at 100; we manipulate CSS viewport instead
                setInitialScale(100)
                // Add JS interface that exposes app-provided location
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun getLastLocation(): String {
                        val loc = AppLocation.lastLocation.value
                        return if (loc != null) {
                            "{" +
                                "\"lat\":" + loc.latitude + "," +
                                "\"lon\":" + loc.longitude + "," +
                                "\"acc\":" + loc.accuracy +
                            "}"
                        } else {
                            "null"
                        }
                    }
                }, "androidGeo")
                lastAppliedZoomPercent.value = zoomPercent
                loadUrl(targetUrl)
                lastLoadedUrl.value = targetUrl
            }
        },
        update = { wv ->
            // Apply UA on updates (e.g., desktopMode toggled)
            val sys = systemUa.value ?: wv.settings.userAgentString
            val desiredUa = when {
                desktopMode -> desktopUaString
                provider.overrideUserAgent != null -> provider.overrideUserAgent
                else -> sys
            }
            if (wv.settings.userAgentString != desiredUa) {
                wv.settings.userAgentString = desiredUa
            }

            // Navigate only if the intended target differs from what we last loaded
            if (lastLoadedUrl.value != targetUrl) {
                wv.setInitialScale(100)
                lastAppliedZoomPercent.value = zoomPercent
                wv.loadUrl(targetUrl)
                lastLoadedUrl.value = targetUrl
            }

            // Update viewport when zoom changes to force denser responsive layouts
            if (lastAppliedZoomPercent.value != zoomPercent) {
                val density = wv.resources.displayMetrics.density
                val widthPx = if (wv.width > 0) wv.width else wv.measuredWidth
                val cssWidth = (((widthPx / density).takeIf { it > 0 } ?: 360f) * (100f / zoomPercent)).coerceAtLeast(360f)
                val js = """
                    (function(){
                      var m=document.querySelector('meta[name=\x76\x69\x65\x77\x70\x6f\x72\x74]');
                      if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');document.head.appendChild(m);} 
                      m.setAttribute('content','width=' + ${cssWidth.toInt()} + ', initial-scale=1.0');
                      try {
                        if (!window.__ocGeoInstalled && window.androidGeo) {
                          window.__ocGeoInstalled = true;
                          const parse = (s) => { try { return JSON.parse(s); } catch(e){ return null } };
                          const makePosition = (loc) => ({
                            coords: { latitude: loc.lat, longitude: loc.lon, accuracy: loc.acc || 50, altitude: null, altitudeAccuracy: null, heading: null, speed: null },
                            timestamp: Date.now()
                          });
                          const getLoc = () => parse(window.androidGeo.getLastLocation());
                          const geo = {
                            getCurrentPosition: function(success, error){
                              const l = getLoc();
                              if (l && typeof success === 'function') success(makePosition(l));
                              else if (typeof error === 'function') error({ code: 1, message: 'Location unavailable' });
                            },
                            watchPosition: function(success){
                              const id = Math.floor(Math.random()*1e9);
                              window.__ocWatchers = window.__ocWatchers || {};
                              window.__ocWatchers[id] = success;
                              return id;
                            },
                            clearWatch: function(id){ if (window.__ocWatchers) delete window.__ocWatchers[id]; }
                          };
                          if (!navigator.geolocation) navigator.geolocation = geo; else { try { Object.setPrototypeOf(navigator.geolocation, geo); } catch(e) { navigator.geolocation = geo; } }
                        }
                      } catch(e) {}
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
                lastAppliedZoomPercent.value = zoomPercent
            }

            // Force reload only when the token changes
            if (lastAppliedReloadToken.value != reloadToken) {
                if (reloadToken != 0) {
                    wv.reload()
                }
                lastAppliedReloadToken.value = reloadToken
            }
        }
    )
}
