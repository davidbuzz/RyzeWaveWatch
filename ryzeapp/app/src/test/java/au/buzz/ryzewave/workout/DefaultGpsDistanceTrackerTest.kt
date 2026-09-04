package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class DefaultGpsDistanceTrackerTest {
    private val t = DefaultGpsDistanceTracker()
    private val lat0 = -27.4698
    private val lon0 = 153.0251

    private fun latPlus(meters: Double): Double = lat0 + meters / DEG_LAT_M
    private fun lonPlus(meters: Double): Double = lon0 + meters / (DEG_LAT_M * cos(Math.toRadians(lat0)))

    @Test
    fun haversineOneDegreeOfLatitude() {
        assertEquals(DEG_LAT_M, DefaultGpsDistanceTracker.haversineMeters(0.0, 0.0, 1.0, 0.0), 0.5)
        assertEquals(0.0, DefaultGpsDistanceTracker.haversineMeters(lat0, lon0, lat0, lon0), 0.0)
        assertEquals(100.0, DefaultGpsDistanceTracker.haversineMeters(lat0, lon0, lat0, lonPlus(100.0)), 0.05)
    }

    @Test
    fun rejectsPoorAccuracy() {
        assertFalse(t.addFix(0L, lat0, lon0, 25f, 1f))
        assertFalse(t.addFix(1000L, lat0, lon0, Float.NaN, 1f))
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(2, t.rejectedAccuracyCount)
        assertEquals(0, t.acceptedCount)
        // exactly 20 m is still fine
        assertTrue(t.addFix(2000L, lat0, lon0, 20f, 1f))
    }

    @Test
    fun firstFixAnchorsWithoutDistance() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
        assertEquals(1, t.acceptedCount)
        assertEquals(5f, t.lastAccuracyM)
    }

    @Test
    fun stationaryJitterIsIgnored() {
        t.addFix(0L, lat0, lon0, 8f, 0f)
        assertFalse(t.addFix(1000L, latPlus(2.0), lon0, 8f, 0f))      // 2 m < max(8, 3)
        assertFalse(t.addFix(2000L, latPlus(6.0), lon0, 8f, 0.2f))    // 6 m < 8 m, still nearly stationary
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(0.0, t.speedMps, 0.0)
        assertEquals(2, t.rejectedJitterCount)
    }

    @Test
    fun thresholdIsAtLeastThreeMetresAndMeasuredFromTheAnchor() {
        t.addFix(0L, lat0, lon0, 1f, 0f)
        assertFalse(t.addFix(1000L, latPlus(2.0), lon0, 1f, 0f))      // 2 m < 3 m floor
        assertTrue(t.addFix(2000L, latPlus(3.5), lon0, 1f, 0f))       // 3.5 m from the anchor
        assertEquals(3.5, t.distanceMeters, 0.05)
    }

    @Test
    fun smallMoveIsAcceptedWhenMovingAndCreditedAtTheReceiverSpeed() {
        t.addFix(0L, lat0, lon0, 8f, 1.2f)
        assertTrue(t.addFix(1000L, latPlus(1.5), lon0, 8f, 1.2f))
        assertEquals(1.2, t.distanceMeters, 0.05)      // rule 4: Doppler 1.2 m/s × 1 s, not the 1.5 m position hop
        assertEquals(1.2, t.speedMps, 1e-6)
        assertEquals(2, t.acceptedCount)
    }

    @Test
    fun dopplerCreditIsCappedByThePositionHopPlusTheAccuracy() {
        // the receiver claims 10 m/s but the position barely moves: credit at most hop + accuracy, not 10 m
        t.addFix(0L, lat0, lon0, 4f, 10f)
        assertTrue(t.addFix(1000L, latPlus(1.0), lon0, 4f, 10f))
        assertEquals(5.0, t.distanceMeters, 0.05)
    }

    @Test
    fun positionHopIsUsedWhenFixesAreMoreThanFiveSecondsApart() {
        // 6 s between fixes: the speed may have changed in between, the hop is the better estimate
        t.addFix(0L, lat0, lon0, 5f, 2f)
        assertTrue(t.addFix(6000L, latPlus(15.0), lon0, 5f, 2f))
        assertEquals(15.0, t.distanceMeters, 0.05)
    }

    @Test
    fun oneHertzJitterAtRunningSpeedIsNotIntegrated() {
        // 3 m/s due north, every fix 2 m off the line alternately left and right (a 4 m lateral zigzag)
        var d = 0.0
        for (i in 0..100) {
            val side = if (i % 2 == 0) 2.0 else -2.0
            t.addFix(i * 1000L, latPlus(3.0 * i), lonPlus(side), 6f, 3.0f)
            d = t.distanceMeters
        }
        assertEquals(300.0, d, 0.5)          // the raw hops would sum to 100 × 5 m = 500 m
        assertEquals(101, t.acceptedCount)
    }

    @Test
    fun slowMoveBeyondTheRadiusAccumulates() {
        t.addFix(0L, lat0, lon0, 5f, 0f)
        assertTrue(t.addFix(10_000L, latPlus(25.0), lon0, 5f, 0f))
        assertEquals(25.0, t.distanceMeters, 0.1)
        assertEquals(2.5, t.speedMps, 0.01)   // no receiver speed: distance / time
    }

    @Test
    fun distanceIsTheHaversineSumBetweenAcceptedFixes() {
        // 100 m legs at a plausible 3.5 m/s (one fix every 30 s)
        t.addFix(0L, lat0, lon0, 5f, 3.5f)
        t.addFix(30_000L, latPlus(100.0), lon0, 5f, 3.5f)
        t.addFix(60_000L, latPlus(100.0), lonPlus(100.0), 5f, 3.5f)
        assertFalse(t.addFix(61_000L, latPlus(100.0), lonPlus(100.0), 30f, 3.5f))   // poor fix does not count
        assertEquals(200.0, t.distanceMeters, 0.5)
        assertEquals(3, t.acceptedCount)
    }

    @Test
    fun singleFixSpikeOffThePathIsRejected() {
        // walking 1.4 m/s at 1 Hz, accuracy 8 m; one multipath spike 25 m off-track, then back on the path
        for (i in 0..9) t.addFix(i * 1000L, latPlus(1.4 * i), lon0, 8f, 1.4f)
        val before = t.distanceMeters
        assertFalse(t.addFix(10_000L, latPlus(1.4 * 10), lonPlus(25.0), 8f, 1.4f))   // 25 m in 1 s = 25 m/s
        assertEquals(1, t.rejectedSpikeCount)
        assertTrue(t.addFix(11_000L, latPlus(1.4 * 11), lon0, 8f, 1.4f))
        assertEquals(before + 2.8, t.distanceMeters, 0.1)                            // 2 s of real walking, not 50 m
    }

    @Test
    fun standingStillWithASmallReportedSpeedAddsNothing() {
        // fused provider says 0.7 m/s (hand-held under trees) while the position scatters 2 m around the truth
        t.addFix(0L, lat0, lon0, 3f, 0.7f)
        var rejected = 0
        for (i in 1..60) {
            val dx = if (i % 2 == 0) 2.0 else -2.0
            if (!t.addFix(i * 1000L, latPlus(dx), lon0, 3f, 0.7f)) rejected++
        }
        assertEquals(60, rejected)
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
    }

    @Test
    fun anchorAccuracyCountsTowardsTheJitterRadius() {
        t.addFix(0L, lat0, lon0, 20f, 0f)                    // poor but accepted anchor
        assertFalse(t.addFix(1000L, latPlus(12.0), lon0, 4f, 0f))   // 12 m < 1.5 × max(4, 20): still inside the anchor's error
        assertFalse(t.addFix(2000L, latPlus(25.0), lon0, 4f, 0f))   // 25 m < 30 m: two 20 m-accuracy positions can differ by that
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertTrue(t.addFix(10_000L, latPlus(35.0), lon0, 4f, 0f))  // 35 m > 30 m, 3.5 m/s over 10 s: real movement
        assertEquals(35.0, t.distanceMeters, 0.1)
    }

    @Test
    fun paceComesFromTheLast30Seconds() {
        // 1.5 m/s walk, one fix per second for a minute: 30 s window = 45 m, well under 100 m
        for (i in 0..60) t.addFix(i * 1000L, latPlus(1.5 * i), lon0, 5f, 1.5f)
        val expected = 1000.0 / 1.5
        assertEquals(expected, t.paceSecPerKm, expected * 0.03)
        assertEquals(90.0, t.distanceMeters, 0.5)
        assertEquals(1.5, t.speedMps, 1e-6)
    }

    @Test
    fun paceUsesTheLast100MetresWhenFast() {
        // 5 m/s: 100 m are covered in 20 s, so the window is 100 m rather than 30 s
        for (i in 0..30) t.addFix(i * 1000L, latPlus(5.0 * i), lon0, 5f, 5f)
        assertEquals(200.0, t.paceSecPerKm, 6.0)
    }

    @Test
    fun paceIsUnknownForTheFirstFewMetres() {
        t.addFix(0L, lat0, lon0, 5f, 1f)
        t.addFix(1000L, latPlus(1.0), lon0, 5f, 1f)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
    }

    @Test
    fun paceDropsToZeroAfterStopping() {
        for (i in 0..30) t.addFix(i * 1000L, latPlus(1.5 * i), lon0, 5f, 1.5f)
        assertTrue(t.paceSecPerKm > 0.0)
        val stopLat = latPlus(45.0)
        for (i in 31..80) t.addFix(i * 1000L, stopLat, lon0, 5f, 0f)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
        assertEquals(0.0, t.speedMps, 0.0)
        assertEquals(45.0, t.distanceMeters, 0.5)
    }

    @Test
    fun outOfOrderFixIsRejected() {
        t.addFix(5000L, lat0, lon0, 5f, 1f)
        assertFalse(t.addFix(4000L, latPlus(50.0), lon0, 5f, 1f))
        assertEquals(0.0, t.distanceMeters, 0.0)
    }

    @Test
    fun resetClearsEverything() {
        t.addFix(0L, lat0, lon0, 5f, 1f)
        t.addFix(1000L, latPlus(10.0), lon0, 5f, 1f)
        t.reset()
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(0.0, t.speedMps, 0.0)
        assertEquals(0, t.acceptedCount)
        assertNull(t.lastAccuracyM)
        assertTrue(t.addFix(0L, latPlus(500.0), lon0, 5f, 1f))
        assertEquals(0.0, t.distanceMeters, 0.0)
    }

    @Test
    fun markGapKeepsDistanceButDropsTheAnchor() {
        t.addFix(0L, lat0, lon0, 5f, 1f)
        t.addFix(10_000L, latPlus(10.0), lon0, 5f, 1f)
        t.markGap()
        assertEquals(10.0, t.distanceMeters, 0.05)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
        assertTrue(t.addFix(60_000L, latPlus(500.0), lon0, 5f, 1f))   // re-anchors: the walk in between is not counted
        assertEquals(10.0, t.distanceMeters, 0.05)
        assertTrue(t.addFix(70_000L, latPlus(505.0), lon0, 5f, 1f))
        assertEquals(15.0, t.distanceMeters, 0.05)
    }

    companion object {
        /** Metres per degree of latitude for the tracker's Earth radius (6 371 000 × π / 180). */
        const val DEG_LAT_M = 111_194.927
    }
}
