package com.opencompares.android.sites

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class LoginState { In, Out, Unknown }

enum class LocationState {
    /** The shopper chose an address on this site. */
    Set,
    /** The site picked a default city by itself; prices may be for another area. */
    Guessed,
    Missing,
    Unknown
}

/** What a marketplace page says about the shopper's login and delivery location. */
data class SiteStatus(
    val login: LoginState,
    val location: LocationState,
    /** The area or address the site shows, if any. */
    val label: String?,
    val lat: Double?,
    val lon: Double?,
    /** The page is a "log in to continue" wall. */
    val loginWall: Boolean,
    /** Items in the site's cart, when the app can tell. */
    val cartCount: Int? = null
) {
    /** Kilometres between where the site delivers and [lat], [lon], when both are known. */
    fun distanceKmFrom(lat: Double, lon: Double): Double? {
        val siteLat = this.lat ?: return null
        val siteLon = this.lon ?: return null
        return distanceKm(siteLat, siteLon, lat, lon)
    }

    companion object {
        val UNKNOWN = SiteStatus(LoginState.Unknown, LocationState.Unknown, null, null, null, false)

        fun parse(json: JSONObject): SiteStatus = SiteStatus(
            login = when (json.optString("login")) {
                "in" -> LoginState.In
                "out" -> LoginState.Out
                else -> LoginState.Unknown
            },
            location = when (json.optString("location")) {
                "set" -> LocationState.Set
                "guessed" -> LocationState.Guessed
                "missing" -> LocationState.Missing
                else -> LocationState.Unknown
            },
            label = if (json.isNull("label")) null else json.optString("label").trim().ifEmpty { null },
            lat = json.optDouble("lat").takeIf { !it.isNaN() },
            lon = json.optDouble("lon").takeIf { !it.isNaN() },
            loginWall = json.optBoolean("loginWall", false),
            cartCount = if (json.isNull("cartCount")) null else json.optInt("cartCount", 0)
        )
    }
}

/**
 * This reading, with anything it couldn't tell filled in from [known]: a site's search or
 * cart page often shows neither the login nor the address (Amazon Now shows them only on
 * its home page), and that is no reason to forget them.
 */
fun SiteStatus.over(known: SiteStatus?): SiteStatus {
    if (known == null) return this
    val keepPlace = location == LocationState.Unknown
    return copy(
        login = if (login == LoginState.Unknown) known.login else login,
        location = if (keepPlace) known.location else location,
        label = if (keepPlace) known.label else label,
        lat = if (keepPlace) known.lat else lat,
        lon = if (keepPlace) known.lon else lon
    )
}

/** Remembers each site's last known login and address across app restarts, on the phone only. */
class SiteStatusMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("site_status", Context.MODE_PRIVATE)

    fun load(key: String): SiteStatus? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { SiteStatus.parse(JSONObject(raw)) }.getOrNull()
    }

    fun save(key: String, s: SiteStatus) {
        val json = JSONObject()
            .put("login", when (s.login) { LoginState.In -> "in"; LoginState.Out -> "out"; else -> "unknown" })
            .put("location", s.location.name.lowercase())
            .put("label", s.label ?: JSONObject.NULL)
            .put("lat", s.lat ?: JSONObject.NULL)
            .put("lon", s.lon ?: JSONObject.NULL)
        prefs.edit().putString(key, json.toString()).apply()
    }
}

/** Distance between two points on Earth, in kilometres. */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/** A site delivering further than this from the shopper is probably set to another address. */
const val FAR_AWAY_KM = 3.0

/** Runs the bundled status script in a pane. Everything stays on the phone. */
class SiteStatusReader(context: Context) {
    private val appContext = context.applicationContext
    private val script: String by lazy {
        appContext.assets.open("site_status.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    suspend fun read(view: WebView?): SiteStatus? {
        if (view == null) return null
        val js = withContext(Dispatchers.IO) { script }
        val raw = withTimeoutOrNull(5_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String> { cont ->
                    view.evaluateJavascript(js) { result -> if (cont.isActive) cont.resume(result ?: "null") }
                }
            }
        } ?: return null
        return try {
            val decoded = JSONTokener(raw).nextValue() as? String ?: return null
            SiteStatus.parse(JSONObject(decoded))
        } catch (e: Exception) {
            null
        }
    }
}
