package com.opencompares.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencompares.android.prices.formatRupees
import com.opencompares.android.shopping.BasketResult
import com.opencompares.android.shopping.CartComparison
import com.opencompares.android.shopping.ChargeKind
import com.opencompares.android.shopping.SiteCart

/**
 * Asks which sites' carts the app may fill. Every site with items starts ticked; the
 * shopper sees exactly what goes where, and that nothing is ordered.
 */
@Composable
fun CartConfirmDialog(
    result: BasketResult,
    siteName: (String) -> String,
    cartCount: (String) -> Int?,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sites = result.sites.filter { it.found.isNotEmpty() }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(sites.map { it.providerKey }) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check real totals") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The app will add these items to each site's cart and read its bill, with delivery and other charges. " +
                        "Nothing is ordered. The items stay in the carts until you order or remove them.",
                    style = MaterialTheme.typography.bodySmall
                )
                sites.forEach { site ->
                    val key = site.providerKey
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { if (key in chosen) chosen.remove(key) else chosen.add(key) },
                        verticalAlignment = Alignment.Top
                    ) {
                        Checkbox(checked = key in chosen, onCheckedChange = { if (it) chosen.add(key) else chosen.remove(key) })
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text(
                                "${siteName(key)}: ${site.found.size} item${if (site.found.size == 1) "" else "s"}, ${formatRupees(site.subtotal)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                result.items.mapNotNull { plan ->
                                    plan.options.firstOrNull { it.providerKey == key }?.let { o ->
                                        plan.item.name + (o.amountText.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
                                    }
                                }.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall
                            )
                            cartCount(key)?.takeIf { it > 0 }?.let { n ->
                                Text(
                                    "Its cart already has $n item${if (n == 1) "" else "s"}; they'll be in the bill too.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sites.map { it.providerKey }.filter { it in chosen }) }, enabled = chosen.isNotEmpty()) {
                Text("Add to carts")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Asks which sites' carts to empty. Every site starts ticked; emptying takes out everything,
 * items the shopper added on the site too, so the dialog says so.
 */
@Composable
fun EmptyCartsDialog(
    siteKeys: List<String>,
    siteName: (String) -> String,
    cartCount: (String) -> Int?,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(siteKeys) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Empty carts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The app opens each site's cart and takes everything out, including items you added on the site yourself.",
                    style = MaterialTheme.typography.bodySmall
                )
                siteKeys.forEach { key ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (key in chosen) chosen.remove(key) else chosen.add(key) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = key in chosen, onCheckedChange = { if (it) chosen.add(key) else chosen.remove(key) })
                        Text(siteName(key), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        cartCount(key)?.let { n ->
                            Text(
                                if (n == 0) "empty" else "$n item${if (n == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(siteKeys.filter { it in chosen }) }, enabled = chosen.isNotEmpty()) {
                Text("Empty carts")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Each filled cart's real bill, and what that means for where to order. */
@Composable
fun CartCard(
    comparison: CartComparison,
    siteName: (String) -> String,
    busy: Boolean,
    onOpenCart: (String) -> Unit,
    onEmptyAdded: (SiteCart) -> Unit,
    onEmptyCarts: (List<String>) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(12.dp)
    ) {
        Text("Real totals, with all charges", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        val best = comparison.best
        if (best != null) {
            Text(
                "Cheapest for the whole list: ${siteName(best.providerKey)}, ${formatRupees(best.bill!!.toPay)} to pay",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
        if (comparison.mixed.isNotEmpty()) {
            val names = comparison.mixed.joinToString(" and ") { siteName(it.providerKey) }
            Hint(
                "$names had other items in the cart too, so " +
                    (if (comparison.mixed.size == 1) "its bill isn't" else "their bills aren't") +
                    " compared. Empty those carts first for a fair comparison."
            )
        }
        comparison.splitAtLeast?.let { split ->
            val text = if (comparison.splitNotWorthIt) {
                "Splitting the order costs at least ${formatRupees(split)} with each site's charges, so it doesn't save money."
            } else {
                "Splitting the order would cost at least ${formatRupees(split)}; smaller orders can pay higher charges, so check before splitting."
            }
            Hint(text)
        }
        comparison.carts.forEach { cart ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            SiteBill(cart, siteName(cart.providerKey), isBest = cart == best, busy = busy, onOpenCart = onOpenCart, onEmptyAdded = onEmptyAdded)
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        TextButton(onClick = { onEmptyCarts(comparison.carts.map { it.providerKey }) }, enabled = !busy) {
            Text("Empty all these carts")
        }
    }
}

@Composable
private fun SiteBill(
    cart: SiteCart,
    name: String,
    isBest: Boolean,
    busy: Boolean,
    onOpenCart: (String) -> Unit,
    onEmptyAdded: (SiteCart) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (isBest) "$name · Cheapest" else name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        cart.bill?.let {
            Text("${formatRupees(it.toPay)} to pay", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
    val bill = cart.bill
    if (bill != null) {
        bill.lines.forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                Text(line.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    when {
                        line.amount == 0.0 && line.kind != ChargeKind.Items -> "Free"
                        line.amount < 0 -> "−" + formatRupees(-line.amount)
                        else -> formatRupees(line.amount)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        bill.extra?.takeIf { it > 0.5 }?.let { Hint("Charges add ${formatRupees(it)} on top of the items.") }
        bill.freeDeliveryAbove?.let { Hint("Free delivery above ${formatRupees(it)}.") }
    }
    cart.otherItems.takeIf { it > 0 }?.let {
        Text(
            "The cart already had $it other item${if (it == 1) "" else "s"}; they're in this bill too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    cart.items.filter { it.problem != null }.forEach {
        Text("${it.item.name}: ${it.problem}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    cart.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Row {
        TextButton(onClick = { onOpenCart(cart.providerKey) }) { Text("Open cart to order") }
        if (cart.items.any { it.addedByApp > 0 }) {
            TextButton(onClick = { onEmptyAdded(cart) }, enabled = !busy) { Text("Remove what was added") }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
