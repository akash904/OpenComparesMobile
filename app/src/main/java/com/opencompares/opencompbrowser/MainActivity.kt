package com.opencompares.opencompbrowser

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

class MainActivity : AppCompatActivity() {
    
    private val webViews = mutableListOf<WebView>()
    private val tabButtons = mutableListOf<Button>()
    private lateinit var addressBar: EditText
    private lateinit var goButton: ImageButton
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
    
    private var activeTabIndex = 0
    private val tabZoomLevels = mutableListOf<Int>() // Store zoom level for each tab (100 = 100%)
    private var defaultTabHeightDp = 300 // Default height for each tab row in dp (mutable)
    private val webViewContainers = mutableListOf<FrameLayout>() // Store containers to resize them
    private val defaultUrls = listOf(
        "https://www.google.com",
        "https://www.github.com",
        "https://www.stackoverflow.com",
        "https://www.wikipedia.org"
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize container views
        tabButtonsContainer = findViewById(R.id.tabButtonsContainer)
        webViewsContainer = findViewById(R.id.webViewsContainer)
        addTabButton = findViewById(R.id.addTabButton)
        
        // Initialize other views
        addressBar = findViewById(R.id.addressBar)
        goButton = findViewById(R.id.goButton)
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
        
        // Add initial 4 tabs
        for (i in 0 until 4) {
            addTab(defaultUrls.getOrNull(i) ?: "about:blank")
        }
        
        // Set first tab as active
        if (webViews.isNotEmpty()) {
            setActiveTab(0)
        }
    }
    
    private fun addTab(url: String = "about:blank") {
        val tabIndex = webViews.size
        
        // Create tab button
        val tabButton = Button(this).apply {
            text = "Tab ${tabIndex + 1}"
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
        
        // Create WebView wrapped in a FrameLayout for better scroll handling
        val webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpToPx(defaultTabHeightDp),
                1f
            )
            // Don't intercept touch events - let them pass through to WebView
            isClickable = false
            isFocusable = false
        }
        
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Disable nested scrolling to prevent conflicts with grid scrolling
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // Enable vertical scrolling with visible scrollbar
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            // Ensure WebView can receive touch events
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        
        webViewContainer.addView(webView)
        webViewContainers.add(webViewContainer) // Store container for resizing
        
        // Add WebView to appropriate row
        val rowIndex = tabIndex / 2
        val isFirstInRow = tabIndex % 2 == 0
        
        if (isFirstInRow) {
            // Create new row
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
            // Add to existing row
            val row = webViewsContainer.getChildAt(rowIndex) as LinearLayout
            row.addView(webViewContainer)
        }
        
        webViews.add(webView)
        tabZoomLevels.add(100) // Default zoom level
        
        // Configure WebView
        setupWebView(webView, tabIndex)
        
        // Load URL
        if (url != "about:blank") {
            webView.loadUrl(url)
        }
    }
    
    private fun setupWebView(webView: WebView, tabIndex: Int) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = false // Don't fit to screen, allow scrolling
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        
        // Ensure WebView can scroll its content independently
        webView.isNestedScrollingEnabled = false
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = true
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let {
                    // Reapply zoom for this tab
                    view?.let { wv ->
                        val index = webViews.indexOf(wv)
                        if (index >= 0 && index < tabZoomLevels.size) {
                            applyZoomToWebView(wv, tabZoomLevels[index])
                        }
                        
                        // Update address bar only if this is the active tab
                        getActiveWebView()?.let { activeWebView ->
                            if (wv == activeWebView) {
                                addressBar.setText(it)
                                updateNavigationButtons()
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun setupButtons() {
        goButton.setOnClickListener {
            loadUrlFromAddressBar()
        }
        
        addressBar.setOnEditorActionListener { _, _, _ ->
            loadUrlFromAddressBar()
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
            addTab()
        }
    }
    
    private fun setupZoomSlider() {
        if (tabZoomLevels.isNotEmpty()) {
            zoomSlider.progress = tabZoomLevels[activeTabIndex] - 50
        }
        
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && webViews.isNotEmpty()) {
                    val zoomPercent = progress + 50 // Convert 0-150 to 50-200
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
        // Set initial height slider value (range is 150-600dp, default 300dp)
        // SeekBar max is 450, so we offset by 150
        heightSlider.progress = defaultTabHeightDp - 150
        heightValue.text = "${defaultTabHeightDp}dp"
        
        heightSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Convert progress (0-450) to height (150-600dp)
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
        // Use JavaScript to apply CSS zoom to zoom everything (images, layout, text)
        val zoomFactor = zoomPercent / 100.0
        val javascript = """
            (function() {
                // Remove existing zoom style if any
                var existingStyle = document.getElementById('webview-zoom-style');
                if (existingStyle) {
                    existingStyle.remove();
                }
                
                // Create and inject zoom style
                var style = document.createElement('style');
                style.id = 'webview-zoom-style';
                style.innerHTML = 'html, body { zoom: $zoomFactor !important; }';
                if (document.head) {
                    document.head.appendChild(style);
                } else {
                    document.documentElement.appendChild(style);
                }
                
                // Also set zoom directly on document element for better compatibility
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
        
        // Update address bar with current URL
        getActiveWebView()?.let { activeWebView ->
            addressBar.setText(activeWebView.url ?: "")
            updateNavigationButtons()
        }
        
        // Update zoom slider to reflect active tab's zoom level
        updateZoomSlider()
    }
    
    private fun updateZoomSlider() {
        if (webViews.isNotEmpty() && activeTabIndex < tabZoomLevels.size) {
            val currentZoom = tabZoomLevels[activeTabIndex]
            zoomSlider.progress = currentZoom - 50 // Convert 50-200 range to 0-150
            zoomValue.text = "$currentZoom%"
            applyZoomToActiveTab(currentZoom)
        }
    }
    
    private fun updateActiveTabUI() {
        tabButtons.forEachIndexed { index, button ->
            if (index == activeTabIndex) {
                button.setBackgroundColor(Color.parseColor("#2196F3")) // Blue for active
                button.setTextColor(Color.WHITE)
            } else {
                button.setBackgroundColor(Color.parseColor("#757575")) // Gray for inactive
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
    
    private fun loadUrlFromAddressBar() {
        var url = addressBar.text.toString().trim()
        
        if (url.isEmpty()) {
            return
        }
        
        // Add protocol if missing
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        
        loadUrl(url)
    }
    
    private fun loadUrl(url: String) {
        getActiveWebView()?.let { webView ->
            webView.loadUrl(url)
            addressBar.setText(url)
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
    
    override fun onBackPressed() {
        getActiveWebView()?.let { activeWebView ->
            if (activeWebView.canGoBack()) {
                activeWebView.goBack()
            } else {
                super.onBackPressed()
            }
        } ?: super.onBackPressed()
    }
}
