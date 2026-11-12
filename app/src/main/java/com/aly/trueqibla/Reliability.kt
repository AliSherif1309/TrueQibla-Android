package com.aly.trueqibla

import kotlin.math.*

/** Conservative validity policies; all ages use elapsed realtime, never the wall clock. */
object Reliability {
    const val LOCATION_MAX_AGE_MS = 10 * 60_000L
    const val SENSOR_MAX_AGE_MS = 1500L
    const val HOLD_MAX_AGE_MS = 30_000L

    /** East declination is positive: true azimuth = magnetic azimuth + declination. */
    fun trueHeading(magnetic: Double, declination: Double) = Astro.norm360(magnetic + declination)

    fun coordinates(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    fun fresh(now: Long, at: Long, maxAge: Long) = at > 0 && now - at in 0..maxAge

    fun acceptLocation(age: Long, accuracy: Float, previousAge: Long, previousAccuracy: Float): Boolean {
        if (age !in 0..LOCATION_MAX_AGE_MS || !accuracy.isFinite() || accuracy <= 0) return false
        // Prefer accuracy only within a short time window; never pin the user to an old GPS fix.
        return previousAccuracy <= 0 || age <= previousAge && (previousAge > 30_000 ||
            accuracy <= max(30f, previousAccuracy * 1.5f))
    }

    /** Screen top edge projected onto the horizontal plane. Require face up, within 45 degrees. */
    fun heading(r: FloatArray, displayRotation: Int): Double? {
        if (r.size < 9 || r.any { !it.isFinite() } || r[8] < 0.7071f) return null
        val (east, north) = when (displayRotation) {
            1 -> -r[0].toDouble() to -r[3].toDouble()
            2 -> -r[1].toDouble() to -r[4].toDouble()
            3 -> r[0].toDouble() to r[3].toDouble()
            else -> r[1].toDouble() to r[4].toDouble()
        }
        if (hypot(east, north) < 0.5) return null
        return Astro.norm360(Math.toDegrees(atan2(east, north)))
    }

    /** Location error can dominate direction near the destination or its antipode. */
    fun directionDefined(lat: Double, lon: Double, accuracyM: Float): Boolean {
        if (!coordinates(lat, lon) || abs(lat) >= 89.9) return false
        val distance = Astro.distanceKm(lat, lon) * 1000
        val singularDistance = min(distance, PI * 6_371_008.8 - distance)
        return singularDistance > max(20.0, accuracyM.coerceAtLeast(0f) * 10.0)
    }
}
