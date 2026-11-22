package com.opencompares.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** What a pane's WebView reports back. Implementations must be safe to call at any time. */
interface PaneWebViewEvents {
    fun onPageStarted(url: String)
    fun onProgress(percent: Int)
    fun onPageFinished(url: String, canGoBack: Boolean)
    fun onMainFrameError(url: String, errorCode: Int, description: String)

    /** The page's renderer died. The view is already destroyed when this is called. */
    fun onRenderProcessGone()
}

/**
 * A pane's WebView plus a record of what it has already applied from [PaneState].
 * Because the record lives on the view, moving the view between the grid and
 * fullscreen never repeats a navigation or a reload.
 */
class PaneWebView(context: Context) : ScrollChainingWebView(context) {
    var events: PaneWebViewEvents? = null
    var appliedNavId = 0
    var appliedReloadId = 0
    var appliedBackId = 0
    var appliedZoom: Int? = null

    /** Zoom the next finished page should get. */
    var targetZoom = 50
    var systemUserAgent: String = ""
    var isDestroyed = false
        private set

    fun destroyNow() {
        if (isDestroyed) return
        isDestroyed = true
        (parent as? ViewGroup)?.removeView(this)
        events = null
        chainTarget = null
        stopLoading()
        destroy()
    }
}

/**
 * Owns one WebView per marketplace for as long as the compare screen lives.
 * Composables borrow views from here instead of creating their own, so a page
 * survives being scrolled away, opened fullscreen, or closed from fullscreen.
 */
class PaneWebViewStore {
    private val views = mutableMapOf<String, PaneWebView>()
    private var timersPaused = false

    /** Returns this pane's view, creating it if needed, detached and ready to attach. */
    fun obtain(key: String, context: Context, create: (Context) -> PaneWebView): PaneWebView {
        val existing = views[key]?.takeUnless { it.isDestroyed }
        val view = existing ?: create(context).also { views[key] = it }
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    /** The live WebView for a pane, if it has one. Callers must not keep the reference. */
    fun view(key: String): PaneWebView? = views[key]?.takeUnless { it.isDestroyed }

    /** Frees panes whose marketplace is no longer selected. */
    fun retainOnly(keys: Set<String>) {
        views.keys.filterNot { it in keys }.forEach { key -> views.remove(key)?.destroyNow() }
    }

    fun forget(key: String) {
        views.remove(key)
    }

    fun onPause() {
        views.values.forEach { it.onPause() }
        // Pauses JavaScript timers for every WebView in the app, saving battery in the background.
        views.values.firstOrNull()?.pauseTimers()
        timersPaused = true
    }

    fun onResume() {
        if (timersPaused) views.values.firstOrNull()?.resumeTimers()
        timersPaused = false
        views.values.forEach { it.onResume() }
    }

    fun destroyAll() {
        views.values.forEach { it.destroyNow() }
        views.clear()
    }
}

/** Schemes a pane loads itself. Anything else is an app link and is ignored. */
private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

/** A modern desktop Chrome UA to request desktop sites reliably. */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Shows one marketplace pane's WebView and applies [pane] to it.
 *
 * - Navigates when [PaneState.navId] changes, reloads when [PaneState.reloadId]
 *   changes and goes back when [PaneState.backId] changes
 * - Applies the mobile or desktop user agent and the pane zoom
 * - Hands drags the page can't use to [scrollChain] (null keeps every drag)
 */
@Composable
fun PaneWebViewHost(
    provider: Provider,
    pane: PaneState,
    store: PaneWebViewStore,
    events: PaneWebViewEvents,
    scrollChain: ScrollChainTarget?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            store.obtain(provider.key, ctx) { createPaneWebView(it, provider, pane, store) }
        },
        update = { wv ->
            if (wv.isDestroyed) return@AndroidView
            wv.events = events
            wv.chainTarget = scrollChain
            applyPaneState(wv, provider, pane)
        },
        // The store owns the view; leaving composition must not destroy it.
        onRelease = { }
    )
}

