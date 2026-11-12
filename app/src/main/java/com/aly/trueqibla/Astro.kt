package com.aly.trueqibla

import kotlin.math.*

/** Qibla, distance and sun position. Spherical great-circle convention; regression tests are in src/test. */
object Astro {
    const val KAABA_LAT = 21.422487
    const val KAABA_LON = 39.826206
    private const val D2R = PI / 180.0
    private const val R2D = 180.0 / PI

    fun norm360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0
    fun norm180(x: Double): Double { val n = norm360(x); return if (n > 180.0) n - 360.0 else n }

    /** Great-circle bearing from (lat, lon) to the Kaaba, degrees clockwise from TRUE north. */
    fun qibla(lat: Double, lon: Double): Double {
        if (!Reliability.coordinates(lat, lon) || abs(lat) == 90.0) return Double.NaN
        val p1 = lat * D2R
        val p2 = KAABA_LAT * D2R
        val dl = (KAABA_LON - lon) * D2R
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        if (hypot(x, y) < 1e-12) return Double.NaN // coincident or antipodal: no unique azimuth
        return norm360(atan2(y, x) * R2D)
    }

    fun distanceKm(lat: Double, lon: Double): Double {
        if (!Reliability.coordinates(lat, lon)) return Double.NaN
        val r = 6371.0088
        val dp = (KAABA_LAT - lat) * D2R
        val dl = (KAABA_LON - lon) * D2R
        val a = sin(dp / 2).pow(2) + cos(lat * D2R) * cos(KAABA_LAT * D2R) * sin(dl / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private fun jd(ms: Long) = ms / 86400000.0 + 2440587.5

    private fun gmst(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        return norm360(280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000.0)
    }

    /** Apparent right ascension and declination of the sun, degrees (Meeus / NOAA, ~0.01°). */
    private fun sunEq(jd: Double): Pair<Double, Double> {
        val t = (jd - 2451545.0) / 36525.0
        val l0 = norm360(280.46646 + t * (36000.76983 + 0.0003032 * t))
        val m = (357.52911 + t * (35999.05029 - 0.0001537 * t)) * D2R
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val om = (125.04 - 1934.136 * t) * D2R
        val lam = (l0 + c - 0.00569 - 0.00478 * sin(om)) * D2R
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = (eps0 + 0.00256 * cos(om)) * D2R
        val ra = norm360(atan2(cos(eps) * sin(lam), cos(lam)) * R2D)
        val dec = asin(sin(eps) * sin(lam)) * R2D
        return Pair(ra, dec)
    }

    /** Sun azimuth/altitude in degrees. */
    fun sun(ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val j = jd(ms)
        val (ra, decDeg) = sunEq(j)
        val dec = decDeg * D2R
        val h = (gmst(j) + lon - ra) * D2R
        val p = lat * D2R
        val alt = asin(sin(p) * sin(dec) + cos(p) * cos(dec) * cos(h)) * R2D
        val az = atan2(-cos(dec) * sin(h), sin(dec) * cos(p) - cos(dec) * cos(h) * sin(p)) * R2D
        return Pair(norm360(az), alt)
    }

    /**
     * The two moments in a year when the sun passes (almost exactly) over the Kaaba (رصد القبلة):
     * the day the sun's declination equals Makkah's latitude, at Makkah's solar noon.
     * At that minute every vertical shadow, wherever the sun is up, points straight away from the Qibla.
     */
    fun sunOverKaaba(year: Int): List<Long> {
        val out = ArrayList<Long>()
        val jan1 = java.time.LocalDate.of(year, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        var prevT = 0L
        var prevDiff = Double.NaN
        for (day in 90..250) {
            var t = jan1 + day * 86400000L + ((12.0 - KAABA_LON / 15.0) * 3600000.0).toLong()
            repeat(4) {
                val j = jd(t)
                val (ra, _) = sunEq(j)
                val h = norm180(gmst(j) + KAABA_LON - ra)
                t -= (h / 360.9856 * 86400000.0).toLong()
            }
            val diff = sunEq(jd(t)).second - KAABA_LAT
            if (!prevDiff.isNaN() && (prevDiff < 0) != (diff < 0)) out.add(if (abs(prevDiff) < abs(diff)) prevT else t)
            prevT = t; prevDiff = diff
        }
        return out
    }

    /** Next sun-over-Kaaba moments (including one that happened in the last [graceMs]). */
    fun nextSunOverKaaba(now: Long, graceMs: Long = 0L): List<Long> {
        val y = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).year
        return (sunOverKaaba(y) + sunOverKaaba(y + 1)).filter { it > now - graceMs }.take(2)
    }

    /** Point along the great circle from (lat1, lon1) to (lat2, lon2); f = 0..1. Returns (lat, lon). */
    fun greatCirclePoint(lat1: Double, lon1: Double, lat2: Double, lon2: Double, f: Double): Pair<Double, Double> {
        val p1 = lat1 * D2R; val l1 = lon1 * D2R; val p2 = lat2 * D2R; val l2 = lon2 * D2R
        val x1 = cos(p1) * cos(l1); val y1 = cos(p1) * sin(l1); val z1 = sin(p1)
        val x2 = cos(p2) * cos(l2); val y2 = cos(p2) * sin(l2); val z2 = sin(p2)
        val d = acos((x1 * x2 + y1 * y2 + z1 * z2).coerceIn(-1.0, 1.0))
        if (d < 1e-9) return Pair(lat1, lon1)
        val a = sin((1 - f) * d) / sin(d); val b = sin(f * d) / sin(d)
        val x = a * x1 + b * x2; val y = a * y1 + b * y2; val z = a * z1 + b * z2
        return Pair(atan2(z, sqrt(x * x + y * y)) * R2D, atan2(y, x) * R2D)
    }
}
