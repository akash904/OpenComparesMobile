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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.opencompares.android.PlaceLookup
import com.opencompares.android.sites.LocationState
import com.opencompares.android.sites.LoginState
import com.opencompares.android.sites.SiteStatus

/** One marketplace as shown in the "Your sites" card. */
data class SiteRow(
    val key: String,
    val name: String,
    val status: SiteStatus?,
    /** Why its prices may not suit the shopper, e.g. another delivery address. */
    val warning: String?,
    /** The shopper chose to use this site without logging in. */
    val loginSkipped: Boolean,
    /** The shopper chose to let this site use its own idea of their area. */
    val locationSkipped: Boolean
) {
    val loginDone: Boolean get() = status?.login == LoginState.In
    val locationDone: Boolean get() = status?.location == LocationState.Set && warning == null
    val ready: Boolean get() = (loginDone || loginSkipped) && (locationDone || locationSkipped)
}

/**
 * The shopper's delivery location and each site's login and address, with a way to fix
 * or skip each. Skipping keeps the site in searches: as a guest, or with its own area.
 */
@Composable
fun SitesCard(
    rows: List<SiteRow>,
    deliveryText: String?,
    usingTypedPlace: Boolean,
    placeLookup: PlaceLookup,
    onUsePhoneLocation: () -> Unit,
    onUseTypedPlace: (String) -> Unit,
    onSetUp: (String) -> Unit,
    onSkipLogin: (String, Boolean) -> Unit,
    onSkipLocation: (String, Boolean) -> Unit,
    onManagePlatforms: () -> Unit,
    onCheckAgain: () -> Unit
) {
    val readyCount = rows.count { it.ready }
    val active = rows.size
    var expanded by rememberSaveable { mutableStateOf(true) }
    var changingPlace by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Your sites", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "$readyCount of $active ready" + if (expanded) "" else " · show",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!expanded) return@Column

        // Delivery location, shared by every site.
        Spacer(Modifier.height(8.dp))
        Text("Deliver to", style = MaterialTheme.typography.labelMedium)
        Text(
            when {
                deliveryText == null -> "Not set. Share your location or type an area."
                usingTypedPlace -> "$deliveryText (typed in)"
                else -> "$deliveryText (your phone's location)"
            },
            style = MaterialTheme.typography.bodyMedium
        )
        if (!changingPlace) {
            TextButton(onClick = { changingPlace = true }) { Text(if (deliveryText == null) "Set location" else "Change") }
        } else {
            PlaceEditor(
                placeLookup = placeLookup,
                onUsePhoneLocation = {
                    onUsePhoneLocation()
                    changingPlace = false
                },
                onUseTypedPlace = onUseTypedPlace,
                onClose = { changingPlace = false }
            )
        }
        Hint("Sites that ask for your location get this one. Nothing else about you is shared with them by the app.")

        rows.forEach { row ->
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            SiteLine(row, onSetUp, onSkipLogin, onSkipLocation)
        }
        Spacer(Modifier.height(8.dp))
        Hint("Logging in shows your own prices and offers, and lets the app read real delivery fees later. You can skip logging in or setting the address on any site; it is still searched.")
        Row {
            TextButton(onClick = onManagePlatforms) { Text("Manage platforms") }
            TextButton(onClick = onCheckAgain) { Text("Check again") }
        }
    }
}

