package com.opencompares.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opencompares.android.CustomProviderCodec
import com.opencompares.android.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** A delivery location the shopper typed in, used instead of the phone's location. */
data class DeliveryPlace(val label: String, val lat: Double, val lon: Double) {
    fun encode(): String = "$lat|$lon|$label"

    companion object {
        fun decode(s: String?): DeliveryPlace? {
            val parts = s?.split('|', limit = 3) ?: return null
            if (parts.size != 3) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return DeliveryPlace(parts[2], lat, lon)
        }
    }
}

/** The settings the user changes in the app. */
data class AppSettings(
    val selectedProviders: Set<String> = DEFAULT_SELECTED,
    val gridColumns: Int = 2,
    val gridHeightDp: Int = 500,
    val customProviders: List<Provider> = emptyList(),
    /** Null means the phone's own location. */
    val deliveryPlace: DeliveryPlace? = null,
    /** Sites the shopper chose to use without logging in. */
    val skippedLogin: Set<String> = emptySet(),
    /** Sites the shopper chose to use with the site's own idea of their area. */
    val skippedLocation: Set<String> = emptySet(),
    /** Each changed shopping list goes straight on to filled carts and real totals. */
    val autoMode: Boolean = false,
    /** In auto mode, empty each cart before filling it. */
    val autoEmptyFirst: Boolean = false
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
        val DELIVERY_PLACE = stringPreferencesKey("deliveryPlace")
        val SKIPPED_LOGIN = stringSetPreferencesKey("skippedLogin")
        val SKIPPED_LOCATION = stringSetPreferencesKey("skippedLocation")
        val AUTO_MODE = booleanPreferencesKey("autoMode")
        val AUTO_EMPTY_FIRST = booleanPreferencesKey("autoEmptyFirst")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                selectedProviders = prefs[Keys.SELECTED] ?: AppSettings.DEFAULT_SELECTED,
                gridColumns = (prefs[Keys.GRID_COLUMNS] ?: 2).coerceIn(1, 2),
                gridHeightDp = (prefs[Keys.GRID_HEIGHT] ?: 500)
                    .coerceIn(AppSettings.MIN_GRID_HEIGHT, AppSettings.MAX_GRID_HEIGHT),
                customProviders = CustomProviderCodec.decodeAll(prefs[Keys.CUSTOM_PROVIDERS] ?: emptySet()),
                deliveryPlace = DeliveryPlace.decode(prefs[Keys.DELIVERY_PLACE]),
                skippedLogin = prefs[Keys.SKIPPED_LOGIN] ?: emptySet(),
                skippedLocation = prefs[Keys.SKIPPED_LOCATION] ?: emptySet(),
                autoMode = prefs[Keys.AUTO_MODE] ?: false,
                autoEmptyFirst = prefs[Keys.AUTO_EMPTY_FIRST] ?: false
            )
        }

    suspend fun save(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.SELECTED] = settings.selectedProviders
            prefs[Keys.GRID_COLUMNS] = settings.gridColumns
            prefs[Keys.GRID_HEIGHT] = settings.gridHeightDp
            prefs[Keys.CUSTOM_PROVIDERS] = settings.customProviders.map(CustomProviderCodec::encode).toSet()
            val place = settings.deliveryPlace
            if (place != null) prefs[Keys.DELIVERY_PLACE] = place.encode() else prefs.remove(Keys.DELIVERY_PLACE)
            prefs[Keys.SKIPPED_LOGIN] = settings.skippedLogin
            prefs[Keys.SKIPPED_LOCATION] = settings.skippedLocation
            prefs[Keys.AUTO_MODE] = settings.autoMode
            prefs[Keys.AUTO_EMPTY_FIRST] = settings.autoEmptyFirst
        }
    }
}
