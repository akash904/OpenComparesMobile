package com.opencompares.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencompares.android.prices.formatRupees
import com.opencompares.android.shopping.BasketResult
import com.opencompares.android.shopping.SiteCart
import com.opencompares.android.shopping.ChatEntry
import com.opencompares.android.shopping.ItemChange
import com.opencompares.android.shopping.ItemOption
import com.opencompares.android.shopping.ListDiff
import com.opencompares.android.shopping.ItemPlan
import com.opencompares.android.shopping.ShoppingSession
import com.opencompares.android.shopping.ShoppingWork
import com.opencompares.android.shopping.WantedItem

/**
 * The landing screen: a chat that asks what the shopper wants, then finds where to buy it.
 * It covers the marketplace panes, which keep loading underneath so they can be searched.
 */
@Composable
fun ShoppingChat(
    session: ShoppingSession,
    signedIn: Boolean,
    siteName: (String) -> String,
    onSignIn: () -> Unit,
    onFindPrices: () -> Unit,
    onOpenOffer: (providerKey: String, url: String) -> Unit,
    onFillCarts: (BasketResult, List<String>) -> Unit,
    onOpenCart: (String) -> Unit,
    onEmptyAdded: (SiteCart) -> Unit,
    /** Asks to empty these sites' carts completely. */
    onEmptyCarts: (List<String>) -> Unit,
    cartCount: (String) -> Int?,
    modifier: Modifier = Modifier,
    /** Shown under the greeting: the shopper's sites and delivery location. */
    sitesCard: @Composable () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val work = session.work
    val lastResultId = session.entries.lastOrNull { it is ChatEntry.ResultCard }?.id
    // The result whose carts the shopper is about to fill, while the confirm dialog is open.
    var confirming by remember { mutableStateOf<BasketResult?>(null) }
    confirming?.let { result ->
        CartConfirmDialog(
            result = result,
            siteName = siteName,
            cartCount = cartCount,
            onConfirm = { keys ->
                confirming = null
                onFillCarts(result, keys)
            },
            onDismiss = { confirming = null }
        )
    }
    val lastListId = session.entries.lastOrNull { it is ChatEntry.ListCard }?.id

    LaunchedEffect(session.entries.size, work) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    // Surface also stops touches reaching the panes underneath.
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // The greeting, then the sites card, then the conversation.
                items(session.entries.take(1), key = { it.id }) { entry ->
                    if (entry is ChatEntry.FromAssistant) Bubble(entry.text, fromUser = false)
                }
                item(key = "sites") { sitesCard() }
                items(session.entries.drop(1), key = { it.id }) { entry ->
                    when (entry) {
                        is ChatEntry.FromUser -> Bubble(entry.text, fromUser = true)
                        is ChatEntry.FromAssistant -> Bubble(entry.text, fromUser = false, isError = entry.isError)
                        is ChatEntry.ListCard -> ListCard(
                            items = entry.items,
                            diff = entry.diff,
                            // Only the latest list can be searched; older ones are history.
                            canSearch = entry.id == lastListId && work == ShoppingWork.Idle,
                            isUpdate = session.hasResults,
                            onFindPrices = onFindPrices
                        )
                        is ChatEntry.ResultCard -> ResultCard(
                            entry = entry,
                            siteName = siteName,
                            onOpenOffer = onOpenOffer,
                            // Only the newest prices can go in the carts; older ones may be stale.
                            onCheckTotals = if (entry.id == lastResultId && work == ShoppingWork.Idle) {
                                { confirming = entry.result }
                            } else null
                        )
                        is ChatEntry.CartCard -> CartCard(
                            comparison = entry.comparison,
                            siteName = siteName,
                            busy = work != ShoppingWork.Idle,
                            onOpenCart = onOpenCart,
                            onEmptyAdded = onEmptyAdded,
                            onEmptyCarts = onEmptyCarts
                        )
                    }
                }
                if (!signedIn) {
                    item(key = "sign-in") {
                        Column {
                            Bubble("Sign in with ChatGPT so I can read your list and match products across sites. It uses your own ChatGPT plan.", fromUser = false)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = onSignIn) { Text("Sign in with ChatGPT") }
                        }
                    }
                }
                if (work != ShoppingWork.Idle) {
                    item(key = "work") { WorkRow(work, onStop = session::stop) }
                }
            }
            HorizontalDivider()
            MessageInput(enabled = signedIn && work == ShoppingWork.Idle, onSend = session::send)
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, isError: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isError -> MaterialTheme.colorScheme.onErrorContainer
                fromUser -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        isError -> MaterialTheme.colorScheme.errorContainer
                        fromUser -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ChatCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(12.dp)
    ) { content() }
}

