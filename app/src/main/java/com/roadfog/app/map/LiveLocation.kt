package com.roadfog.app.map

/** Monotonic expiry survives style changes without making an old fix fresh again. */
class LiveLocation {
    data class Fix(val latitude: Double, val longitude: Double, val bearing: Double, val expiresAt: Long)
    private var fix: Fix? = null

    fun update(latitude: Double, longitude: Double, bearing: Double, now: Long, ageMillis: Long): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            ageMillis !in 0 until MAX_AGE_MS
        ) return false
        val heading = if (bearing.isFinite()) ((bearing % 360.0) + 360.0) % 360.0 else 0.0
        fix = Fix(latitude, longitude, heading, now + MAX_AGE_MS - ageMillis)
        return true
    }

    fun current(now: Long): Fix? = fix?.takeIf { now < it.expiresAt }
    fun clear() { fix = null }

    companion object { const val MAX_AGE_MS = 60_000L }
}
