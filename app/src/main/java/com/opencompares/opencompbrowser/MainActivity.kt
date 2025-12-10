package com.opencompares.opencompbrowser

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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
    
    private var activeTabIndex = 0
    private val tabZoomLevels = mutableListOf<Int>()
    private var defaultTabHeightDp = 300
    private val webViewContainers = mutableListOf<FrameLayout>()
    
    private lateinit var searchViewModel: SearchViewModel
    
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
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        refreshButton = findViewById(R.id.refreshButton)
        zoomSlider = findViewById(R.id.zoomSlider)
        zoomValue = findViewById(R.id.zoomValue)
        heightSlider = findViewById(R.id.heightSlider)
        heightValue = findViewById(R.id.heightValue)
        
        // Set up button listeners
        setupButtons()
        
        // Set up zoom slider
        setupZoomSlider()
        
        // Set up height slider
        setupHeightSlider()
        
        // Initialize tabs from marketplaces
        initializeMarketplaceTabs()
        
        // Observe search query changes
        observeSearchQuery()
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
        
        // Create WebView container
        val webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpToPx(defaultTabHeightDp),
                1f
            )
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
        tabZoomLevels.add(100)
        
        // Configure WebView
        setupWebView(webView)
        
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
        }
        
        webView.isNestedScrollingEnabled = false
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = true
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        
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
            zoomSlider.progress = tabZoomLevels[activeTabIndex] - 50
        }
        
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && webViews.isNotEmpty()) {
                    val zoomPercent = progress + 50
                    if (activeTabIndex < tabZoomLevels.size) {
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
            zoomSlider.progress = currentZoom - 50
            zoomValue.text = "$currentZoom%"
            applyZoomToActiveTab(currentZoom)
        }
    }
    
    private fun updateActiveTabUI() {
        tabButtons.forEachIndexed { index, button ->
            if (index == activeTabIndex) {
                button.setBackgroundColor(Color.parseColor("#2196F3"))
                button.setTextColor(Color.WHITE)
            } else {
                button.setBackgroundColor(Color.parseColor("#757575"))
                button.setTextColor(Color.WHITE)
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
}
