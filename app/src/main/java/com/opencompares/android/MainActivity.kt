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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

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
        topBar = { TopAppBar(title = { Text("Open Compares — MVP") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // Shared search bar (typing does NOT trigger search)
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                OutlinedTextField(
                    value = queryField,
                    onValueChange = { queryField = it },
                    label = { Text("Search") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    // Apply the query to ALL panes at once
                    appliedQuery = queryField.text
                    providers.forEach { p ->
                        paneUrls[p.key] = p.buildUrl(appliedQuery)
                    }
                }) { Text("Search") }
            }

            // Global zoom: 5–200%
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Zoom: ${zoom}%")
                Slider(
                    value = zoom.toFloat(),
                    onValueChange = { zoom = it.toInt().coerceIn(5, 200) },
                    valueRange = 5f..200f
                )
            }

            // Two-column grid; each pane has its own menu + WebView
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(providers, key = { it.key }) { provider ->
                    val currentUrl = paneUrls[provider.key] ?: provider.buildUrl(appliedQuery)
                    val desktopMode = paneDesktop[provider.key] ?: false
                    val reloadToken = paneReload[provider.key] ?: 0

                    var menuOpen by remember { mutableStateOf(false) }

                    Card(Modifier.fillMaxWidth().height(650.dp)) {
                        Column(Modifier.fillMaxSize()) {
                            // Header: provider + overflow (Open, Home, Desktop mode)
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(provider.displayName, style = MaterialTheme.typography.titleMedium)

                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
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

                            // Body: WebView bound to the pane's currentUrl
                            Box(Modifier.fillMaxSize().padding(8.dp)) {
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