@Composable
private fun ListCard(
    items: List<WantedItem>,
    diff: ListDiff?,
    canSearch: Boolean,
    isUpdate: Boolean,
    onFindPrices: () -> Unit
) {
    ChatCard {
        Text(if (diff == null) "Your list" else "Your list, updated", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    // Say what this message did, so a wrong guess (added vs replaced) is easy to spot.
                    val change = diff?.changes?.get(item.id)
                    val label = when (change) {
                        ItemChange.Added -> "new"
                        ItemChange.Replaced -> "replaces ${diff.replaced[item.id]?.name}"
                        ItemChange.Changed -> "changed"
                        else -> null
                    }
                    label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
                if (item.amountText.isNotEmpty()) {
                    Text(item.amountText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        diff?.removed?.forEach { gone ->
            Text(
                gone.name,
                style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        if (canSearch) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onFindPrices) { Text(if (isUpdate) "Update prices" else "Find the best prices") }
            Hint(
                if (isUpdate) "Only new or changed items are searched again; the rest reuse recent results."
                else "Searches each site for every item, with your logins and location. You can also change the list by typing."
            )
        }
    }
}

@Composable
private fun WorkRow(work: ShoppingWork, onStop: () -> Unit) {
    val text = when (work) {
        ShoppingWork.Thinking -> "Reading your list…"
        is ShoppingWork.Searching -> "Searching every site for ${work.itemName} (${work.number} of ${work.total})…"
        ShoppingWork.Matching -> "ChatGPT is matching products across sites…"
        is ShoppingWork.Carting -> if (work.siteName.isEmpty()) {
            "Filling carts (${work.number} of ${work.total} done)\n${work.step}"
        } else {
            "${work.siteName}: ${work.step}" + if (work.number > 0) " (${work.number} of ${work.total})…" else "…"
        }
        ShoppingWork.Idle -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (work is ShoppingWork.Searching || work == ShoppingWork.Matching || work is ShoppingWork.Carting) {
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun ResultCard(
    entry: ChatEntry.ResultCard,
    siteName: (String) -> String,
    onOpenOffer: (String, String) -> Unit,
    onCheckTotals: (() -> Unit)?
) {
    val result = entry.result
    ChatCard {
        Text("Prices for your list", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Hint("Item prices only. Delivery, handling and other charges show up in each site's cart.")
        Spacer(Modifier.height(8.dp))
        Summary(result, siteName)
        if (onCheckTotals != null && result.sites.any { it.found.isNotEmpty() }) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCheckTotals) { Text("Check real totals with charges") }
            Hint("Adds the items to each site's cart, after you confirm, and reads the bill. Nothing is ordered.")
        }
        if (entry.needLogin.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Hint("${entry.needLogin.joinToString()} asked you to log in, so it was left out. Log in in its pane and search again.")
        }
        if (entry.needLocation.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Hint("${entry.needLocation.joinToString()} asked for a delivery address, so it was left out. Use Set up in Your sites, then search again.")
        }
        if (entry.noResults.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Hint("${entry.noResults.joinToString()} showed no products, so it was left out.")
        }
        entry.reusedNote?.let {
            Spacer(Modifier.height(6.dp))
            Hint(it)
        }
        entry.warnings.forEach {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        result.items.forEach { plan ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            ItemRows(plan, result, siteName, onOpenOffer)
        }
    }
}

@Composable
private fun Summary(result: BasketResult, siteName: (String) -> String) {
    val single = result.bestSingleSite
    if (single != null) {
        val everything = if (result.unavailable.isEmpty()) "Everything" else "Everything that was found"
        Text(
            "$everything on ${siteName(single.providerKey)}: ${formatRupees(single.subtotal)}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    } else if (result.items.isNotEmpty()) {
        Text("No single site has everything.", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    }
    result.sites.forEach { site ->
        val total = site.found.size + site.missing.size
        val coverage = when {
            !site.complete -> "${site.found.size} of $total items"
            result.unavailable.isEmpty() -> "all $total items"
            else -> "all $total found items"
        }
        val walled = result.items.filter { site.providerKey in it.loginNeeded }.map { it.item }
        val notThere = site.missing - walled.toSet()
        val missing = listOfNotNull(
            notThere.takeIf { it.isNotEmpty() && it.size <= 3 }?.let { "no ${it.joinToString { w -> w.name }}" },
            walled.takeIf { it.isNotEmpty() }?.let { "asked to log in for ${it.joinToString { w -> w.name }}" }
        ).joinToString("; ").let { if (it.isEmpty()) "" else " ($it)" }
        Text(
            "${siteName(site.providerKey)}: $coverage, ${formatRupees(site.subtotal)}$missing",
            style = MaterialTheme.typography.bodySmall
        )
    }
    val split = result.cheapestSplit
    val saving = single?.let { it.subtotal - result.cheapestSplitTotal }
    // Only worth a line when splitting costs less than one site, or no site has everything.
    if (split.size > 1 && (saving == null || saving >= 0.5)) {
        val sites = split.keys.joinToString(" and ") { siteName(it) }
        Spacer(Modifier.height(4.dp))
        Text(
            "Cheapest by item: ${formatRupees(result.cheapestSplitTotal)} across $sites" +
                (saving?.takeIf { it >= 0.5 }?.let { ", ${formatRupees(it)} less before charges" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    if (result.unavailable.isNotEmpty()) {
        Text(
            "Not found anywhere: ${result.unavailable.joinToString { it.name }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ItemRows(plan: ItemPlan, result: BasketResult, siteName: (String) -> String, onOpenOffer: (String, String) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(plan.item.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        if (plan.item.amountText.isNotEmpty()) {
            Text(plan.item.amountText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    plan.options.forEach { option ->
        OptionRow(option, isCheapest = plan.hasClearCheapest && option == plan.cheapest, siteName(option.providerKey), onOpenOffer)
    }
    if (plan.options.size > 1 && !plan.hasClearCheapest) Hint("Same price on more than one site.")
    val missingOn = result.sites.map { it.providerKey }
        .filter { key -> key !in plan.loginNeeded && plan.options.none { it.providerKey == key } }
    if (plan.loginNeeded.isNotEmpty()) {
        Hint("${plan.loginNeeded.joinToString { siteName(it) }} asked you to log in for this search.")
    }
    if (missingOn.isNotEmpty() && plan.options.isNotEmpty()) {
        Hint("Not found on ${missingOn.joinToString { siteName(it) }}")
    }
    if (plan.options.isEmpty() && plan.loginNeeded.isEmpty()) Hint("Not found on any site.")
    if (plan.similar.isNotEmpty()) {
        Text(
            if (plan.options.isEmpty()) "Similar, not exactly what you asked for:" else "Similar on other sites:",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        plan.similar.forEach { option ->
            OptionRow(option, isCheapest = false, siteName(option.providerKey), onOpenOffer)
        }
        Hint("Not counted in the totals. To use one, tell me, like \"use ${plan.similar.first().offer.name.take(30)} instead\".")
    }
    plan.note?.let { Hint(it) }
}

@Composable
private fun OptionRow(option: ItemOption, isCheapest: Boolean, siteName: String, onOpenOffer: (String, String) -> Unit) {
    val link = option.offer.link
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (link != null) Modifier.clickable { onOpenOffer(option.providerKey, link) } else Modifier)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (isCheapest) "$siteName · Cheapest" else siteName,
                style = MaterialTheme.typography.labelMedium,
                color = if (isCheapest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                listOf(option.offer.name, option.amountText, if (option.moreThanWanted) "more than you asked for" else "")
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatRupees(option.cost),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isCheapest) FontWeight.Bold else FontWeight.Normal)
        )
    }
}

@Composable
private fun MessageInput(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("What do you want to buy?") },
            enabled = enabled,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() })
        )
        IconButton(onClick = send, enabled = enabled && text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
