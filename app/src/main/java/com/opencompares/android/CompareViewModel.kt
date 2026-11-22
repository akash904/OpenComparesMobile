package com.opencompares.android

import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.webkit.WebViewClient
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencompares.android.data.AppSettings
import com.opencompares.android.data.SettingsRepository
import com.opencompares.android.prices.PaneReadResult
import com.opencompares.android.prices.PriceReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** Why a pane is showing an error instead of its page. */
data class PaneError(val kind: Kind, val failedUrl: String?) {
    enum class Kind { Offline, LoadFailed, Crashed }
}

/**
 * Everything the app knows about one marketplace pane.
 *
 * Navigation, reload and back are requests: bumping the id asks the WebView to
 * act once, even when the URL is the same as before.
 */
data class PaneState(
    /** The last URL the page reported. A recreated WebView starts here. */
    val currentUrl: String,
    val navUrl: String = currentUrl,
    val navId: Int = 0,
    val reloadId: Int = 0,
    val backId: Int = 0,
    val desktopMode: Boolean = false,
    val zoom: Int,
    val canGoBack: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
    val error: PaneError? = null,
    /** Bumped to build a fresh WebView after the old one's renderer died. */
    val instanceId: Int = 0
) {
    fun navigate(url: String) = copy(navUrl = url, navId = navId + 1, error = null)

    fun reload() = copy(reloadId = reloadId + 1, error = null)

    fun back() = copy(backId = backId + 1)

    fun toggleDesktop() = copy(desktopMode = !desktopMode, reloadId = reloadId + 1)

    fun started() = copy(loading = true, progress = 0, error = null)

    fun finished(url: String, canGoBack: Boolean) =
        copy(currentUrl = url, canGoBack = canGoBack, loading = false, progress = 100)

    fun failed(url: String, errorCode: Int) =
        copy(error = PaneError(errorKindFor(errorCode), url), loading = false)

    fun crashed() = copy(
        error = PaneError(PaneError.Kind.Crashed, currentUrl),
        loading = false,
        canGoBack = false
    )

    /** Retry after an error: rebuild a crashed WebView, or load the failed URL again. */
    fun retry(): PaneState = when (error?.kind) {
        PaneError.Kind.Crashed -> copy(error = null, instanceId = instanceId + 1)
        null -> reload()
        else -> navigate(error?.failedUrl ?: currentUrl)
    }

    companion object {
        fun initial(provider: Provider) = PaneState(currentUrl = provider.home(), zoom = provider.defaultZoom)

        /** Connection problems get an "offline" message; anything else is a failed load. */
        fun errorKindFor(errorCode: Int): PaneError.Kind = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT -> PaneError.Kind.Offline
            else -> PaneError.Kind.LoadFailed
        }
    }
}

class CompareViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    /** Null until the saved settings have loaded, so no default panes flash up first. */
    var settings by mutableStateOf<AppSettings?>(null)
        private set

    val providers: List<Provider> by derivedStateOf {
        MVP_PROVIDERS + (settings?.customProviders ?: emptyList())
    }

    val visibleProviders: List<Provider> by derivedStateOf {
        val selected = settings?.selectedProviders ?: emptySet()
        providers.filter { it.key in selected }
    }

    var query by mutableStateOf("")

    /** The zoom shown in the top bar. Zoom buttons apply it to every pane, or the fullscreen one. */
    var globalZoom by mutableIntStateOf(50)
        private set

    /** Key of the pane shown fullscreen, or null for the grid. */
    var fullScreenPane by mutableStateOf<String?>(null)
        private set

    var addressText by mutableStateOf<String?>(null)
        private set

    private val panes = mutableStateMapOf<String, PaneState>()

    private val priceReader = PriceReader(application)

    /** Latest prices read from the panes, in pane order. Empty until the first read. */
    var priceResults by mutableStateOf<List<PaneReadResult>>(emptyList())
        private set

    var readingPrices by mutableStateOf(false)
        private set

    /** Reads product cards from every visible pane, on the device, without any AI. */
    fun readPrices(store: PaneWebViewStore) {
        if (readingPrices) return
        readingPrices = true
        val views = visibleProviders.associate { p ->
            // A crashed pane has no usable page.
            p.key to store.view(p.key)?.takeUnless { pane(p).error?.kind == PaneError.Kind.Crashed }
        }
        viewModelScope.launch {
            try {
                priceResults = priceReader.readAll(views)
            } finally {
                readingPrices = false
            }
        }
    }

    init {
        viewModelScope.launch {
            repository.settings.collect { loaded ->
                settings = loaded
                if (fullScreenPane != null && fullScreenPane !in loaded.selectedProviders) fullScreenPane = null
            }
        }
        viewModelScope.launch {
            var lastGeocoded: Location? = null
            AppLocation.lastLocation.collect { loc ->
                if (loc == null) {
                    addressText = null
                    return@collect
                }
                // GPS updates arrive often; only look the address up again after a real move.
                val previous = lastGeocoded
                if (previous != null && addressText != null && previous.distanceTo(loc) < 100f) return@collect
                lastGeocoded = loc
                addressText = lookUpAddress(loc)
            }
        }
    }

    fun pane(provider: Provider): PaneState = panes[provider.key] ?: PaneState.initial(provider)

    private fun updatePane(key: String, change: (PaneState) -> PaneState) {
        val provider = providers.firstOrNull { it.key == key } ?: return
        panes[key] = change(panes[key] ?: PaneState.initial(provider))
    }

    // Search and navigation

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        visibleProviders.forEach { p -> updatePane(p.key) { it.navigate(p.buildUrl(q)) } }
    }

    fun goHome(key: String) {
        val provider = providers.firstOrNull { it.key == key } ?: return
        updatePane(key) { it.navigate(provider.home()) }
    }

    fun goBack(key: String) = updatePane(key) { it.back() }

    fun reload(key: String) = updatePane(key) { it.reload() }

    fun toggleDesktop(key: String) = updatePane(key) { it.toggleDesktop() }

    fun retry(key: String) = updatePane(key) { it.retry() }

    // Layout

    fun openFullscreen(key: String) {
        fullScreenPane = key
    }

    fun exitFullscreen() {
        fullScreenPane = null
    }

    fun zoomIn() = changeZoom(+5)

    fun zoomOut() = changeZoom(-5)

    private fun changeZoom(step: Int) {
        globalZoom = (globalZoom + step).coerceIn(5, 100)
        val targets = fullScreenPane?.let { listOf(it) } ?: visibleProviders.map { it.key }
        targets.forEach { key -> updatePane(key) { it.copy(zoom = globalZoom) } }
    }

    fun saveSettings(newSettings: AppSettings) {
        settings = newSettings
        if (fullScreenPane != null && fullScreenPane !in newSettings.selectedProviders) fullScreenPane = null
        viewModelScope.launch { repository.save(newSettings) }
    }

    // Events from the WebViews

    fun onPageStarted(key: String) = updatePane(key) { it.started() }

    fun onProgress(key: String, percent: Int) = updatePane(key) { it.copy(progress = percent) }

    fun onPageFinished(key: String, url: String, canGoBack: Boolean) =
        updatePane(key) { it.finished(url, canGoBack) }

    fun onMainFrameError(key: String, url: String, errorCode: Int) =
        updatePane(key) { it.failed(url, errorCode) }

    fun onRenderProcessGone(key: String) = updatePane(key) { it.crashed() }

    // Location

    private suspend fun lookUpAddress(loc: Location): String? {
        val geocoder = Geocoder(getApplication(), Locale.getDefault())
        val address: Address? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(results: MutableList<Address>) = cont.resume(results.firstOrNull())
                        override fun onError(errorMessage: String?) = cont.resume(null)
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                }
            }
        } catch (_: Exception) {
            null
        }
        return address?.let {
            listOfNotNull(it.subLocality, it.locality, it.adminArea, it.postalCode)
                .joinToString(", ")
                .ifEmpty { null }
        }
    }
}