private fun desiredUserAgent(wv: PaneWebView, provider: Provider, pane: PaneState): String = when {
    pane.desktopMode -> DESKTOP_USER_AGENT
    provider.overrideUserAgent != null -> provider.overrideUserAgent
    else -> wv.systemUserAgent
}

private fun applyPaneState(wv: PaneWebView, provider: Provider, pane: PaneState) {
    val ua = desiredUserAgent(wv, provider, pane)
    if (wv.settings.userAgentString != ua) wv.settings.userAgentString = ua

    wv.targetZoom = pane.zoom
    var navigated = false
    if (wv.appliedNavId != pane.navId) {
        wv.appliedNavId = pane.navId
        wv.appliedZoom = pane.zoom
        wv.loadUrl(pane.navUrl)
        navigated = true
    }

    if (wv.appliedZoom != pane.zoom) {
        wv.evaluateJavascript(pageSetupScript(cssViewportWidth(wv, pane.zoom), autoSelectZeptoLocation = false), null)
        wv.appliedZoom = pane.zoom
    }

    if (wv.appliedReloadId != pane.reloadId) {
        wv.appliedReloadId = pane.reloadId
        // A fresh navigation already loads the page with the new settings.
        if (!navigated) wv.reload()
    }

    if (wv.appliedBackId != pane.backId) {
        wv.appliedBackId = pane.backId
        if (wv.canGoBack()) wv.goBack()
    }
}

// The touch listener only claims the gesture and returns false, so clicks still reach the page.
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createPaneWebView(
    ctx: Context,
    provider: Provider,
    pane: PaneState,
    store: PaneWebViewStore
): PaneWebView = PaneWebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)
    settings.loadsImagesAutomatically = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = false
    settings.mediaPlaybackRequiresUserGesture = false
    settings.setGeolocationEnabled(true)
    // Off-screen pre-raster is left off: with every pane alive it would keep a
    // full set of rendered tiles in memory per pane, too much for low-end phones.

    // Some providers load http assets.
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

    // Cookies, including third-party ones, keep logins and sessions working.
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    isNestedScrollingEnabled = false
    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = true
    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    isClickable = true
    isFocusable = true
    isFocusableInTouchMode = true

    systemUserAgent = settings.userAgentString
    settings.userAgentString = desiredUserAgent(this, provider, pane)

    // The pane keeps every touch. Drags the page can't use reach the grid via chainTarget.
    setOnTouchListener { v, event ->
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        false
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // Shopping sites push "open in app" links (intent://, market://, ...) that a
            // WebView can't load. Ignore them so the pane stays on the current page.
            val scheme = request?.url?.scheme?.lowercase() ?: return false
            return scheme !in WEB_SCHEMES
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (url != null) events?.onPageStarted(url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (isDestroyed) return
            // Widen the CSS viewport so the page renders at the pane's zoom.
            evaluateJavascript(pageSetupScript(cssViewportWidth(this@apply, targetZoom), autoSelectZeptoLocation = true), null)
            appliedZoom = targetZoom
            if (url != null) events?.onPageFinished(url, canGoBack())
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            // Failed images or scripts are normal; only a failed page is worth showing.
            if (request?.isForMainFrame != true) return
            events?.onMainFrameError(
                request.url.toString(),
                error?.errorCode ?: WebViewClient.ERROR_UNKNOWN,
                error?.description?.toString() ?: "Load error"
            )
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Without this the whole app is killed when a page's renderer crashes or
            // is reclaimed for memory. Destroy just this view and let the pane offer a reload.
            val listener = events
            store.forget(provider.key)
            destroyNow()
            listener?.onRenderProcessGone()
            return true
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            events?.onProgress(newProgress)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            if (origin == null || callback == null) return
            // Auto-allow; the app controls the actual data via the JS interface.
            callback.invoke(origin, true, true)
        }

        // Open target=_blank and popup requests in the same WebView.
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport
            transport?.webView = view
            resultMsg?.sendToTarget()
            return true
        }
    }

    // Keep the initial scale at 100; zoom works by widening the CSS viewport instead.
    setInitialScale(100)
    addJavascriptInterface(LocationBridge(), LocationBridge.NAME)

    // New panes start where the pane last was, e.g. after a crash recovery.
    appliedNavId = pane.navId
    appliedReloadId = pane.reloadId
    appliedBackId = pane.backId
    targetZoom = pane.zoom
    appliedZoom = pane.zoom
    loadUrl(pane.currentUrl)
}

