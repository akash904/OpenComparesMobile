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
    val loginWall: Boolean
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
            loginWall = json.optBoolean("loginWall", false)
        )
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
