package com.opencompares.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencompares.android.chatgpt.AccountState
import com.opencompares.android.chatgpt.ChatGptViewModel
import com.opencompares.android.chatgpt.ConnectionTest

private const val MANAGE_USAGE_URL = "https://chatgpt.com/settings/usage"

/** Connect, test and disconnect the user's ChatGPT plan. */
@Composable
fun ChatGptDialog(viewModel: ChatGptViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ChatGPT") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (val s = state) {
                    AccountState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))

                    is AccountState.SignedOut -> {
                        Text("Use your ChatGPT plan", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Complete eligible AI requests in this app with usage included in your ChatGPT plan or credits balance.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Requests run from this phone, for your own searches only. Open Compares never sees your ChatGPT password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        s.message?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.signIn { url -> openSignInTab(context, url) } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Continue with ChatGPT") }
                    }

                    is AccountState.SigningIn -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(s.step, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = viewModel::cancelSignIn) { Text("Cancel") }
                    }

                    is AccountState.SignedIn -> {
                        Text("Signed in as ${s.email ?: s.name ?: "your ChatGPT account"}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        if (s.hasPlanUsage) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Using ChatGPT plan", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { openExternal(context, MANAGE_USAGE_URL) }) { Text("Manage usage") }
                            }
                        } else {
                            Text(
                                "Plan usage wasn't granted, so AI features can't use your ChatGPT plan. Sign out and sign in again to allow it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        ConnectionTestSection(viewModel, enabled = s.hasPlanUsage)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = viewModel::signOut) { Text("Sign out") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    val signedIn = state as? AccountState.SignedIn
    if (signedIn?.showWelcome == true) {
        AlertDialog(
            onDismissRequest = viewModel::dismissWelcome,
            title = { Text("You're using your ChatGPT plan") },
            text = { Text("Eligible usage in this app uses your ChatGPT plan. Manage usage in your ChatGPT settings.") },
            confirmButton = { TextButton(onClick = viewModel::dismissWelcome) { Text("Got it") } }
        )
    }
}

@Composable
private fun ConnectionTestSection(viewModel: ChatGptViewModel, enabled: Boolean) {
    when (val test = viewModel.connectionTest) {
        ConnectionTest.Idle -> Button(onClick = viewModel::testConnection, enabled = enabled) { Text("Test connection") }
        is ConnectionTest.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(test.partialText.ifEmpty { "Asking ChatGPT…" }, style = MaterialTheme.typography.bodySmall)
        }
        is ConnectionTest.Passed -> Column {
            Text("Connected ✓", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(test.reply, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::testConnection) { Text("Test again") }
        }
        is ConnectionTest.Failed -> Column {
            Text(test.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::testConnection) { Text("Try again") }
        }
    }
}

/** Opens OpenAI's sign-in in a browser tab inside the app, or the full browser as a fallback. */
private fun openSignInTab(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    } catch (_: ActivityNotFoundException) {
        openExternal(context, url)
    }
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser installed.
    }
}
