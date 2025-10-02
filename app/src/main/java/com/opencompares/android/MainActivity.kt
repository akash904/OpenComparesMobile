package com.opencompares.android

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.runtime.collectAsState
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    MaterialTheme {
        MVPCompareScreen()
    }
}

@Composable
private fun CompactSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val containerColor = MaterialTheme.colorScheme.surface

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
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
    val providers = remember { MVP_PROVIDERS } // Blinkit, Zepto, Instamart
    val context = LocalContext.current

    // 1) What the user is typing
    var queryField by remember { mutableStateOf(TextFieldValue("")) }
    // 2) We apply this query to all panes only when Search is clicked
    var appliedQuery by remember { mutableStateOf("") }

    // Zoom 5–100%, default 50%
    var zoom by remember { mutableStateOf(50) }

    // Which pane (by provider.key) is fullscreen; null means grid view
    var fullScreenPane by remember { mutableStateOf<String?>(null) }
    
    // Clean browser view state
    var showCleanBrowser by remember { mutableStateOf(false) }

    // Marketplaces selection dialog and state
    var showMarketplaceDialog by remember { mutableStateOf(false) }
    var selectedProviders by remember { mutableStateOf(mutableSetOf("blinkit", "zepto", "instamart")) }
    var tempMarketplaceSelection by remember { mutableStateOf(mutableSetOf<String>()) }
    LaunchedEffect(showMarketplaceDialog) {
        if (showMarketplaceDialog) {
            tempMarketplaceSelection = selectedProviders.toMutableSet()
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

                    // Open Clean Browser button
                    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                        Button(
                            onClick = {
                                // Show clean browser view within the app
                                showCleanBrowser = true
                            },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.OpenInBrowser,
                                contentDescription = "Open Clean Browser",
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

            if (showCleanBrowser) {
                // Clean browser view - minimal WebView without custom modifications
                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Header with close button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Clean Browser - Zepto",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )

                            IconButton(onClick = { showCleanBrowser = false }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Filled.FullscreenExit,
                                    contentDescription = "Close Clean Browser",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Clean WebView container
                        Box(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
                            CleanWebView(
                                url = "https://www.zeptonow.com/",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            } else if (fullScreenPane == null) {
                // Ultra-compact two-column grid
                val visibleProviders = providers.filter { selectedProviders.contains(it.key) }
                if (visibleProviders.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No marketplaces selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
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
                            modifier = Modifier.fillMaxWidth().height(500.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                // Ultra-compact header with provider name and menu
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
                                OpenComparesWebView(
                                    provider = provider,
                                    targetUrl = currentUrl,           // <- explicit URL (search or home)
                                    zoomPercent = providerZoom,
                                    desktopMode = desktopMode,
                                    reloadToken = reloadToken,
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
                                OpenComparesWebView(
                                    provider = provider,
                                    targetUrl = currentUrl,
                                    zoomPercent = providerZoom,
                                    desktopMode = desktopMode,
                                    reloadToken = reloadToken,
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
                            providers.forEach { p ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(p.displayName)
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
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            selectedProviders = tempMarketplaceSelection.toMutableSet()
                            if (fullScreenPane != null && !selectedProviders.contains(fullScreenPane!!)) {
                                fullScreenPane = null
                            }
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

@Composable
fun CleanWebView(
    url: String,
    modifier: Modifier = Modifier
) {
    var showLocationDialog by remember { mutableStateOf(false) }
    var locationCallback by remember { mutableStateOf<android.webkit.GeolocationPermissions.Callback?>(null) }
    var locationOrigin by remember { mutableStateOf<String?>(null) }
    
    AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = true
                
                // Enable proper dialog and overlay rendering
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                settings.mediaPlaybackRequiresUserGesture = false
                
                // Enable hardware acceleration for better rendering
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                }
                
                // Enable proper CSS and DOM support for modals
                settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                
                // Enable mixed content for better compatibility
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                
                // Enable cookies for session management
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
                
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, url: String?): Boolean {
                        return false
                    }
                    
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Enable proper modal dialog support and auto-simulate location flow
                        view?.evaluateJavascript("""
                            (function() {
                                // Ensure modal dialogs render properly
                                const style = document.createElement('style');
                                style.textContent = `
                                    .modal, .overlay, [role="dialog"] {
                                        z-index: 999999 !important;
                                        position: fixed !important;
                                    }
                                    .modal-backdrop, .modal-overlay {
                                        z-index: 999998 !important;
                                    }
                                `;
                                document.head.appendChild(style);
                                
                                // Auto-simulate location selection flow for testing
                                setTimeout(() => {
                                    console.log('Auto-simulating location selection...');
                                    
                                    // Find and click "Select Location" button
                                    const selectLocationBtn = document.querySelector('button[aria-label*="location"], [data-testid*="location"], button[title*="location"]') ||
                                                           Array.from(document.querySelectorAll('button')).find(btn => btn.textContent.includes('Select Location') || btn.textContent.includes('Location'));
                                    
                                    if (selectLocationBtn) {
                                        console.log('Found Select Location button, clicking...');
                                        selectLocationBtn.click();
                                        
                                        // Wait for dialog to appear, then auto-click location enable
                                        setTimeout(() => {
                                            console.log('Looking for location enable button...');
                                            
                                            // Look for various location enable button patterns
                                            const enableLocationBtn = document.querySelector('button[aria-label*="enable"], [data-testid*="enable"], [data-testid*="allow"]') ||
                                                                   Array.from(document.querySelectorAll('button')).find(btn => 
                                                                       btn.textContent.includes('Enable') || 
                                                                       btn.textContent.includes('Allow') || 
                                                                       btn.textContent.includes('Yes') ||
                                                                       btn.textContent.includes('Continue')
                                                                   );
                                            
                                            if (enableLocationBtn) {
                                                console.log('Found Enable Location button, clicking...');
                                                enableLocationBtn.click();
                                            } else {
                                                console.log('Enable Location button not found, checking for geolocation API...');
                                                // Try to trigger geolocation API directly
                                                if (navigator.geolocation) {
                                                    navigator.geolocation.getCurrentPosition(
                                                        (position) => console.log('Geolocation success:', position),
                                                        (error) => console.log('Geolocation error:', error)
                                                    );
                                                }
                                            }
                                        }, 2000);
                                    } else {
                                        console.log('Select Location button not found');
                                    }
                                }, 3000);
                            })();
                        """, null)
                    }
                }
                
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: android.webkit.GeolocationPermissions.Callback?
                    ) {
                        if (origin != null && callback != null) {
                            locationOrigin = origin
                            locationCallback = callback
                            showLocationDialog = true
                        }
                    }
                    
                    // Handle JavaScript alerts and dialogs
                    override fun onJsAlert(view: android.webkit.WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                        // Allow JavaScript alerts to show
                        result?.confirm()
                        return true
                    }
                    
                    override fun onJsConfirm(view: android.webkit.WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                        // Allow JavaScript confirms to show
                        result?.confirm()
                        return true
                    }
                    
                    override fun onJsPrompt(view: android.webkit.WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                        // Allow JavaScript prompts to show
                        result?.confirm()
                        return true
                    }
                    
                    // Handle popup windows properly
                    override fun onCreateWindow(
                        view: android.webkit.WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        // Allow popup windows to open in the same WebView
                        val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                        transport?.webView = view
                        resultMsg?.sendToTarget()
                        return true
                    }
                }
                
                loadUrl(url)
            }
        },
        modifier = modifier
    )
    
    // Location permission dialog
    if (showLocationDialog) {
        AlertDialog(
            onDismissRequest = {
                showLocationDialog = false
                locationCallback?.invoke(locationOrigin ?: "", false, false)
                locationCallback = null
                locationOrigin = null
            },
            title = { Text("Location Access") },
            text = { 
                Text("Allow ${locationOrigin ?: "this website"} to access your location?") 
            },
            confirmButton = {
                TextButton(onClick = {
                    showLocationDialog = false
                    locationCallback?.invoke(locationOrigin ?: "", true, false)
                    locationCallback = null
                    locationOrigin = null
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLocationDialog = false
                    locationCallback?.invoke(locationOrigin ?: "", false, false)
                    locationCallback = null
                    locationOrigin = null
                }) { Text("Deny") }
            }
        )
    }
}
