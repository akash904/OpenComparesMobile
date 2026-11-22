package com.opencompares.android

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.opencompares.android.data.DeliveryPlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object AppLocation : LocationListener {
    private var locationManager: LocationManager? = null
    private val applicationScope = CoroutineScope(Dispatchers.Main)

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation

    /** A place the shopper typed in. When set, pages get it instead of the phone's location. */
    @Volatile var manualPlace: DeliveryPlace? = null

    /** Where the shopper wants delivery: the typed-in place, else the phone's location. */
    fun deliveryPoint(): Triple<Double, Double, Float>? {
        manualPlace?.let { return Triple(it.lat, it.lon, 100f) }
        val loc = _lastLocation.value ?: return null
        return Triple(loc.latitude, loc.longitude, loc.accuracy)
    }

    fun initialize(context: Context) {
        if (locationManager != null) return
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        start(context)
    }

    fun start(context: Context) {
        val lm = locationManager ?: (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { locationManager = it }
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        requestUpdates(lm)
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(lm: LocationManager) {
        // Try to seed with last known locations
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p)
                if (loc != null && (_lastLocation.value == null || loc.time > (_lastLocation.value?.time ?: 0L))) {
                    _lastLocation.value = loc
                }
            } catch (_: SecurityException) { }
        }

        // Request ongoing updates with reasonable intervals
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 10f, this, Looper.getMainLooper())
            }
        } catch (_: Exception) { }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 20_000L, 50f, this, Looper.getMainLooper())
            }
        } catch (_: Exception) { }
    }

    fun stop() {
        try { locationManager?.removeUpdates(this) } catch (_: Exception) { }
    }

    override fun onLocationChanged(location: Location) {
        applicationScope.launch { _lastLocation.value = location }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }

    override fun onProviderEnabled(provider: String) { }

    override fun onProviderDisabled(provider: String) { }
}


