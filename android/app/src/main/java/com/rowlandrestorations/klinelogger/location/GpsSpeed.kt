package com.rowlandrestorations.klinelogger.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone GPS speed in mph, like the iOS app's GPSSpeedManager. The ECU has no working speed channel (the
 * 0x0D / 0x6B candidates stayed flat through a 0-42.8 mph water test), so this is the reference speed for
 * Live Data and the research log. Uses the platform GPS provider: no Google Play services needed.
 */
class GpsSpeed(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _mph = MutableStateFlow<Double?>(null)
    private val _status = MutableStateFlow(Status.OFF)
    private var lastFix = 0L

    /** Null until there's a fix with a speed, and again if fixes stop coming. */
    val mph: StateFlow<Double?> = _mph.asStateFlow()
    val status: StateFlow<Status> = _status.asStateFlow()

    enum class Status { OFF, NO_PERMISSION, GPS_OFF, WAITING, FIX }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFix = SystemClock.elapsedRealtime()
            _mph.value = if (location.hasSpeed()) location.speed * MPS_TO_MPH else null
            _status.value = Status.FIX
        }

        override fun onProviderDisabled(provider: String) {
            _mph.value = null
            _status.value = Status.GPS_OFF
        }

        override fun onProviderEnabled(provider: String) {
            _status.value = Status.WAITING
        }

        @Deprecated("Needed before Android 10")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (_status.value == Status.WAITING || _status.value == Status.FIX) return
        if (!hasPermission()) {
            _status.value = Status.NO_PERMISSION
            return
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) _status.value = Status.GPS_OFF
        else _status.value = Status.WAITING
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            _status.value = Status.NO_PERMISSION
        } catch (e: IllegalArgumentException) {
            _status.value = Status.GPS_OFF      // no GPS hardware
        }
    }

    /** Drops a speed that's gone stale (lost the sky view), so logs show a gap instead of a frozen number. */
    fun expireStale() {
        if (_mph.value != null && SystemClock.elapsedRealtime() - lastFix > STALE_MS) {
            _mph.value = null
            _status.value = Status.WAITING
        }
    }

    fun stop() {
        lm.removeUpdates(listener)
        _mph.value = null
        _status.value = Status.OFF
    }

    private companion object {
        const val MPS_TO_MPH = 2.2369362921
        const val STALE_MS = 5_000L
    }
}
