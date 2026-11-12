package com.aly.trueqibla

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ReliabilityTest {
    @Test fun declinationIsAddedExactlyOnceWithEastPositive() {
        assertEquals(5.0, Reliability.trueHeading(355.0, 10.0), 1e-9)
        assertEquals(345.0, Reliability.trueHeading(355.0, -10.0), 1e-9)
        assertEquals(0.0, Reliability.trueHeading(0.0, 0.0), 1e-9)
    }
    @Test fun anglesWrapAcrossNorth() {
        assertEquals(1.0, Astro.norm360(721.0), 1e-10)
        assertEquals(359.0, Astro.norm360(-1.0), 1e-10)
        assertEquals(2.0, Astro.norm180(1.0 - 359.0), 1e-10)
        assertEquals(-2.0, Astro.norm180(359.0 - 1.0), 1e-10)
    }

    @Test fun globalBearingsMatchIndependentTangentPlaneProjection() {
        // Project the destination's 3D unit vector onto local east and north.
        // Covers hemispheres, date line, high latitudes and a grid of 629 nonsingular positions.
        val kLat = Math.toRadians(Astro.KAABA_LAT)
        val kLon = Math.toRadians(Astro.KAABA_LON)
        val k = doubleArrayOf(cos(kLat) * cos(kLon), cos(kLat) * sin(kLon), sin(kLat))
        for (lat in -90..90 step 10) for (lon in -180..180 step 10) {
            if (abs(lat) == 90) continue
            val p = Math.toRadians(lat.toDouble()); val l = Math.toRadians(lon.toDouble())
            val east = -sin(l) * k[0] + cos(l) * k[1]
            val north = -sin(p) * cos(l) * k[0] - sin(p) * sin(l) * k[1] + cos(p) * k[2]
            val expected = Astro.norm360(Math.toDegrees(atan2(east, north)))
            assertEquals("$lat,$lon", expected, Astro.qibla(lat.toDouble(), lon.toDouble()), 1e-9)
        }
        assertEquals(Astro.qibla(0.0, -180.0), Astro.qibla(0.0, 180.0), 1e-9)
    }

    @Test fun cardinalBearingsAndDistance() {
        assertEquals(0.0, Astro.qibla(0.0, Astro.KAABA_LON), 1e-9)
        assertEquals(180.0, Astro.qibla(40.0, Astro.KAABA_LON), 1e-9)
        assertEquals(0.0, Astro.distanceKm(Astro.KAABA_LAT, Astro.KAABA_LON), 1e-9)
        assertEquals(PI * 6371.0088, Astro.distanceKm(-Astro.KAABA_LAT, Astro.KAABA_LON - 180), 0.001)
        // Independent common spherical reference, New York -> Kaaba.
        assertEquals(58.48, Astro.qibla(40.7128, -74.0060), 0.02)
    }

    @Test fun invalidOrNonUniqueBearingsAreNotNorth() {
        for ((lat, lon) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY,
            91.0 to 0.0, 0.0 to 181.0, 90.0 to 0.0, -90.0 to 0.0,
            Astro.KAABA_LAT to Astro.KAABA_LON, -Astro.KAABA_LAT to Astro.KAABA_LON - 180)) {
            assertTrue("$lat,$lon", Astro.qibla(lat, lon).isNaN())
        }
    }

    @Test fun locationErrorSuppressesUnstableDirections() {
        assertFalse(Reliability.directionDefined(Astro.KAABA_LAT, Astro.KAABA_LON, 3f))
        assertFalse(Reliability.directionDefined(-Astro.KAABA_LAT, Astro.KAABA_LON - 180, 3f))
        assertFalse(Reliability.directionDefined(89.95, 0.0, 3f))
        assertFalse(Reliability.directionDefined(21.43, 39.83, 200f))
        assertTrue(Reliability.directionDefined(25.2854, 51.5310, 3000f))
    }

    @Test fun freshFixEventuallyReplacesOldMoreAccurateFix() {
        assertFalse(Reliability.acceptLocation(0, 100f, 5000, 5f))
        assertTrue(Reliability.acceptLocation(0, 100f, 31_000, 5f))
        assertTrue(Reliability.acceptLocation(0, 5f, 5000, 100f))
        assertTrue(Reliability.acceptLocation(1000, 3000f, Long.MAX_VALUE, -1f))
        assertFalse(Reliability.acceptLocation(40_000, 5f, 31_000, 100f))
    }

    @Test fun invalidLocationsNeverEnterTheFilter() {
        for (acc in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f))
            assertFalse(Reliability.acceptLocation(0, acc, Long.MAX_VALUE, -1f))
        assertFalse(Reliability.acceptLocation(-1, 5f, Long.MAX_VALUE, -1f))
        assertFalse(Reliability.acceptLocation(600_001, 5f, Long.MAX_VALUE, -1f))
    }

    @Test fun timestampsRejectMissingFutureAndExpiredSamples() {
        assertFalse(Reliability.fresh(100, 0, 1500))
        assertFalse(Reliability.fresh(100, 101, 1500))
        assertTrue(Reliability.fresh(1600, 100, 1500))
        assertFalse(Reliability.fresh(1601, 100, 1500))
        assertTrue(Reliability.fresh(30_100, 100, Reliability.HOLD_MAX_AGE_MS))
        assertFalse(Reliability.fresh(30_101, 100, Reliability.HOLD_MAX_AGE_MS))
    }

    private val identity = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)

    @Test fun displayRotationUsesScreenTopIncludingNaturalLandscapeDevices() {
        assertEquals(0.0, Reliability.heading(identity, 0)!!, 1e-9)
        assertEquals(270.0, Reliability.heading(identity, 1)!!, 1e-9)
        assertEquals(180.0, Reliability.heading(identity, 2)!!, 1e-9)
        assertEquals(90.0, Reliability.heading(identity, 3)!!, 1e-9)
        val east = floatArrayOf(0f,1f,0f, -1f,0f,0f, 0f,0f,1f)
        assertEquals(90.0, Reliability.heading(east, 0)!!, 1e-9)
    }

    @Test fun uprightFaceDownAndInvalidMatricesHaveNoHeading() {
        assertNull(Reliability.heading(identity.copyOf().also { it[8] = 0f }, 0))
        assertNull(Reliability.heading(identity.copyOf().also { it[8] = -1f }, 0))
        assertNull(Reliability.heading(identity.copyOf().also { it[1] = Float.NaN }, 0))
        assertNull(Reliability.heading(floatArrayOf(), 0))
    }
}
