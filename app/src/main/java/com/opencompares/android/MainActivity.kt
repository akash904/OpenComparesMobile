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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
    var queryField by remember { mutableStateOf(TextFieldValue("tea")) }
    // 2) We apply this query to all panes only when Search is clicked
    var appliedQuery by remember { mutableStateOf("tea") }

    // Zoom 5–200%, default 50%
    var zoom by remember { mutableStateOf(50) }

    // Which pane (by provider.key) is fullscreen; null means grid view
    var fullScreenPane by remember { mutableStateOf<String?>(null) }

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
        }
    }

    // Per-pane state maps (url, desktop mode, reload token)
    val paneUrls = remember { mutableStateMapOf<String, String>() }
    val paneDesktop = remember { mutableStateMapOf<String, Boolean>() }
    val paneReload = remember { mutableStateMapOf<String, Int>() }

    // Initialize per-pane URL on first composition
    LaunchedEffect(Unit) {
        providers.forEach { p ->
            paneUrls.putIfAbsent(p.key, p.buildUrl(appliedQuery))
            paneDesktop.putIfAbsent(p.key, false)
            paneReload.putIfAbsent(p.key, 0)
        }
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { 
                    Text(
                        "Open Compares", 
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    ) 
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
                                paneUrls[p.key] = p.buildUrl(appliedQuery)
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
                    
                    // Zoom label
                    Text(
                        "Zoom: ${zoom}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(Modifier.width(4.dp))
                    
                    // Zoom slider (compact)
                    Slider(
                        value = zoom.toFloat(),
                        onValueChange = { zoom = it.toInt().coerceIn(5, 200) },
                        valueRange = 5f..200f,
                        modifier = Modifier.width(80.dp)
                    )
                }
            }

            // Ultra-thin divider
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            if (fullScreenPane == null) {
                // Ultra-compact two-column grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(providers, key = { it.key }) { provider ->
                        val currentUrl = paneUrls[provider.key] ?: provider.buildUrl(appliedQuery)
                        val desktopMode = paneDesktop[provider.key] ?: false
                        val reloadToken = paneReload[provider.key] ?: 0

                        var menuOpen by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier.fillMaxWidth().height(400.dp),
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
                                                    text = {
                                                        Row(
                                                            Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text("Desktop mode")
                                                            Checkbox(
                                                                checked = desktopMode,
                                                                onCheckedChange = { checked ->
                                                                    paneDesktop[provider.key] = checked
                                                                    // Force reload of the same URL with new UA
                                                                    paneReload[provider.key] = (paneReload[provider.key] ?: 0) + 1
                                                                }
                                                            )
                                                        }
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
                                        zoomPercent = zoom,
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
                }
            } else {
                // Fullscreen single pane view
                val provider = providers.firstOrNull { it.key == fullScreenPane }
                if (provider != null) {
                    val currentUrl = paneUrls[provider.key] ?: provider.buildUrl(appliedQuery)
                    val desktopMode = paneDesktop[provider.key] ?: false
                    val reloadToken = paneReload[provider.key] ?: 0

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
                                                text = {
                                                    Row(
                                                        Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text("Desktop mode")
                                                        Checkbox(
                                                            checked = desktopMode,
                                                            onCheckedChange = { checked ->
                                                                paneDesktop[provider.key] = checked
                                                                paneReload[provider.key] = (paneReload[provider.key] ?: 0) + 1
                                                            }
                                                        )
                                                    }
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
                                    zoomPercent = zoom,
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
                }
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