@Composable
private fun PlaceEditor(
    placeLookup: PlaceLookup,
    onUsePhoneLocation: () -> Unit,
    onUseTypedPlace: (String) -> Unit,
    onClose: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedButton(onClick = onUsePhoneLocation) { Text("Use my phone's location") }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Area and city, or pincode") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onUseTypedPlace(text) })
            )
            Spacer(Modifier.width(8.dp))
            if (placeLookup == PlaceLookup.Searching) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Button(onClick = { onUseTypedPlace(text) }, enabled = text.isNotBlank()) { Text("Use") }
            }
        }
        if (placeLookup is PlaceLookup.Failed) {
            Text(placeLookup.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun SiteLine(
    row: SiteRow,
    onSetUp: (String) -> Unit,
    onSkipLogin: (String, Boolean) -> Unit,
    onSkipLocation: (String, Boolean) -> Unit
) {
    val s = row.status
    val (icon, tint) = when {
        row.ready -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        s == null -> Icons.Filled.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Filled.ErrorOutline to Color(0xFFB26A00)
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                if (s != null && !row.ready) {
                    TextButton(onClick = { onSetUp(row.key) }) { Text("Set up") }
                }
            }
            StepLine(
                text = loginText(row),
                done = row.loginDone,
                skipLabel = when {
                    row.loginDone || s == null -> null
                    row.loginSkipped -> "Ask again"
                    else -> "Skip login"
                },
                onSkip = { onSkipLogin(row.key, !row.loginSkipped) }
            )
            StepLine(
                text = locationText(row),
                done = row.locationDone,
                skipLabel = when {
                    row.locationDone || s == null -> null
                    row.locationSkipped -> "Ask again"
                    else -> "Skip location"
                },
                onSkip = { onSkipLocation(row.key, !row.locationSkipped) }
            )
            row.warning?.takeIf { s?.location == LocationState.Set && !row.locationSkipped }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** One of a site's two steps, with a small button to skip it or ask again. */
@Composable
private fun StepLine(text: String, done: Boolean, skipLabel: String?, onSkip: () -> Unit) {
    if (text.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (skipLabel != null) {
            TextButton(onClick = onSkip) { Text(skipLabel, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

private fun loginText(row: SiteRow): String {
    val s = row.status ?: return "Not checked yet"
    return when {
        s.login == LoginState.In -> "Logged in"
        row.loginSkipped -> "Shopping as a guest"
        s.login == LoginState.Out -> "Not logged in"
        else -> "Login not detected yet"
    }
}

private fun locationText(row: SiteRow): String {
    val s = row.status ?: return ""
    if (row.locationSkipped && !row.locationDone) {
        return "Using the site's own area" + (s.label?.let { ": $it" } ?: "")
    }
    return when (s.location) {
        LocationState.Set -> "Delivering to ${s.label ?: "your chosen address"}"
        LocationState.Guessed -> "Guessing your area" + (s.label?.let { ": $it" } ?: "") + ". Pick your address."
        LocationState.Missing -> "No delivery address set"
        LocationState.Unknown -> "Delivery address not detected"
    }
}

/**
 * Shown under a site opened full screen for setup: what is left to do there, ticking off
 * live as the app notices the login and address.
 */
@Composable
fun SetupBanner(
    siteName: String,
    status: SiteStatus?,
    warning: String?,
    deliveryText: String?,
    onDone: () -> Unit
) {
    val loggedIn = status?.login == LoginState.In
    val placed = status?.location == LocationState.Set && warning == null
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Setting up $siteName", style = MaterialTheme.typography.titleSmall)
            Step(
                done = loggedIn,
                doneText = "Logged in",
                todoText = "Log in on $siteName above, usually with your phone number and an OTP. Optional, but it shows your own prices and fees."
            )
            Step(
                done = placed,
                doneText = "Delivering to ${status?.label ?: "your address"}",
                todoText = (if (status?.location == LocationState.Set && warning != null) "$warning " else "") +
                    "Tap the location at the top of $siteName and choose \"use current location\"" +
                    (deliveryText?.let { ": it will get $it" } ?: "") + ". Or type your address there."
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (loggedIn && placed) {
                    Button(onClick = onDone) { Text("All set") }
                } else {
                    TextButton(onClick = onDone) { Text("Done for now") }
                }
            }
        }
    }
}

@Composable
private fun Step(done: Boolean, doneText: String, todoText: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (done) Color(0xFF2E7D32) else Color(0xFFB26A00),
            modifier = Modifier.size(18.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(if (done) doneText else todoText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Hint(text: String) {
    if (text.isEmpty()) return
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
