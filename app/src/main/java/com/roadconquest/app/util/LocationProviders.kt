package com.roadconquest.app.util

import android.location.LocationManager
import android.location.Location

object LocationProviders {
    /** Fused can combine satellite and other inputs; its name alone does not determine accuracy. */
    fun preferred(locationManager: LocationManager): String? =
        candidates(locationManager).firstOrNull()

    /** Ordered alternatives used only if registering the preferred provider fails. */
    fun fallback(locationManager: LocationManager): List<String> =
        candidates(locationManager).drop(1)

    /**
     * Register only one provider at a time. The platform fused provider already combines GPS
     * and other inputs, so also keeping a separate GPS request active duplicates high-power
     * location work on devices that expose both. Fall through to GPS/network only when the
     * preferred provider cannot be registered.
     */
    fun registerHighAccuracy(locationManager: LocationManager, register: (String) -> Boolean) {
        for (provider in candidates(locationManager)) {
            if (register(provider)) return
        }
    }

    /** A baseline must come from a fix produced after the current location request began. */
    fun isFixSince(location: Location, requestElapsedNanos: Long, requestWallMillis: Long): Boolean {
        val fixElapsedNanos = location.elapsedRealtimeNanos
        return if (fixElapsedNanos > 0L && requestElapsedNanos > 0L) {
            fixElapsedNanos >= requestElapsedNanos
        } else {
            location.time > 0L && location.time >= requestWallMillis
        }
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
