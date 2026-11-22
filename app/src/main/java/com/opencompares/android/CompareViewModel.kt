package com.opencompares.android

import android.app.Application
import android.content.pm.ApplicationInfo
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
import com.opencompares.android.data.DeliveryPlace
import com.opencompares.android.data.SettingsRepository
import com.opencompares.android.chatgpt.ChatGptAccount
import com.opencompares.android.chatgpt.SiwcApiException
import com.opencompares.android.prices.Comparison
import com.opencompares.android.prices.ComparisonFormatException
import com.opencompares.android.prices.ComparisonParser
import com.opencompares.android.prices.ComparisonPrompts
import com.opencompares.android.prices.PaneReadResult
import com.opencompares.android.prices.PriceReader
import com.opencompares.android.shopping.PaneDriver
import com.opencompares.android.shopping.ShoppingSession
import com.opencompares.android.sites.FAR_AWAY_KM
import com.opencompares.android.sites.LocationState
import com.opencompares.android.sites.LoginState
import com.opencompares.android.sites.SiteStatus
import com.opencompares.android.sites.SiteStatusReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val instanceId: Int = 0,
    /** Counts finished page loads, so code driving the pane can wait for the next one. */
    val loads: Int = 0
) {
    fun navigate(url: String) = copy(navUrl = url, navId = navId + 1, error = null)

    fun reload() = copy(reloadId = reloadId + 1, error = null)

    fun back() = copy(backId = backId + 1)

    fun toggleDesktop() = copy(desktopMode = !desktopMode, reloadId = reloadId + 1)

    fun started() = copy(loading = true, progress = 0, error = null)

    fun finished(url: String, canGoBack: Boolean) =
        copy(currentUrl = url, canGoBack = canGoBack, loading = false, progress = 100, loads = loads + 1)

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

/** Looking up a place the shopper typed in. */
sealed interface PlaceLookup {
    data object Idle : PlaceLookup
    data object Searching : PlaceLookup
    data class Failed(val message: String) : PlaceLookup
}

/** Progress of a ChatGPT comparison of the prices read from the panes. */
sealed interface ComparisonState {
    data object Idle : ComparisonState
    data object Running : ComparisonState
    data class Done(val comparison: Comparison) : ComparisonState
    data class Failed(val message: String, val signInNeeded: Boolean = false) : ComparisonState
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

    private val chatGpt = ChatGptAccount.get(application)

    /** The latest comparison of [priceResults]; reset whenever prices are read again. */
    var comparison by mutableStateOf<ComparisonState>(ComparisonState.Idle)
        private set

    private var compareJob: Job? = null

    private var lastReadAtMillis = 0L

    /** The shopping chat on the landing screen. */
    val shopping = ShoppingSession(
        viewModelScope,
        chatGpt,
        priceReader,
        debugLog = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    )

    // Site setup: login and delivery location on each marketplace

    private val statusReader = SiteStatusReader(application)

    /** What each pane last said about login and delivery location. */
    val siteStatus = mutableStateMapOf<String, SiteStatus>()

    /** The phone's own location, as latitude and longitude. */
    var phonePoint by mutableStateOf<Pair<Double, Double>?>(null)
        private set

    var placeLookup by mutableStateOf<PlaceLookup>(PlaceLookup.Idle)
        private set

    /** A typed-in delivery place, or null for the phone's location. */
    val deliveryPlace: DeliveryPlace? get() = settings?.deliveryPlace

    /** Where the shopper wants delivery: the typed-in place, else the phone's location. */
    val deliveryPoint: Pair<Double, Double>?
        get() = deliveryPlace?.let { it.lat to it.lon } ?: phonePoint

    /** Sites used without logging in, by the shopper's choice. */
    val skippedLogin: Set<String> get() = settings?.skippedLogin ?: emptySet()

    /** Sites used with their own idea of the shopper's area, by the shopper's choice. */
    val skippedLocation: Set<String> get() = settings?.skippedLocation ?: emptySet()

    fun refreshStatus(key: String, store: PaneWebViewStore) {
        val view = store.view(key) ?: return
        viewModelScope.launch {
            val now = statusReader.read(view) ?: return@launch
            val before = siteStatus[key]
            siteStatus[key] = now
            // New login or address: prices may differ, so don't reuse this site's saved results.
            if (before != null && (before.login != now.login || before.location != now.location || before.label != now.label)) {
                shopping.forgetSite(key)
            }
        }
    }

    fun refreshStatuses(store: PaneWebViewStore) = visibleProviders.forEach { refreshStatus(it.key, store) }

    /** Use a site without logging in (or undo that). The site stays in searches. */
    fun skipLogin(key: String, skip: Boolean) {
        val current = settings ?: return
        saveSettings(current.copy(skippedLogin = if (skip) current.skippedLogin + key else current.skippedLogin - key))
    }

    /** Use a site with whatever area it picked (or undo that). The site stays in searches. */
    fun skipLocation(key: String, skip: Boolean) {
        val current = settings ?: return
        saveSettings(current.copy(skippedLocation = if (skip) current.skippedLocation + key else current.skippedLocation - key))
    }

    fun usePhoneLocation() {
        val current = settings ?: return
        placeLookup = PlaceLookup.Idle
        saveSettings(current.copy(deliveryPlace = null))
    }

    /** Finds a typed area or pincode and uses it as the delivery location for every site. */
    fun useTypedPlace(query: String) {
        val q = query.trim()
        if (q.isEmpty() || placeLookup == PlaceLookup.Searching) return
        placeLookup = PlaceLookup.Searching
        viewModelScope.launch {
            val place = findPlace(q)
            val current = settings
            if (place == null || current == null) {
                placeLookup = PlaceLookup.Failed("Couldn't find \"$q\". Try an area and city, or a pincode.")
            } else {
                placeLookup = PlaceLookup.Idle
                saveSettings(current.copy(deliveryPlace = place))
            }
        }
    }

    /**
     * Why a site's prices may not suit the shopper, or null when all looks right.
     * Login walls are reported by the shopping run itself.
     */
    fun siteWarning(key: String): String? {
        val status = siteStatus[key] ?: return null
        val name = providers.firstOrNull { it.key == key }?.displayName ?: key
        val point = deliveryPoint
        val far = point?.let { status.distanceKmFrom(it.first, it.second) }?.takeIf { it > FAR_AWAY_KM }
        return when {
            status.location == LocationState.Missing -> "$name has no delivery address set, so its prices may be for another area."
            status.location == LocationState.Guessed ->
                "$name is guessing your area" + (status.label?.let { " ($it)" } ?: "") + ", so its prices may not be for your address."
            far != null -> "$name is delivering to ${status.label ?: "another address"}, ${Math.round(far)} km from your location."
            else -> null
        }
    }

    /** True when login and address are each either done or skipped by the shopper. */
    fun siteReady(key: String): Boolean {
        val status = siteStatus[key] ?: return false
        val loginOk = status.login == LoginState.In || key in skippedLogin
        val placeOk = (status.location == LocationState.Set && siteWarning(key) == null) || key in skippedLocation
        return loginOk && placeOk
    }

    private suspend fun findPlace(query: String): DeliveryPlace? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(getApplication(), Locale.getDefault())
        // The marketplaces are Indian, so look there first.
        val q = if (query.contains("india", ignoreCase = true)) query else "$query, India"
        val address: Address? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(q, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(results: MutableList<Address>) = cont.resume(results.firstOrNull())
                        override fun onError(errorMessage: String?) = cont.resume(null)
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(q, 1)?.firstOrNull()
                }
            }
        } catch (_: Exception) {
            null
        }
        return address?.let {
            val label = listOfNotNull(it.subLocality, it.locality, it.postalCode).distinct().joinToString(", ").ifEmpty { query }
            DeliveryPlace(label, it.latitude, it.longitude)
        }
    }

    /** Searches every visible pane for each item on the shopping list. */
    fun findBestPrices(store: PaneWebViewStore) {
        // Every pane must be on screen (under the chat) to load and render its results.
        fullScreenPane = null
        shopping.findBestPrices(object : PaneDriver {
            override val siteKeys = visibleProviders
                .filter { pane(it).error?.kind != PaneError.Kind.Crashed }
                .map { it.key }

            override fun warning(key: String) = siteWarning(key)

            override fun siteName(key: String) = providers.firstOrNull { it.key == key }?.displayName ?: key

            override fun searchUrl(key: String, query: String) =
                providers.firstOrNull { it.key == key }?.buildUrl(query)

            override fun navigate(key: String, url: String) = updatePane(key) { it.navigate(url) }

            override fun loads(key: String) = panes[key]?.loads ?: 0

            override fun view(key: String) = store.view(key)
        })
    }

    /**
     * Opening the prices sheet reads the panes afresh, unless a comparison of a recent
     * read is running or finished, which closing and reopening the sheet should not throw away.
     */
    fun openPrices(store: PaneWebViewStore) {
        val recent = System.currentTimeMillis() - lastReadAtMillis < KEEP_COMPARISON_MS
        if (recent && (comparison is ComparisonState.Running || comparison is ComparisonState.Done)) return
        readPrices(store)
    }

    /** Reads product cards from every visible pane, on the device, without any AI. */
    fun readPrices(store: PaneWebViewStore) {
        if (readingPrices) return
        readingPrices = true
        compareJob?.cancel()
        comparison = ComparisonState.Idle
        val views = visibleProviders.associate { p ->
            // A crashed pane has no usable page.
            p.key to store.view(p.key)?.takeUnless { pane(p).error?.kind == PaneError.Kind.Crashed }
        }
        viewModelScope.launch {
            try {
                priceResults = priceReader.readAll(views)
                lastReadAtMillis = System.currentTimeMillis()
            } finally {
                readingPrices = false
            }
        }
    }

    /**
     * Asks ChatGPT, on the user's plan, which listings are the same product. The model only
     * groups them; best value is worked out here from the prices the app read itself.
     */
    fun compareWithChatGpt() {
        if (comparison is ComparisonState.Running || readingPrices) return
        val pages = priceResults.filterIsInstance<PaneReadResult.Read>().map { it.page }.filter { it.offers.isNotEmpty() }
        if (pages.size < 2) {
            comparison = ComparisonState.Failed("Prices from at least two sites are needed. Search, then read prices again.")
            return
        }
        val prompt = ComparisonPrompts.build(query.trim(), pages) { key ->
            providers.firstOrNull { it.key == key }?.displayName ?: key
        }
        comparison = ComparisonState.Running
        compareJob = viewModelScope.launch {
            comparison = try {
                ComparisonState.Done(ComparisonParser.parse(chatGpt.ask(prompt.instructions, prompt.input), prompt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiwcApiException) {
                ComparisonState.Failed(e.userMessage, signInNeeded = e.requiresSignIn)
            } catch (e: ComparisonFormatException) {
                ComparisonState.Failed("ChatGPT's answer couldn't be read. Try again.")
            } catch (e: Exception) {
                ComparisonState.Failed(e.message ?: "The comparison failed. Try again.")
            }
        }
    }

    /** Shows a product's page in its own pane. Only web links are followed. */
    fun openOffer(key: String, url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        updatePane(key) { it.navigate(url) }
        if (fullScreenPane != null) fullScreenPane = key
    }

    init {
        viewModelScope.launch {
            repository.settings.collect { loaded ->
                settings = loaded
                AppLocation.manualPlace = loaded.deliveryPlace
                if (fullScreenPane != null && fullScreenPane !in loaded.selectedProviders) fullScreenPane = null
            }
        }
        viewModelScope.launch {
            var lastGeocoded: Location? = null
            AppLocation.lastLocation.collect { loc ->
                phonePoint = loc?.let { it.latitude to it.longitude }
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
        AppLocation.manualPlace = newSettings.deliveryPlace
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

    private companion object {
        const val KEEP_COMPARISON_MS = 5 * 60 * 1000L
    }
}
