package com.opencompares.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencompares.android.MVP_PROVIDERS
import com.opencompares.android.Provider
import com.opencompares.android.data.AppSettings
import com.opencompares.android.newCustomProviderKey
import com.opencompares.android.validateCustomProvider

/**
 * Picks marketplaces, grid columns and pane height, and manages custom marketplaces.
 * Changes are kept locally and only applied on Save.
 */
@Composable
fun MarketplaceSettingsDialog(
    current: AppSettings,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(current.selectedProviders) }
    var columns by remember { mutableIntStateOf(current.gridColumns) }
    var height by remember { mutableIntStateOf(current.gridHeightDp) }
    var customProviders by remember { mutableStateOf(current.customProviders) }
    var showAddDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Marketplaces") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SectionTitle("Grid Layout")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Columns")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1")
                        RadioButton(selected = columns == 1, onClick = { columns = 1 })
                        Spacer(Modifier.width(8.dp))
                        Text("2")
                        RadioButton(selected = columns == 2, onClick = { columns = 2 })
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle("Marketplaces")
                (MVP_PROVIDERS + customProviders).forEach { p ->
                    val isCustom = customProviders.any { it.key == p.key }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.displayName, modifier = Modifier.weight(1f))
                        if (isCustom) {
                            IconButton(onClick = {
                                customProviders = customProviders.filterNot { it.key == p.key }
                                selected = selected - p.key
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove ${p.displayName}", modifier = Modifier.size(18.dp))
                            }
                        }
                        Checkbox(
                            checked = p.key in selected,
                            onCheckedChange = { checked -> selected = if (checked) selected + p.key else selected - p.key }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(onClick = { showAddDialog = true }, modifier = Modifier.align(Alignment.End)) {
                    Text("Add Provider")
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle("Grid Height")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Height (dp)")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            height = (height - 50).coerceIn(AppSettings.MIN_GRID_HEIGHT, AppSettings.MAX_GRID_HEIGHT)
                        }) { Text("-") }
                        Text(
                            text = height.toString(),
                            modifier = Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = {
                            height = (height + 50).coerceIn(AppSettings.MIN_GRID_HEIGHT, AppSettings.MAX_GRID_HEIGHT)
                        }) { Text("+") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val keys = (MVP_PROVIDERS + customProviders).map { it.key }.toSet()
                // copy() keeps settings this dialog doesn't show, like the delivery location.
                onSave(
                    current.copy(
                        selectedProviders = selected intersect keys,
                        gridColumns = columns,
                        gridHeightDp = height,
                        customProviders = customProviders
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showAddDialog) {
        AddProviderDialog(
            existingKeys = (MVP_PROVIDERS + customProviders).map { it.key }.toSet(),
            onAdd = { provider ->
                customProviders = customProviders + provider
                selected = selected + provider.key
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun AddProviderDialog(
    existingKeys: Set<String>,
    onAdd: (Provider) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Provider") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL Template") },
                    singleLine = true,
                    placeholder = { Text("https://example.com/search?q={query}") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Use {query} as the placeholder for the search term",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                errorText?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmedName = name.trim()
                val trimmedUrl = url.trim()
                errorText = validateCustomProvider(trimmedName, trimmedUrl)
                if (errorText == null) {
                    onAdd(
                        Provider(
                            key = newCustomProviderKey(trimmedName, existingKeys),
                            displayName = trimmedName,
                            urlTemplate = trimmedUrl,
                            homeUrl = null,
                            defaultZoom = 50
                        )
                    )
                }
            }) { Text("Add Provider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
