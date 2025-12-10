package com.opencompares.opencompbrowser

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale

class MainActivity : AppCompatActivity() {
    
    private val webViews = mutableListOf<WebView>()
    private val tabButtons = mutableListOf<Button>()
    private val tabMarketplaces = mutableListOf<Marketplace>() // Store marketplace for each tab
    private lateinit var searchBar: EditText
    private lateinit var searchButton: Button
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var zoomSlider: SeekBar
    private lateinit var zoomValue: TextView
    private lateinit var heightSlider: SeekBar
    private lateinit var heightValue: TextView
    private lateinit var tabButtonsContainer: LinearLayout
    private lateinit var webViewsContainer: LinearLayout
    private lateinit var addTabButton: Button
    private lateinit var navigationBar: LinearLayout
    private lateinit var currentUrlDisplay: TextView
    private lateinit var settingsToggleButton: ImageButton
    private lateinit var zoomControlPanel: LinearLayout
    private lateinit var heightControlPanel: LinearLayout
    private lateinit var locationDisplay: TextView
    private lateinit var locationDisplayRow: LinearLayout
    
    private var controlsVisible = true // Toggle state for controls visibility
    private var activeTabIndex = 0
    private val tabZoomLevels = mutableListOf<Int>()
    private var defaultTabHeightDp = 430
    private val webViewContainers = mutableListOf<FrameLayout>()
    
    private lateinit var searchViewModel: SearchViewModel
    
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private var locationPermissionGranted = false
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    private var locationListener: LocationListener? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize ViewModel
        searchViewModel = ViewModelProvider(this)[SearchViewModel::class.java]
        
        // Handle back button press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                getActiveWebView()?.let { activeWebView ->
                    if (activeWebView.canGoBack()) {
                        activeWebView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                } ?: run {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        
        // Initialize container views
        tabButtonsContainer = findViewById(R.id.tabButtonsContainer)
        webViewsContainer = findViewById(R.id.webViewsContainer)
        addTabButton = findViewById(R.id.addTabButton)
        navigationBar = findViewById(R.id.navigationBar)
        currentUrlDisplay = findViewById(R.id.currentUrlDisplay)
        
        // Initialize views
        searchBar = findViewById(R.id.searchBar)
        searchButton = findViewById(R.id.searchButton)
        settingsToggleButton = findViewById(R.id.settingsToggleButton)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        refreshButton = findViewById(R.id.refreshButton)
        zoomSlider = findViewById(R.id.zoomSlider)
        zoomValue = findViewById(R.id.zoomValue)
        heightSlider = findViewById(R.id.heightSlider)
        heightValue = findViewById(R.id.heightValue)
        zoomControlPanel = findViewById(R.id.zoomControlPanel)
        heightControlPanel = findViewById(R.id.heightControlPanel)
        locationDisplay = findViewById(R.id.locationDisplay)
        locationDisplayRow = findViewById(R.id.locationDisplayRow)
        
        // Set up button listeners
        setupButtons()
        
        // Set up zoom slider
        setupZoomSlider()
        
        // Set up height slider
        setupHeightSlider()
        
        // Request location permission
        requestLocationPermission()
        
        // Initialize tabs from marketplaces
        initializeMarketplaceTabs()
        
        // Observe search query changes
        observeSearchQuery()
    }
    
    private fun requestLocationPermission() {
        val fineLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (fineLocationPermission == PackageManager.PERMISSION_GRANTED ||
            coarseLocationPermission == PackageManager.PERMISSION_GRANTED) {
            locationPermissionGranted = true
            enableLocationForAllWebViews()
            startLocationUpdates()
        } else {
            // Request permissions
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            locationPermissionGranted = grantResults.isNotEmpty() &&
                    (grantResults[0] == PackageManager.PERMISSION_GRANTED ||
                     (grantResults.size > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED))
            
            // Update all WebViews with location permission
            if (locationPermissionGranted) {
                enableLocationForAllWebViews()
                startLocationUpdates()
            }
        }
    }
    
    private fun enableLocationForAllWebViews() {
        webViews.forEach { webView ->
            setupLocationForWebView(webView)
        }
    }
    
