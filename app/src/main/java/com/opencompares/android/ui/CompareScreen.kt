package com.opencompares.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencompares.android.AppLocation
import com.opencompares.android.CompareViewModel
import com.opencompares.android.GridScrollbar
import com.opencompares.android.PaneWebViewStore
import com.opencompares.android.R
import com.opencompares.android.ScrollChainTarget
import com.opencompares.android.chatgpt.ChatGptViewModel
import com.opencompares.android.data.AppSettings
import kotlinx.coroutines.launch

/** Search once, see every selected marketplace side by side. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(viewModel: CompareViewModel = viewModel()) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val settings = viewModel.settings

    // Location: ask once on start, and start updates as soon as permission exists.
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) AppLocation.initialize(context) }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) AppLocation.initialize(context) else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val requestLocation = { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    // WebViews live here for the life of the screen, independent of grid or fullscreen.
    val store = remember { PaneWebViewStore() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, store) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> store.onResume()
                Lifecycle.Event.ON_PAUSE -> store.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            store.destroyAll()
        }
    }
    // Free the WebViews of marketplaces the user turned off.
    val selectedKeys = settings?.selectedProviders
    LaunchedEffect(selectedKeys) {
        if (selectedKeys != null) store.retainOnly(selectedKeys)
    }

    // Grid scrolling. Panes keep their own touches, so when a page is already at its
    // top or bottom they forward the rest of the drag here to move the grid instead.
    val gridScrollState = rememberScrollState()
    val gridFlingBehavior = ScrollableDefaults.flingBehavior()
    val gridScope = rememberCoroutineScope()
    val gridScrollChain = remember(gridScrollState, gridFlingBehavior, gridScope) {
        object : ScrollChainTarget {
            override fun onTouchDown() {
                if (gridScrollState.isScrollInProgress) {
                    gridScope.launch { gridScrollState.stopScroll() }
                }
            }

            // Finger moving up (negative) scrolls the grid forward.
            override fun onUnconsumedDrag(fingerDy: Float) {
                gridScrollState.dispatchRawDelta(-fingerDy)
            }

            override fun onUnconsumedFling(fingerVelocityY: Float) {
                gridScope.launch {
                    gridScrollState.scroll {
                        with(gridFlingBehavior) { performFling(-fingerVelocityY) }
                    }
                }
            }
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showChatGpt by remember { mutableStateOf(false) }
    var showPrices by remember { mutableStateOf(false) }
    val chatGptViewModel: ChatGptViewModel = viewModel()
    val runSearch: () -> Unit = {
        viewModel.search()
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(50.dp),
                title = {
                    Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_logo),
                                contentDescription = "App logo",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Open Compares",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                },
                actions = {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        IconButton(
                            onClick = {
                                showPrices = true
                                viewModel.readPrices(store)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.LocalOffer,
                                contentDescription = "Read prices",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = { showChatGpt = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = "ChatGPT",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    ZoomControls(
                        zoom = viewModel.globalZoom,
                        onZoomOut = viewModel::zoomOut,
                        onZoomIn = viewModel::zoomIn
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
            SearchBar(
                query = viewModel.query,
                onQueryChange = { viewModel.query = it },
                onSearch = runSearch,
                onOpenSettings = { showSettings = true }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            val fullScreenProvider = viewModel.fullScreenPane?.let { key ->
                viewModel.visibleProviders.firstOrNull { it.key == key }
            }
            when {
                // Saved settings are still loading; drawing nothing avoids building default panes.
                settings == null -> Unit

                fullScreenProvider != null -> MarketplacePane(
                    provider = fullScreenProvider,
                    pane = viewModel.pane(fullScreenProvider),
                    isFullscreen = true,
                    store = store,
                    viewModel = viewModel,
                    scrollChain = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                viewModel.visibleProviders.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No marketplaces selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> MarketplaceGrid(
                    viewModel = viewModel,
                    settings = settings,
                    store = store,
                    scrollState = gridScrollState,
                    scrollChain = gridScrollChain,
                    onRequestLocation = requestLocation
                )
            }
        }
    }

    if (showPrices) {
        PricesSheet(
            results = viewModel.priceResults,
            reading = viewModel.readingPrices,
            providers = viewModel.providers,
            onRefresh = { viewModel.readPrices(store) },
            onDismiss = { showPrices = false }
        )
    }

    if (showChatGpt) {
        ChatGptDialog(viewModel = chatGptViewModel, onDismiss = { showChatGpt = false })
    }

    if (showSettings && settings != null) {
        MarketplaceSettingsDialog(
            current = settings,
            onSave = { newSettings ->
                viewModel.saveSettings(newSettings)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}

/**
 * A plain (non-lazy) grid: every selected pane stays composed so its WebView keeps
 * its page, scroll position and login while off screen.
 */
@Composable
private fun MarketplaceGrid(
    viewModel: CompareViewModel,
    settings: AppSettings,
    store: PaneWebViewStore,
    scrollState: androidx.compose.foundation.ScrollState,
    scrollChain: ScrollChainTarget,
    onRequestLocation: () -> Unit
) {
    val columns = settings.gridColumns
    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(scrollState)
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            viewModel.visibleProviders.chunked(columns).forEach { rowProviders ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowProviders.forEach { provider ->
                        key(provider.key) {
                            MarketplacePane(
                                provider = provider,
                                pane = viewModel.pane(provider),
                                isFullscreen = false,
                                store = store,
                                viewModel = viewModel,
                                scrollChain = scrollChain,
                                modifier = Modifier.weight(1f).height(settings.gridHeightDp.dp)
                            )
                        }
                    }
                    // Keep a short last row's panes the same width as full rows.
                    repeat(columns - rowProviders.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            LocationFooter(addressText = viewModel.addressText, onRequestLocation = onRequestLocation)
        }
        // Always-available scroll handle; see GridScrollbar.
        GridScrollbar(scrollState)
    }
}

@Composable
private fun ZoomControls(zoom: Int, onZoomOut: () -> Unit, onZoomIn: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            IconButton(onClick = onZoomOut, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.ZoomOut,
                    contentDescription = "Zoom out",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            LinearProgressIndicator(
                progress = { zoom / 100f },
                modifier = Modifier.width(64.dp).height(4.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            IconButton(onClick = onZoomIn, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.ZoomIn,
                    contentDescription = "Zoom in",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit
) {
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search...",
                onSearch = onSearch,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSearch, modifier = Modifier.height(32.dp)) {
                Text("Search", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
            }
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Button(onClick = onOpenSettings, modifier = Modifier.height(32.dp)) {
                    Icon(Icons.Filled.Store, contentDescription = "Marketplaces", modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(32.dp)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 8.dp),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
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
