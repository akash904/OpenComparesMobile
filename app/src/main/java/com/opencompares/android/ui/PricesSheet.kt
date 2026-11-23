package com.opencompares.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencompares.android.ComparisonState
import com.opencompares.android.Provider
import com.opencompares.android.prices.Comparison
import com.opencompares.android.prices.ListedOffer
import com.opencompares.android.prices.Offer
import com.opencompares.android.prices.PaneReadResult
import com.opencompares.android.prices.ProductMatch
import com.opencompares.android.prices.formatRupees

/** Shows the products read from each pane, grouped by marketplace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesSheet(
    results: List<PaneReadResult>,
    reading: Boolean,
    providers: List<Provider>,
    comparison: ComparisonState,
    chatGptSignedIn: Boolean,
    onRefresh: () -> Unit,
    onCompare: () -> Unit,
    onSignIn: () -> Unit,
    onOpenOffer: (providerKey: String, url: String) -> Unit,
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
                val sitesWithOffers = results.count { it is PaneReadResult.Read && it.page.offers.isNotEmpty() }
                LazyColumn(Modifier.fillMaxWidth()) {
                    // Always the first item, so the list opens at the top rather than past it.
                    item(key = "compare") {
                        ComparisonSection(
                            state = comparison,
                            enabled = !reading,
                            sitesWithOffers = sitesWithOffers,
                            signedIn = chatGptSignedIn,
                            nameOf = nameOf,
                            onCompare = onCompare,
                            onSignIn = onSignIn,
                            onOpenOffer = onOpenOffer
                        )
                    }
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
            result.page.needsLocation -> "This site wants a delivery address first. Set it in its pane, then read again."
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

@Composable
private fun ComparisonSection(
    state: ComparisonState,
    enabled: Boolean,
    sitesWithOffers: Int,
    signedIn: Boolean,
    nameOf: (String) -> String,
    onCompare: () -> Unit,
    onSignIn: () -> Unit,
    onOpenOffer: (String, String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        when (state) {
            ComparisonState.Idle -> {
                if (sitesWithOffers < 2) {
                    Hint("Read prices from at least two sites to compare them.")
                    return@Column
                }
                Text(
                    "Find the same products across sites and the best value per unit.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                if (signedIn) {
                    Button(onClick = onCompare, enabled = enabled) { Text("Compare with ChatGPT") }
                } else {
                    OutlinedButton(onClick = onSignIn) { Text("Sign in with ChatGPT to compare") }
                }
                Spacer(Modifier.height(4.dp))
                Hint("Sends only the product names, sizes and prices listed below to ChatGPT, using your plan.")
            }

            ComparisonState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("ChatGPT is matching products across sites…", style = MaterialTheme.typography.bodyMedium)
            }

            is ComparisonState.Failed -> {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                if (state.signInNeeded) {
                    OutlinedButton(onClick = onSignIn) { Text("Sign in with ChatGPT") }
                } else {
                    TextButton(onClick = onCompare) { Text("Try again") }
                }
            }

            is ComparisonState.Done -> ComparisonResult(state.comparison, nameOf, onCompare, onOpenOffer)
        }
    }
}

@Composable
private fun ComparisonResult(
    comparison: Comparison,
    nameOf: (String) -> String,
    onCompare: () -> Unit,
    onOpenOffer: (String, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Across sites", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onCompare) { Text("Compare again") }
    }
    comparison.summary?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
        Hint("Summary written by ChatGPT. Prices below are read from the pages.")
        Spacer(Modifier.height(8.dp))
    }
    if (comparison.matches.isEmpty()) {
        Hint("No product here appears on more than one site. Try a more specific search.")
    }
    comparison.matches.forEach { match ->
        MatchCard(match, nameOf, onOpenOffer)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MatchCard(match: ProductMatch, nameOf: (String) -> String, onOpenOffer: (String, String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp)
    ) {
        Text(
            match.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        val best = match.best
        val lead = match.leadPercent
        val per = match.comparedPer?.let { " per $it" } ?: ""
        val verdict = when {
            best == null -> "Nothing in stock to compare."
            match.tie -> "Same price$per on " +
                match.offers.map { nameOf(it.providerKey) }.distinct().take(2).joinToString(" and ")
            lead != null -> "${nameOf(best.providerKey)} is $lead% cheaper$per"
            else -> "Best value: ${nameOf(best.providerKey)}"
        }
        Text(
            verdict,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(4.dp))
        match.offers.forEach { listed ->
            MatchOfferRow(listed, isBest = !match.tie && listed == best, siteName = nameOf(listed.providerKey), onOpenOffer = onOpenOffer)
        }
    }
}

/** One site's listing inside a match. Tapping it opens the product in that site's pane. */
@Composable
private fun MatchOfferRow(listed: ListedOffer, isBest: Boolean, siteName: String, onOpenOffer: (String, String) -> Unit) {
    val offer = listed.offer
    val link = offer.link
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (link != null) Modifier.clickable { onOpenOffer(listed.providerKey, link) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (isBest) "$siteName · Best" else siteName,
                style = MaterialTheme.typography.labelMedium,
                color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            val details = listOfNotNull(
                offer.name,
                offer.quantity?.toString() ?: offer.quantityText,
                offer.unitPrice?.toString(),
                offer.etaText,
                if (!offer.inStock) "Out of stock" else null
            ).joinToString(" · ")
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatRupees(offer.price),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal)
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
