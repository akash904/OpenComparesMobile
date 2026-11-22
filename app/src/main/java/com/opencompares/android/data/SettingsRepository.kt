package com.opencompares.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opencompares.android.CustomProviderCodec
import com.opencompares.android.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The settings the user changes in the Marketplaces dialog. */
data class AppSettings(
    val selectedProviders: Set<String> = DEFAULT_SELECTED,
    val gridColumns: Int = 2,
    val gridHeightDp: Int = 500,
    val customProviders: List<Provider> = emptyList()
) {
    companion object {
        val DEFAULT_SELECTED = setOf("blinkit", "zepto", "instamart")
        const val MIN_GRID_HEIGHT = 300
        const val MAX_GRID_HEIGHT = 800
    }
}

/** Name of the SharedPreferences file used up to version 1.1. */
private const val LEGACY_PREFS = "oc_prefs"

// Copies the old SharedPreferences values over once, under the same key names, then deletes them.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context, LEGACY_PREFS)) }
)

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    // Same names as the old SharedPreferences keys so the migration maps them directly.
    private object Keys {
        val SELECTED = stringSetPreferencesKey("selectedProviders")
        val GRID_COLUMNS = intPreferencesKey("gridColumns")
        val GRID_HEIGHT = intPreferencesKey("gridHeight")
        val CUSTOM_PROVIDERS = stringSetPreferencesKey("customProviders")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                selectedProviders = prefs[Keys.SELECTED] ?: AppSettings.DEFAULT_SELECTED,
                gridColumns = (prefs[Keys.GRID_COLUMNS] ?: 2).coerceIn(1, 2),
                gridHeightDp = (prefs[Keys.GRID_HEIGHT] ?: 500)
                    .coerceIn(AppSettings.MIN_GRID_HEIGHT, AppSettings.MAX_GRID_HEIGHT),
                customProviders = CustomProviderCodec.decodeAll(prefs[Keys.CUSTOM_PROVIDERS] ?: emptySet())
            )
        }

    suspend fun save(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.SELECTED] = settings.selectedProviders
            prefs[Keys.GRID_COLUMNS] = settings.gridColumns
            prefs[Keys.GRID_HEIGHT] = settings.gridHeightDp
            prefs[Keys.CUSTOM_PROVIDERS] = settings.customProviders.map(CustomProviderCodec::encode).toSet()
        }
    }
}
