package com.opencompares.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opencompares.android.CompareViewModel
import com.opencompares.android.PaneError
import com.opencompares.android.PaneState
import com.opencompares.android.PaneWebViewEvents
import com.opencompares.android.PaneWebViewHost
import com.opencompares.android.PaneWebViewStore
import com.opencompares.android.Provider
import com.opencompares.android.ScrollChainTarget

/**
 * One marketplace: a header with navigation controls above its WebView.
 * The same card is used in the grid and fullscreen; the WebView moves between them intact.
 */
@Composable
fun MarketplacePane(
    provider: Provider,
    pane: PaneState,
    isFullscreen: Boolean,
    store: PaneWebViewStore,
    viewModel: CompareViewModel,
    scrollChain: ScrollChainTarget?,
    modifier: Modifier = Modifier
) {
    val key = provider.key
    // Only refers to the view model and the key, so it never goes stale.
    val events = remember(key, viewModel) {
        object : PaneWebViewEvents {
            override fun onPageStarted(url: String) = viewModel.onPageStarted(key)
            override fun onProgress(percent: Int) = viewModel.onProgress(key, percent)
            override fun onPageFinished(url: String, canGoBack: Boolean) = viewModel.onPageFinished(key, url, canGoBack)
            override fun onMainFrameError(url: String, errorCode: Int, description: String) =
                viewModel.onMainFrameError(key, url, errorCode)
            override fun onRenderProcessGone() = viewModel.onRenderProcessGone(key)
        }
    }

    // Each finished load may change the login or address; sites fill these in a moment later.
    LaunchedEffect(key, pane.loads, pane.instanceId) {
        kotlinx.coroutines.delay(2_500)
        viewModel.refreshStatus(key, store)
    }

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            PaneHeader(
                provider = provider,
                pane = pane,
                isFullscreen = isFullscreen,
                onBack = { viewModel.goBack(key) },
                onToggleFullscreen = {
                    if (isFullscreen) viewModel.exitFullscreen() else viewModel.openFullscreen(key)
                },
                onHome = { viewModel.goHome(key) },
                onReload = { viewModel.reload(key) },
                onToggleDesktop = { viewModel.toggleDesktop(key) }
            )

            // Thin loading bar; the spacer keeps the WebView from jumping when it hides.
            if (pane.loading) {
                LinearProgressIndicator(
                    progress = { pane.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }

            Box(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
                // A crashed WebView is gone; a new one is built when the user retries. The site is
                // part of the key too: the fullscreen pane can switch sites in place, and must not
                // keep showing the previous site's WebView.
                if (pane.error?.kind != PaneError.Kind.Crashed) {
                    key(key, pane.instanceId) {
                        PaneWebViewHost(
                            provider = provider,
                            pane = pane,
                            store = store,
                            events = events,
                            scrollChain = scrollChain,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                pane.error?.let { error ->
                    PaneErrorOverlay(
                        error = error,
                        siteName = provider.displayName,
                        onRetry = { viewModel.retry(key) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun PaneHeader(
    provider: Provider,
    pane: PaneState,
    isFullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onToggleDesktop: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            provider.displayName,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                IconButton(onClick = onBack, enabled = pane.canGoBack, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Go back",
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                        modifier = Modifier.size(16.dp)
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open in browser") },
                            onClick = {
                                menuOpen = false
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pane.currentUrl)))
                                } catch (_: ActivityNotFoundException) {
                                    // No browser installed; nothing sensible to do.
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Home") },
                            onClick = {
                                menuOpen = false
                                onHome()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reload") },
                            onClick = {
                                menuOpen = false
                                onReload()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Desktop mode") },
                            trailingIcon = {
                                Checkbox(checked = pane.desktopMode, onCheckedChange = { onToggleDesktop() })
                            },
                            onClick = {
                                menuOpen = false
                                onToggleDesktop()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneErrorOverlay(
    error: PaneError,
    siteName: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (title, body) = when (error.kind) {
        PaneError.Kind.Offline -> "No connection" to "Couldn't reach $siteName. Check your internet connection."
        PaneError.Kind.LoadFailed -> "Page didn't load" to "$siteName couldn't be loaded right now."
        PaneError.Kind.Crashed -> "Page stopped" to "$siteName stopped working, often because the phone ran low on memory."
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (error.kind == PaneError.Kind.Offline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text(if (error.kind == PaneError.Kind.Crashed) "Reload" else "Retry") }
        }
    }
}
