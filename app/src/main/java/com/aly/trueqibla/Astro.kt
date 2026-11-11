package com.aly.trueqibla

import kotlin.math.*

/** Qibla, distance and sun position. Same formulas as the web app (tested there). */
object Astro {
    const val KAABA_LAT = 21.422487
    const val KAABA_LON = 39.826206
    private const val D2R = PI / 180.0
    private const val R2D = 180.0 / PI

    fun norm360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0
    fun norm180(x: Double): Double { val n = norm360(x); return if (n > 180.0) n - 360.0 else n }

    /** Great-circle bearing from (lat, lon) to the Kaaba, degrees clockwise from TRUE north. */
    fun qibla(lat: Double, lon: Double): Double {
        val p1 = lat * D2R
        val p2 = KAABA_LAT * D2R
        val dl = (KAABA_LON - lon) * D2R
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return norm360(atan2(y, x) * R2D)
    }

    fun distanceKm(lat: Double, lon: Double): Double {
        val r = 6371.0088
        val dp = (KAABA_LAT - lat) * D2R
        val dl = (KAABA_LON - lon) * D2R
        val a = sin(dp / 2).pow(2) + cos(lat * D2R) * cos(KAABA_LAT * D2R) * sin(dl / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /** Sun azimuth/altitude in degrees (Meeus / NOAA, ~0.01°). */
    fun sun(ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val jd = ms / 86400000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = norm360(280.46646 + t * (36000.76983 + 0.0003032 * t))
        val m = (357.52911 + t * (35999.05029 - 0.0001537 * t)) * D2R
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val om = (125.04 - 1934.136 * t) * D2R
        val lam = (l0 + c - 0.00569 - 0.00478 * sin(om)) * D2R
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = (eps0 + 0.00256 * cos(om)) * D2R
        val ra = atan2(cos(eps) * sin(lam), cos(lam)) * R2D
        val dec = asin(sin(eps) * sin(lam))
        val gmst = norm360(280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000.0)
        val h = (gmst + lon - ra) * D2R
        val p = lat * D2R
        val alt = asin(sin(p) * sin(dec) + cos(p) * cos(dec) * cos(h)) * R2D
        val az = atan2(-cos(dec) * sin(h), sin(dec) * cos(p) - cos(dec) * cos(h) * sin(p)) * R2D
        return Pair(norm360(az), alt)
    }
}
