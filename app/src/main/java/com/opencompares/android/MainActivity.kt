package com.opencompares.android

import android.Manifest
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
// removed unused import horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
// removed unused import Search icon
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.graphics.PointF
import kotlin.math.abs
import android.content.pm.PackageManager
import androidx.compose.runtime.collectAsState
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var scaleDetector: ScaleGestureDetector
    private val globalScaleState = mutableStateOf(1f)
    private val globalTranslateXState = mutableStateOf(0f)
    private val globalTranslateYState = mutableStateOf(0f)
    private var lastPanCentroid: PointF? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (globalScaleState.value * detector.scaleFactor).coerceIn(0.5f, 3.0f)
                if (next != globalScaleState.value) globalScaleState.value = next
                return true
            }
        })
        setContent { App(globalScaleState, globalTranslateXState, globalTranslateYState) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) {
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    lastPanCentroid = computeCentroid(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    val current = computeCentroid(ev)
                    val last = lastPanCentroid ?: current
                    val dx = current.x - last.x
                    val dy = current.y - last.y
                    if (dx != 0f || dy != 0f) {
                        globalTranslateXState.value += dx
                        globalTranslateYState.value += dy
                    }
                    lastPanCentroid = current
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    lastPanCentroid = null
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun computeCentroid(ev: MotionEvent): PointF {
        var sumX = 0f
        var sumY = 0f
        val count = ev.pointerCount
        for (i in 0 until count) {
            sumX += ev.getX(i)
            sumY += ev.getY(i)
        }
        return PointF(sumX / count, sumY / count)
    }
}

@Composable
fun App(
    globalScaleState: MutableState<Float>,
    globalTranslateXState: MutableState<Float>,
    globalTranslateYState: MutableState<Float>
) {
    MaterialTheme {
        val globalScale by globalScaleState
        val translateX by globalTranslateXState
        val translateY by globalTranslateYState

        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = globalScale
                        scaleY = globalScale
                        translationX = translateX
                        translationY = translateY
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
            ) {
                MVPCompareScreen()
            }

            // Floating reset button: visible only when scale/pan deviates from defaults
            val hasTransform = (abs(globalScale - 1f) > 0.01f) || (abs(translateX) > 1f) || (abs(translateY) > 1f)
            if (hasTransform) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            globalScaleState.value = 1f
                            globalTranslateXState.value = 0f
                            globalTranslateYState.value = 0f
                        }
                    ) {
                        Text("Reset view")
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val containerColor = MaterialTheme.colorScheme.surface
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSearch()
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        ),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(32.dp)
            .background(containerColor, shape)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 8.dp),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                innerTextField()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MVPCompareScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Preferences for persisting selection across sessions
    val prefs = remember { context.getSharedPreferences("oc_prefs", Context.MODE_PRIVATE) }

    val baseProviders = remember { MVP_PROVIDERS } // Blinkit, Zepto, Instamart
    // Mutable list of user-added custom providers (persisted)
    val customProviders = remember { mutableStateListOf<Provider>() }
    // Load custom providers once
    LaunchedEffect(Unit) {
        val saved = prefs.getStringSet("customProviders", emptySet()) ?: emptySet()
        val loaded = saved.mapNotNull { raw ->
            // Format: key||displayName||urlTemplate||homeUrl||defaultZoom
            val parts = raw.split("||")
            try {
                val key = parts.getOrNull(0) ?: return@mapNotNull null
                val name = parts.getOrNull(1) ?: return@mapNotNull null
                val url = parts.getOrNull(2) ?: return@mapNotNull null
                val home = parts.getOrNull(3)
                val zoom = parts.getOrNull(4)?.toIntOrNull() ?: 50
                Provider(key = key, displayName = name, urlTemplate = url, homeUrl = home, defaultZoom = zoom)
            } catch (_: Exception) { null }
        }
        customProviders.clear()
        customProviders.addAll(loaded)
    }
    // Combined providers list (base + custom)
    val providers = remember(customProviders.size) { baseProviders + customProviders.toList() }

    // 1) What the user is typing
    var queryField by remember { mutableStateOf(TextFieldValue("")) }
    // 2) We apply this query to all panes only when Search is clicked
    var appliedQuery by remember { mutableStateOf("") }

    // Zoom 5–100%, default 50%
    var zoom by remember { mutableStateOf(50) }

    // Which pane (by provider.key) is fullscreen; null means grid view
    var fullScreenPane by remember { mutableStateOf<String?>(null) }

    // Marketplaces selection dialog and state
    var showMarketplaceDialog by remember { mutableStateOf(false) }
    var selectedProviders by remember {
        val saved = prefs.getStringSet("selectedProviders", null)
        mutableStateOf(saved?.toMutableSet() ?: mutableSetOf("blinkit", "zepto", "instamart"))
    }
    var tempMarketplaceSelection by remember { mutableStateOf(mutableSetOf<String>()) }
    
    // Grid size state (1 or 2 columns)
    var gridColumns by remember {
        val saved = prefs.getInt("gridColumns", 2)
        mutableStateOf(saved)
    }
    var tempGridColumns by remember { mutableStateOf(2) }
    
    // Grid height state (in dp)
    var gridHeight by remember {
        val saved = prefs.getInt("gridHeight", 500)
        mutableStateOf(saved)
    }
    var tempGridHeight by remember { mutableStateOf(500) }
    LaunchedEffect(showMarketplaceDialog) {
        if (showMarketplaceDialog) {
            tempMarketplaceSelection = selectedProviders.toMutableSet()
            tempGridColumns = gridColumns
            tempGridHeight = gridHeight
        }
    }

    // Location permission & site prompt
    var pendingGeoResponder by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var siteRequestingGeo by remember { mutableStateOf<String?>(null) }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            pendingGeoResponder?.invoke(false)
            pendingGeoResponder = null
            siteRequestingGeo = null
        } else {
            // Start app-level location once permission is granted
            AppLocation.initialize(context)
        }
    }

	// Prompt for location permission on app start if not granted yet (once per session)
	var requestedPermissionOnStart by remember { mutableStateOf(false) }
	LaunchedEffect(Unit) {
		val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
		if (!granted && !requestedPermissionOnStart) {
			requestedPermissionOnStart = true
			locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
		}
	}

    // If permission already granted, initialize location provider immediately
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            AppLocation.initialize(context)
        }
    }

    val lastLocation = AppLocation.lastLocation.collectAsState(initial = null)
    var addressText by remember { mutableStateOf<String?>(null) }

    // Reverse geocode when location changes
    // Capture a non-composable context for use inside effects
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(lastLocation.value) {
        val loc = lastLocation.value
        if (loc == null) {
            addressText = null
        } else {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Async callback API
                    withContext(Dispatchers.Main) {
                        geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { results ->
                            val a = results?.firstOrNull()
                            addressText = if (a != null) {
                                listOfNotNull(a.subLocality, a.locality, a.adminArea, a.postalCode).joinToString(", ")
                            } else null
                        }
                    }
                } else {
                    val results = withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    }
                    val a = results?.firstOrNull()
                    addressText = if (a != null) {
                        listOfNotNull(a.subLocality, a.locality, a.adminArea, a.postalCode).joinToString(", ")
                    } else null
                }
            } catch (_: Exception) {
                addressText = null
            }
        }
    }

    // Per-pane state maps (url, desktop mode, reload token, zoom)
    val paneUrls = remember { mutableStateMapOf<String, String>() }
    val paneDesktop = remember { mutableStateMapOf<String, Boolean>() }
    val paneReload = remember { mutableStateMapOf<String, Int>() }
    val paneZoom = remember { mutableStateMapOf<String, Int>() }

    // Initialize per-pane URL on first composition
    LaunchedEffect(Unit) {
        providers.forEach { p ->
            paneUrls.putIfAbsent(p.key, p.home())
            paneDesktop.putIfAbsent(p.key, false)
            paneReload.putIfAbsent(p.key, 0)
            paneZoom.putIfAbsent(p.key, p.defaultZoom)
        }
    }
    // Ensure new custom providers added later get initialized
    LaunchedEffect(customProviders.size) {
        providers.forEach { p ->
            paneUrls.putIfAbsent(p.key, p.home())
            paneDesktop.putIfAbsent(p.key, false)
            paneReload.putIfAbsent(p.key, 0)
            paneZoom.putIfAbsent(p.key, p.defaultZoom)
        }
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(50.dp),
                title = {
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_logo),
                                contentDescription = "App logo",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Open Compares",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                            IconButton(onClick = {
                                val newZoom = (zoom - 5).coerceIn(5, 100)
                                zoom = newZoom
                                if (fullScreenPane == null) {
                                    selectedProviders.forEach { key -> paneZoom[key] = newZoom }
                                } else {
                                    val key = fullScreenPane
                                    if (key != null) paneZoom[key] = newZoom
                                }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.ZoomOut,
                                    contentDescription = "Zoom out",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Box(modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            LinearProgressIndicator(
                                progress = { zoom / 100f },
                                modifier = Modifier.width(64.dp).height(4.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                            )
                        }
                        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                            IconButton(onClick = {
                                val newZoom = (zoom + 5).coerceIn(5, 100)
                                zoom = newZoom
                                if (fullScreenPane == null) {
                                    selectedProviders.forEach { key -> paneZoom[key] = newZoom }
                                } else {
                                    val key = fullScreenPane
                                    if (key != null) paneZoom[key] = newZoom
                                }
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.ZoomIn,
                                    contentDescription = "Zoom in",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) 
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // Combined search and zoom control in single row
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Search field (takes most space)
                    CompactSearchField(
                        value = queryField,
                        onValueChange = { queryField = it },
                        placeholder = "Search...",
                        onSearch = {
                            // Execute search when keyboard Search/Enter pressed
                            appliedQuery = queryField.text
                            providers.forEach { p ->
                                if (selectedProviders.contains(p.key)) {
                                    paneUrls[p.key] = p.buildUrl(appliedQuery)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    
                    Spacer(Modifier.width(8.dp))

                    // Search button
                    Button(
                        onClick = {
                            appliedQuery = queryField.text
                            providers.forEach { p ->
                                if (selectedProviders.contains(p.key)) {
                                    paneUrls[p.key] = p.buildUrl(appliedQuery)
                                }
                            }
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                        modifier = Modifier.height(32.dp)
                    ) { 
                        Text(
                            "Search", 
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium
                            )
                        ) 
                    }
                    
                    Spacer(Modifier.width(12.dp))
                    // Zoom controls moved to TopAppBar
                    
                    // Marketplaces button
                    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                        Button(
                            onClick = { showMarketplaceDialog = true },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Store,
                                contentDescription = "Marketplaces",
                                modifier = Modifier.size(16.dp)
                            )
                            
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    
                }
            }

            // Ultra-thin divider
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            if (fullScreenPane == null) {
                // Ultra-compact two-column grid
                val visibleProviders = providers.filter { selectedProviders.contains(it.key) }
                if (visibleProviders.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No marketplaces selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visibleProviders, key = { it.key }) { provider ->
                        val currentUrl = paneUrls[provider.key] ?: provider.home()
                        val desktopMode = paneDesktop[provider.key] ?: false
                        val reloadToken = paneReload[provider.key] ?: 0
                        val providerZoom = paneZoom[provider.key] ?: provider.defaultZoom

                        var menuOpen by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier.fillMaxWidth().height(gridHeight.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                // Ultra-compact header with provider name and menu
                                // Per-pane back navigation state shared between header and WebView
                                var backToken by remember(provider.key) { mutableStateOf(0) }
                                var canGoBack by remember(provider.key) { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        provider.displayName, 
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                                            IconButton(onClick = { backToken += 1 }, enabled = canGoBack, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    imageVector = Icons.Filled.ArrowBack,
                                                    contentDescription = "Go back",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        IconButton(onClick = { fullScreenPane = provider.key }, modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                Icons.Filled.Fullscreen,
                                                contentDescription = "Enter fullscreen",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Box {
                                            IconButton(
                                                onClick = { menuOpen = true },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.MoreVert, 
                                                    contentDescription = "More options",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = menuOpen,
                                                onDismissRequest = { menuOpen = false }
                                            ) {
                                                // Open current URL externally
                                                DropdownMenuItem(
                                                    text = { Text("Open") },
                                                    onClick = {
                                                        menuOpen = false
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                                        context.startActivity(intent)
                                                    }
                                                )
                                                // Home: set this pane's URL to site home
                                                DropdownMenuItem(
                                                    text = { Text("Home") },
                                                    onClick = {
                                                        menuOpen = false
                                                        paneUrls[provider.key] = provider.home()
                                                    }
                                                )
                                                // Desktop mode toggle (checkbox)
                                                DropdownMenuItem(
                                                    text = { Text("Desktop mode") },
                                                    trailingIcon = {
                                                        Checkbox(
                                                            checked = desktopMode,
                                                            onCheckedChange = { checked ->
                                                                paneDesktop[provider.key] = checked
                                                                // Force reload of the same URL with new UA
                                                                paneReload[provider.key] = (paneReload[provider.key] ?: 0) + 1
                                                            }
                                                        )
                                                    },
                                                    onClick = {
                                                        paneDesktop[provider.key] = !desktopMode
                                                        paneReload[provider.key] = (paneReload[provider.key] ?: 0) + 1
                                                        menuOpen = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Ultra-compact WebView container
                                Box(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
                                key(provider.key) {
                                OpenComparesWebView(
                                    provider = provider,
                                    targetUrl = currentUrl,           // <- explicit URL (search or home)
                                    zoomPercent = providerZoom,
                                    desktopMode = desktopMode,
                                    reloadToken = reloadToken,
                                    backRequestToken = backToken,
                                    onCanGoBackChanged = { canGoBack = it },
                                        onUrlChanged = { newUrl ->
                                            // Keep the tracked URL in sync with actual navigations
                                            paneUrls[provider.key] = newUrl
                                        },
                                        onError = {
                                            // TODO: show Snackbar/Toast if desired
                                        },
                                        onGeoPrompt = { origin, respond ->
                                            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                            pendingGeoResponder = respond
                                            siteRequestingGeo = origin
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                }
                            }
                        }
                    }
                    // Footer at end of grid
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val locationText = addressText ?: "Location: unavailable"
                        Spacer(Modifier.height(4.dp))
                        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "💡 You can Login to each website to get your personalized prices and add to cart directly from the here",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (addressText == null) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            Button(
                                                onClick = { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(24.dp)
                                            ) {
                                                Text(
                                                    "Get Location Access",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = locationText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_logo),
                                            contentDescription = "App logo",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Open Compares",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
            } else {
                // Fullscreen single pane view
                val provider = providers.firstOrNull { it.key == fullScreenPane && selectedProviders.contains(it.key) }
                if (provider != null) {
                    val currentUrl = paneUrls[provider.key] ?: provider.home()
                    val desktopMode = paneDesktop[provider.key] ?: false
                    val reloadToken = paneReload[provider.key] ?: 0
                    val providerZoom = paneZoom[provider.key] ?: provider.defaultZoom

                    var menuOpen by remember { mutableStateOf(false) }
                    // Back state for fullscreen pane
                    var backToken by remember(provider.key) { mutableStateOf(0) }
                    var canGoBack by remember(provider.key) { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            // Header with exit fullscreen
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    provider.displayName,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                                        IconButton(onClick = { backToken += 1 }, enabled = canGoBack, modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                imageVector = Icons.Filled.ArrowBack,
                                                contentDescription = "Go back",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    IconButton(onClick = { fullScreenPane = null }, modifier = Modifier.size(28.dp)) {
        						Icon(
                                            Icons.Filled.FullscreenExit,
                                            contentDescription = "Exit fullscreen",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Box {
                                        IconButton(
                                            onClick = { menuOpen = true },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.MoreVert,
                                                contentDescription = "More options",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuOpen,
                                            onDismissRequest = { menuOpen = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Open") },
                                                onClick = {
                                                    menuOpen = false
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                                    context.startActivity(intent)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Home") },
                                                onClick = {
                                                    menuOpen = false
                                                    paneUrls[provider.key] = provider.home()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Desktop mode") },
                                                trailingIcon = {
                                                    Checkbox(
                                                        checked = desktopMode,
                                                        onCheckedChange = { checked ->
                                                            paneDesktop[provider.key] = checked
                                                            paneReload[provider.key] = (paneReload[provider.key] ?: 0) + 1
                                                        }
                                                    )
                                                },
                                                onClick = {
                                                    paneDesktop[provider.key] = !desktopMode
                                                    paneReload[provider.key] = (paneReload[provider.key] ?: 0) + 1
                                                    menuOpen = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Fullscreen WebView fills all
                            Box(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
                                key(provider.key) {
                                OpenComparesWebView(
                                    provider = provider,
                                    targetUrl = currentUrl,
                                    zoomPercent = providerZoom,
                                    desktopMode = desktopMode,
                                    reloadToken = reloadToken,
                                    backRequestToken = backToken,
                                    onCanGoBackChanged = { canGoBack = it },
                                    onUrlChanged = { newUrl ->
                                        paneUrls[provider.key] = newUrl
                                    },
                                    onError = { },
                                    onGeoPrompt = { origin, respond ->
                                        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                        pendingGeoResponder = respond
                                        siteRequestingGeo = origin
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                }
                            }
                        }
                    }
                    // Footer below fullscreen content; make parent scrollable if needed
                    Spacer(Modifier.height(8.dp))
                    val locationText = addressText ?: "Location: unavailable"
                    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "💡 You can Login to each website to get your personalized prices and add to cart directly from the here",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (addressText == null) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        Button(
                                            onClick = { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text(
                                                "Get Location Access",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = locationText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_logo),
                                        contentDescription = "App logo",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Open Compares",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Marketplaces selection dialog
            if (showMarketplaceDialog) {
                AlertDialog(
                    onDismissRequest = { showMarketplaceDialog = false },
                    title = { Text("Marketplaces") },
                    text = {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Grid size selector
                            Text(
                                text = "Grid Layout",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Columns")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("1")
                                    RadioButton(
                                        selected = tempGridColumns == 1,
                                        onClick = { tempGridColumns = 1 }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("2")
                                    RadioButton(
                                        selected = tempGridColumns == 2,
                                        onClick = { tempGridColumns = 2 }
                                    )
                                }
                            }
                            
                            Spacer(Modifier.height(16.dp))
                            
                            // Marketplace selection
                            Text(
                                text = "Marketplaces",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            providers.forEach { p ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Text(p.displayName, modifier = Modifier.weight(1f))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Remove button for custom providers
                                        val isCustom = customProviders.any { it.key == p.key }
                                        if (isCustom) {
                                            IconButton(onClick = {
                                                // Remove from custom list and selections; persist
                                                val idx = customProviders.indexOfFirst { it.key == p.key }
                                                if (idx >= 0) customProviders.removeAt(idx)
                                                val nextSel = tempMarketplaceSelection.toMutableSet()
                                                nextSel.remove(p.key)
                                                tempMarketplaceSelection = nextSel
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "Remove provider",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Checkbox(
                                            checked = tempMarketplaceSelection.contains(p.key),
                                            onCheckedChange = { checked ->
                                                val next = tempMarketplaceSelection.toMutableSet()
                                                if (checked) next.add(p.key) else next.remove(p.key)
                                                tempMarketplaceSelection = next
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            // Add custom provider button
                            var showAddDialog by remember { mutableStateOf(false) }
                            Button(onClick = { showAddDialog = true }, modifier = Modifier.align(Alignment.End)) { Text("Add Provider") }

                            if (showAddDialog) {
                                var newName by remember { mutableStateOf("") }
                                var newUrl by remember { mutableStateOf("") }
                                var errorText by remember { mutableStateOf<String?>(null) }
                                AlertDialog(
                                    onDismissRequest = { showAddDialog = false },
                                    title = { Text("Add Custom Provider") },
                                    text = {
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                        ) {
                                            OutlinedTextField(
                                                value = newName,
                                                onValueChange = { newName = it },
                                                label = { Text("Provider Name") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = newUrl,
                                                onValueChange = { newUrl = it },
                                                label = { Text("URL Template") },
                                                singleLine = true,
                                                placeholder = { Text("https://example.com/search?q={query}") },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text("Use {query} as the placeholder for the search term", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (errorText != null) {
                                                Spacer(Modifier.height(6.dp))
                                                Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val name = newName.trim()
                                            val url = newUrl.trim()
                                            val valid = name.isNotEmpty() && url.isNotEmpty() && (url.contains("{query}") || url.contains("{QUERY}"))
                                            if (!valid) {
                                                errorText = "Enter a name and a URL containing {query}"
                                                return@TextButton
                                            }
                                            val safe = name.lowercase(Locale.getDefault()).replace("[^a-z0-9]+".toRegex(), "_")
                                            val key = "custom_${'$'}safe_${'$'}{System.currentTimeMillis()}"
                                            val p = Provider(key = key, displayName = name, urlTemplate = url, homeUrl = null, defaultZoom = 50)
                                            customProviders.add(p)
                                            // Select by default in temp selection
                                            val nextSel = tempMarketplaceSelection.toMutableSet(); nextSel.add(key); tempMarketplaceSelection = nextSel
                                            showAddDialog = false
                                        }) { Text("Add Provider") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                                    }
                                )
                            }
                            
                            Spacer(Modifier.height(16.dp))
                            
                            // Grid height controls
                            Text(
                                text = "Grid Height",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Height (dp)")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { tempGridHeight = (tempGridHeight - 50).coerceIn(300, 800) }
                                    ) {
                                        Text("-")
                                    }
                                    Text(
                                        text = tempGridHeight.toString(),
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(
                                        onClick = { tempGridHeight = (tempGridHeight + 50).coerceIn(300, 800) }
                                    ) {
                                        Text("+")
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            selectedProviders = tempMarketplaceSelection.toMutableSet()
                            gridColumns = tempGridColumns
                            gridHeight = tempGridHeight
                            if (fullScreenPane != null && !selectedProviders.contains(fullScreenPane!!)) {
                                fullScreenPane = null
                            }
                            // Persist selection
                            // Persist custom providers too
                            val serialized = customProviders.map { cp ->
                                listOf(cp.key, cp.displayName, cp.urlTemplate, cp.homeUrl ?: "", cp.defaultZoom.toString()).joinToString("||")
                            }.toSet()
                            prefs.edit()
                                .putStringSet("selectedProviders", selectedProviders)
                                .putInt("gridColumns", gridColumns)
                                .putInt("gridHeight", gridHeight)
                                .putStringSet("customProviders", serialized)
                                .apply()
                            showMarketplaceDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showMarketplaceDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // Site-level geolocation dialog
            if (siteRequestingGeo != null) {
                AlertDialog(
                    onDismissRequest = {
                        pendingGeoResponder?.invoke(false)
                        pendingGeoResponder = null
                        siteRequestingGeo = null
                    },
                    title = { Text("Share location?") },
                    text = { Text("Allow ${siteRequestingGeo} to access your location?") },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingGeoResponder?.invoke(true)
                            pendingGeoResponder = null
                            siteRequestingGeo = null
                        }) { Text("Allow") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            pendingGeoResponder?.invoke(false)
                            pendingGeoResponder = null
                            siteRequestingGeo = null
                        }) { Text("Deny") }
                    }
                )
            }
        }
    }
}