/** Exposes the app's last known location to pages as window.androidGeo. */
class LocationBridge {
    @JavascriptInterface
    fun getLastLocation(): String {
        val loc = AppLocation.lastLocation.value ?: return "null"
        return "{\"lat\":${loc.latitude},\"lon\":${loc.longitude},\"acc\":${loc.accuracy}}"
    }

    companion object {
        const val NAME = "androidGeo"
    }
}

/** CSS viewport width that makes the page render at [zoomPercent] of its normal size. */
private fun cssViewportWidth(wv: WebView, zoomPercent: Int): Int {
    val density = wv.resources.displayMetrics.density
    val widthPx = if (wv.width > 0) wv.width else wv.measuredWidth
    val widthDp = (widthPx / density).takeIf { it > 0 } ?: 360f
    return (widthDp * (100f / zoomPercent.coerceIn(5, 100))).coerceAtLeast(360f).toInt()
}

/**
 * Sets the viewport width and installs a geolocation shim backed by [LocationBridge].
 * With [autoSelectZeptoLocation], Zepto pages also get their location picker clicked through.
 */
private fun pageSetupScript(cssWidth: Int, autoSelectZeptoLocation: Boolean): String {
    val zepto = if (!autoSelectZeptoLocation) "" else """
      // Auto-simulate location selection flow (only for Zepto)
      if (window.location.hostname.includes('zeptonow.com')) {
        setTimeout(() => {
          const selectLocationBtn = document.querySelector('button[aria-label*="location"], [data-testid*="location"], button[title*="location"]') ||
                                 Array.from(document.querySelectorAll('button')).find(btn => btn.textContent.includes('Select Location') || btn.textContent.includes('Location'));
          if (selectLocationBtn) {
            selectLocationBtn.click();
            // Wait for the dialog to appear, then click the location enable button.
            setTimeout(() => {
              const enableLocationBtn = document.querySelector('button[aria-label*="enable"], [data-testid*="enable"], [data-testid*="allow"]') ||
                                     Array.from(document.querySelectorAll('button')).find(btn =>
                                         btn.textContent.includes('Enable') ||
                                         btn.textContent.includes('Allow') ||
                                         btn.textContent.includes('Yes') ||
                                         btn.textContent.includes('Continue')
                                     );
              if (enableLocationBtn) enableLocationBtn.click();
            }, 2000);
          }
        }, 3000);
      }
    """
    return """
    (function(){
      var m=document.querySelector('meta[name=\x76\x69\x65\x77\x70\x6f\x72\x74]');
      if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');document.head.appendChild(m);}
      m.setAttribute('content','width=' + $cssWidth + ', initial-scale=1.0');
      $zepto
      try {
        if (!window.__ocGeoInstalled && window.${LocationBridge.NAME}) {
          window.__ocGeoInstalled = true;
          const parse = (s) => { try { return JSON.parse(s); } catch(e){ return null } };
          const makePosition = (loc) => ({
            coords: { latitude: loc.lat, longitude: loc.lon, accuracy: loc.acc || 50, altitude: null, altitudeAccuracy: null, heading: null, speed: null },
            timestamp: Date.now()
          });
          const getLoc = () => parse(window.${LocationBridge.NAME}.getLastLocation());
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
}