    private fun setupLocationForWebView(webView: WebView) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // Always allow location access for all origins
                callback?.invoke(origin ?: "", true, false)
            }
        }
    }
    
    private fun startLocationUpdates() {
        if (!locationPermissionGranted) return
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                updateLocationDisplay()
            }
            
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        
        try {
            // Get last known location first
            val lastKnownLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            if (lastKnownLocation != null) {
                currentLocation = lastKnownLocation
                updateLocationDisplay()
            }
            
            // Request location updates
            locationListener?.let { listener ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        10000L, // 10 seconds
                        10f, // 10 meters
                        listener
                    )
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000L,
                        10f,
                        listener
                    )
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateLocationDisplay() {
        currentLocation?.let { location ->
            // Get human-readable address using geocoding
            getAddressFromLocation(location.latitude, location.longitude)
        } ?: run {
            locationDisplay.text = getString(R.string.getting_location)
        }
    }
    
    private fun getAddressFromLocation(latitude: Double, longitude: Double) {
        lifecycleScope.launch {
            try {
                val address = withContext(Dispatchers.IO) {
                    if (Geocoder.isPresent()) {
                        val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                        addresses?.firstOrNull()
                    } else {
                        null
                    }
                }
                
                address?.let {
                    val addressText = formatAddress(it)
                    locationDisplay.text = addressText
                } ?: run {
                    // Fallback to coordinates if geocoding fails
                    locationDisplay.text = "${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)}"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to coordinates on error
                locationDisplay.text = "${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)}"
            }
        }
    }
    
    private fun formatAddress(address: Address): String {
        val parts = mutableListOf<String>()
        
        // Add street address if available
        address.getAddressLine(0)?.let { parts.add(it) }
        
        // Add locality (city) if not already included
        if (parts.isEmpty() || !parts[0].contains(address.locality ?: "")) {
            address.locality?.let { parts.add(it) }
        }
        
        // Add admin area (state/province) if available
        address.adminArea?.let { parts.add(it) }
        
        // Add country if available
        address.countryName?.let { parts.add(it) }
        
        return if (parts.isNotEmpty()) {
            parts.joinToString(", ")
        } else {
            // Fallback to coordinates if no address parts
            "${String.format("%.6f", address.latitude)}, ${String.format("%.6f", address.longitude)}"
        }
    }
    
    private fun initializeMarketplaceTabs() {
        val marketplaces = searchViewModel.marketplaces.value
        marketplaces.forEach { marketplace ->
            addMarketplaceTab(marketplace)
        }
        
        // Set first tab as active
        if (webViews.isNotEmpty()) {
            setActiveTab(0)
        }
    }
    
    private fun addMarketplaceTab(marketplace: Marketplace, query: String = "") {
        val tabIndex = webViews.size
        
        // Create tab button
        val tabButton = Button(this).apply {
            text = marketplace.name
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(8, 4, 8, 4)
            }
            setBackgroundColor(Color.parseColor("#757575"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                setActiveTab(tabIndex)
            }
        }
        tabButtonsContainer.addView(tabButton)
        tabButtons.add(tabButton)
        tabMarketplaces.add(marketplace)
        
        // Create WebView container with border
        val borderWidth = 3 // Border width in dp
        val webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpToPx(defaultTabHeightDp),
                1f
            ).apply {
                setMargins(4, 4, 4, 4) // Add margin for border visibility
            }
            background = resources.getDrawable(R.drawable.tab_border, theme)
            setPadding(dpToPx(borderWidth), dpToPx(borderWidth), dpToPx(borderWidth), dpToPx(borderWidth))
            isClickable = false
            isFocusable = false
        }
        
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        
        webViewContainer.addView(webView)
        webViewContainers.add(webViewContainer)
        
        // Add WebView to appropriate row
        val rowIndex = tabIndex / 2
        val isFirstInRow = tabIndex % 2 == 0
        
        if (isFirstInRow) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(webViewContainer)
            webViewsContainer.addView(row)
        } else {
            val row = webViewsContainer.getChildAt(rowIndex) as LinearLayout
            row.addView(webViewContainer)
        }
        
        webViews.add(webView)
        tabZoomLevels.add(65) // Default zoom 65%
        
        // Configure WebView
        setupWebView(webView)
        
        // Enable location if permission was already granted
        if (locationPermissionGranted) {
            setupLocationForWebView(webView)
        }
        
        // Load initial URL or search query
        if (query.isNotEmpty()) {
            loadSearchQuery(marketplace, query, webView)
        } else {
            // Load marketplace homepage or default page
            webView.loadUrl(marketplace.urlTemplate.replace("{query}", ""))
        }
    }
    
    private fun loadSearchQuery(marketplace: Marketplace, query: String, webView: WebView) {
        val searchUrl = marketplace.getSearchUrl(query)
        webView.loadUrl(searchUrl)
    }
    
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            // Enable geolocation
            setGeolocationEnabled(true)
        }
        
        webView.isNestedScrollingEnabled = false
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = true
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        
        // Set up location permission handling
        if (locationPermissionGranted) {
            setupLocationForWebView(webView)
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let {
                    view?.let { wv ->
                        val index = webViews.indexOf(wv)
                        if (index >= 0 && index < tabZoomLevels.size) {
                            applyZoomToWebView(wv, tabZoomLevels[index])
                        }
                        
                        if (wv == getActiveWebView()) {
                            currentUrlDisplay.text = it
                            updateNavigationButtons()
                        }
                    }
                }
            }
        }
    }
    
    private fun setupButtons() {
        searchButton.setOnClickListener {
            performSearch()
        }
        
        searchBar.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }
        
        backButton.setOnClickListener {
            getActiveWebView()?.let { activeWebView ->
                if (activeWebView.canGoBack()) {
                    activeWebView.goBack()
                }
            }
        }
        
        forwardButton.setOnClickListener {
            getActiveWebView()?.let { activeWebView ->
                if (activeWebView.canGoForward()) {
                    activeWebView.goForward()
                }
            }
        }
        
        refreshButton.setOnClickListener {
            getActiveWebView()?.reload()
        }
        
        addTabButton.setOnClickListener {
            // Show dialog to add marketplace (for future enhancement)
            // For now, just add a new marketplace tab
            val newMarketplace = Marketplace(
                id = "custom_${System.currentTimeMillis()}",
                name = "Custom",
                urlTemplate = "https://www.google.com/search?q={query}"
            )
            addMarketplaceTab(newMarketplace, searchViewModel.currentQuery.value)
        }
        
        settingsToggleButton.setOnClickListener {
            toggleControlsVisibility()
        }
    }
    
    private fun toggleControlsVisibility() {
        controlsVisible = !controlsVisible
        
        if (controlsVisible) {
            // Show all controls
            navigationBar.visibility = View.VISIBLE
            tabButtonsContainer.visibility = View.VISIBLE
            addTabButton.visibility = View.VISIBLE
            locationDisplayRow.visibility = View.VISIBLE
            zoomControlPanel.visibility = View.VISIBLE
            heightControlPanel.visibility = View.VISIBLE
            settingsToggleButton.setImageResource(android.R.drawable.ic_menu_preferences)
        } else {
            // Hide all controls
            navigationBar.visibility = View.GONE
            tabButtonsContainer.visibility = View.GONE
            addTabButton.visibility = View.GONE
            locationDisplayRow.visibility = View.GONE
            zoomControlPanel.visibility = View.GONE
            heightControlPanel.visibility = View.GONE
            settingsToggleButton.setImageResource(android.R.drawable.ic_menu_view)
        }
    }
    
    private fun performSearch() {
        val query = searchBar.text.toString().trim()
        if (query.isEmpty()) {
            return
        }
        
        // Update ViewModel
        searchViewModel.setQuery(query)
        searchViewModel.setSearching(true)
        
        // Update all tabs with the search query
        webViews.forEachIndexed { index, webView ->
            if (index < tabMarketplaces.size) {
                val marketplace = tabMarketplaces[index]
                loadSearchQuery(marketplace, query, webView)
            }
        }
        
        // Hide keyboard
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
        
        searchViewModel.setSearching(false)
    }
    
    private fun observeSearchQuery() {
        searchViewModel.currentQuery
            .onEach { query ->
                if (query.isNotEmpty() && searchBar.text.toString() != query) {
                    searchBar.setText(query)
                }
            }
            .launchIn(lifecycleScope)
    }
    
    private fun setupZoomSlider() {
        if (tabZoomLevels.isNotEmpty()) {
            // Convert zoom percent (50-120%) to slider progress (0-70)
            zoomSlider.progress = tabZoomLevels[activeTabIndex] - 50
        }
        
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && webViews.isNotEmpty()) {
                    // Convert slider progress (0-70) to zoom percent (50-120%)
                    val zoomPercent = progress + 50
                    if (activeTabIndex < tabZoomLevels.size && zoomPercent <= 120) {
                        tabZoomLevels[activeTabIndex] = zoomPercent
                        applyZoomToActiveTab(zoomPercent)
                        zoomValue.text = "$zoomPercent%"
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun setupHeightSlider() {
        heightSlider.progress = defaultTabHeightDp - 150
        heightValue.text = "${defaultTabHeightDp}dp"
        
        heightSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val heightDp = progress + 150
                    defaultTabHeightDp = heightDp
                    heightValue.text = "${heightDp}dp"
                    updateAllTabHeights(heightDp)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun updateAllTabHeights(heightDp: Int) {
        val heightPx = dpToPx(heightDp)
        webViewContainers.forEach { container ->
            val layoutParams = container.layoutParams as? LinearLayout.LayoutParams
            layoutParams?.let {
                it.height = heightPx
                container.layoutParams = it
            }
        }
    }
    
    private fun applyZoomToActiveTab(zoomPercent: Int) {
        getActiveWebView()?.let { webView ->
            applyZoomToWebView(webView, zoomPercent)
        }
    }
    
    private fun applyZoomToWebView(webView: WebView, zoomPercent: Int) {
        val zoomFactor = zoomPercent / 100.0
        val javascript = """
            (function() {
                var existingStyle = document.getElementById('webview-zoom-style');
                if (existingStyle) {
                    existingStyle.remove();
                }
                
                var style = document.createElement('style');
                style.id = 'webview-zoom-style';
                style.innerHTML = 'html, body { zoom: $zoomFactor !important; }';
                if (document.head) {
                    document.head.appendChild(style);
                } else {
                    document.documentElement.appendChild(style);
                }
                
                if (document.documentElement) {
                    document.documentElement.style.zoom = '$zoomFactor';
                }
                if (document.body) {
                    document.body.style.zoom = '$zoomFactor';
                }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(javascript, null)
    }
    
    private fun setActiveTab(index: Int) {
        if (index < 0 || index >= webViews.size) return
        
        activeTabIndex = index
        updateActiveTabUI()
        
        getActiveWebView()?.let { activeWebView ->
            currentUrlDisplay.text = activeWebView.url ?: ""
            updateNavigationButtons()
        }
        
        // Show navigation bar when a tab is active
        navigationBar.visibility = View.VISIBLE
        
        updateZoomSlider()
    }
    
    private fun updateZoomSlider() {
        if (webViews.isNotEmpty() && activeTabIndex < tabZoomLevels.size) {
            val currentZoom = tabZoomLevels[activeTabIndex]
            // Convert zoom percent (50-120%) to slider progress (0-70)
            zoomSlider.progress = currentZoom - 50
            zoomValue.text = "$currentZoom%"
            applyZoomToActiveTab(currentZoom)
        }
    }
    
    private fun updateActiveTabUI() {
        tabButtons.forEachIndexed { index, button ->
            if (index == activeTabIndex) {
                button.setBackgroundColor(Color.parseColor("#9C27B0"))
                button.setTextColor(Color.WHITE)
            } else {
                button.setBackgroundColor(Color.parseColor("#757575"))
                button.setTextColor(Color.WHITE)
            }
        }
        
        // Update WebView container borders
        webViewContainers.forEachIndexed { index, container ->
            if (index == activeTabIndex) {
                container.background = resources.getDrawable(R.drawable.tab_border_active, theme)
            } else {
                container.background = resources.getDrawable(R.drawable.tab_border, theme)
            }
        }
    }
    
    private fun getActiveWebView(): WebView? {
        return if (webViews.isNotEmpty() && activeTabIndex >= 0 && activeTabIndex < webViews.size) {
            webViews[activeTabIndex]
        } else {
            null
        }
    }
    
    private fun updateNavigationButtons() {
        getActiveWebView()?.let { activeWebView ->
            backButton.isEnabled = activeWebView.canGoBack()
            forwardButton.isEnabled = activeWebView.canGoForward()
        } ?: run {
            backButton.isEnabled = false
            forwardButton.isEnabled = false
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
    
    override fun onResume() {
        super.onResume()
        // Resume location updates if permission is granted
        if (locationPermissionGranted) {
            startLocationUpdates()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Stop location updates when paused to save battery
        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
        }
    }
}
