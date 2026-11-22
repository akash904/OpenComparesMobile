package com.opencompares.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencompares.android.Provider
import com.opencompares.android.prices.Offer
import com.opencompares.android.prices.PaneReadResult
import com.opencompares.android.prices.formatRupees

/** Shows the products read from each pane, grouped by marketplace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesSheet(
    results: List<PaneReadResult>,
    reading: Boolean,
    providers: List<Provider>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val nameOf = { key: String -> providers.firstOrNull { it.key == key }?.displayName ?: key }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Prices on screen", style = MaterialTheme.typography.titleMedium)
                if (reading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onRefresh) { Text("Read again") }
                }
            }
            Text(
                "Read from each page on this phone, with your logins and location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            if (results.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(if (reading) "Reading prices…" else "Nothing read yet.")
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    results.forEach { result ->
                        item(key = "h-${result.providerKey}") {
                            SiteHeader(nameOf(result.providerKey), result)
                        }
                        if (result is PaneReadResult.Read) {
                            items(result.page.offers, key = { "o-${result.providerKey}-${it.name}-${it.price}" }) { offer ->
                                OfferRow(offer)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SiteHeader(name: String, result: PaneReadResult) {
    val detail = when (result) {
        is PaneReadResult.Read -> when {
            result.page.offers.isNotEmpty() -> "${result.page.offers.size} products"
            result.page.needsLogin -> "This site wants you to log in first. Log in inside its pane, then read again."
            result.page.scannedPrices == 0 -> "No prices on this page yet. Search first, or wait for it to load."
            else -> "Prices found, but no products recognised on this page."
        }
        is PaneReadResult.NotLoaded -> "Page not loaded."
        is PaneReadResult.Failed -> result.reason
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OfferRow(offer: Offer) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(offer.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                offer.quantity?.toString() ?: offer.quantityText,
                offer.unitPrice?.toString(),
                offer.etaText,
                if (!offer.inStock) "Out of stock" else null
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatRupees(offer.price), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            offer.mrp?.let {
                Text(
                    formatRupees(it),
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            offer.discountPercent?.let {
                Text("$it% off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
