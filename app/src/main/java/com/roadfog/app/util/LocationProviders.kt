package com.roadfog.app.util

import android.location.LocationManager
import android.location.Location

object LocationProviders {
    /** Fused can combine satellite and other inputs; its name alone does not determine accuracy. */
    fun preferred(locationManager: LocationManager): String? =
        candidates(locationManager).firstOrNull()

    /** Ordered alternatives used only if registering the preferred provider fails. */
    fun fallback(locationManager: LocationManager): List<String> =
        candidates(locationManager).drop(1)

    /** Request both precise sources by default, with network only when neither registers. */
    fun registerHighAccuracy(locationManager: LocationManager, register: (String) -> Boolean) {
        val enabled = candidates(locationManager)
        var registered = false
        enabled.filter { it != LocationManager.NETWORK_PROVIDER }.forEach {
            if (register(it)) registered = true
        }
        if (!registered && LocationManager.NETWORK_PROVIDER in enabled) register(LocationManager.NETWORK_PROVIDER)
    }

    /** Compare near-contemporaneous fixes by reported accuracy, without holding a moving car stale. */
    fun isBetterFix(candidate: Location, previous: Location?): Boolean {
        if (previous == null) return true
        val elapsed = if (candidate.elapsedRealtimeNanos > 0 && previous.elapsedRealtimeNanos > 0) {
            (candidate.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000L
        } else candidate.time - previous.time
        if (elapsed < 0) return false
        if (elapsed > 2_000L) return true
        if (!candidate.hasAccuracy()) return false
        if (!previous.hasAccuracy()) return true
        if (elapsed == 0L) return candidate.accuracy < previous.accuracy
        return candidate.provider == previous.provider || candidate.accuracy <= previous.accuracy
    }

    private fun candidates(locationManager: LocationManager): List<String> {
        val enabled = locationManager.getProviders(true).toSet()
        val fused = LocationManager.FUSED_PROVIDER
        return buildList(3) {
            if (fused in enabled) add(fused)
            if (LocationManager.GPS_PROVIDER in enabled) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in enabled) add(LocationManager.NETWORK_PROVIDER)
        }.distinct()
    }
}
