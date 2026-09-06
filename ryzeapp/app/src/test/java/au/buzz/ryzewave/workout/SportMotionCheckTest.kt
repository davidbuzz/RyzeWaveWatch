package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.workout.SportMotionCheck.Expectation
import au.buzz.ryzewave.workout.SportMotionCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * The GPS-versus-declared-sport check. Its point is that a stationary reading is evidence: it confirms a rowing
 * machine and contradicts an outdoor run, and the same test tells a genuine erg session from a workout somebody
 * forgot to stop.
 */
class SportMotionCheckTest {

    private val lat0 = -27.0
    private val lon0 = 153.0
    private val metresPerDegLat = 111_320.0
    private val metresPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))

    /** [n] fixes jittering inside a [jitterM] circle, one per second. */
    private fun cluster(n: Int, jitterM: Double, accuracy: Float = 5f): List<TrackPoint> = List(n) { i ->
        val a = i * 2.399
        val r = jitterM * ((i % 7) / 6.0)
        TrackPoint(
            1L, i * 1000L,
            lat0 + r * kotlin.math.sin(a) / metresPerDegLat,
            lon0 + r * cos(a) / metresPerDegLon,
            accuracy, 0.1f, null, accepted = true, cumulativeM = 0.0,
        )
    }

    /** A straight line of [n] fixes, [stepM] apart, one per second. */
    private fun line(n: Int, stepM: Double): List<TrackPoint> = List(n) { i ->
        TrackPoint(1L, i * 1000L, lat0 + i * stepM / metresPerDegLat, lon0, 5f, stepM.toFloat(), null, accepted = true, cumulativeM = i * stepM)
    }

    private val tenMinutes = 10 * 60_000L

    @Test
    fun expectationsComeFromTheSportTables() {
        assertEquals(Expectation.STAYS_PUT, SportMotionCheck.expectation(0x29))     // Rower
        assertEquals(Expectation.STAYS_PUT, SportMotionCheck.expectation(0x12))     // Spinning
        assertEquals(Expectation.STAYS_PUT, SportMotionCheck.expectation(0x15))     // Treadmill
        assertEquals(Expectation.STAYS_PUT, SportMotionCheck.expectation(0x07))     // Tennis: a court, not a road
        assertEquals(Expectation.COVERS_GROUND, SportMotionCheck.expectation(0x01)) // Outdoor Running
        assertEquals(Expectation.COVERS_GROUND, SportMotionCheck.expectation(0x02)) // Cycling
        assertEquals(Expectation.EITHER, SportMotionCheck.expectation(0x0C))        // Baseball: barely moves
        assertEquals(Expectation.EITHER, SportMotionCheck.expectation(0x04))        // Swimming: phone is on the shore
        assertEquals(Expectation.EITHER, SportMotionCheck.expectation(null))
    }

    @Test
    fun aRowingMachineSessionThatStayedPutIsConfirmed() {
        val r = SportMotionCheck.check(0x29, cluster(600, 4.0), tenMinutes * 4, 0.0)
        assertEquals(r.message, Verdict.CONFIRMED_STATIONARY, r.verdict)
        assertTrue(r.message, r.radiusM < SportMotionCheck.STATIONARY_RADIUS_M)
        assertTrue(r.message, r.message.contains("stayed put"))
    }

    @Test
    fun aSpinningSessionThatTravelledTwoKilometresIsMislabelled() {
        val pts = line(600, 3.3)   // 2 km at 3.3 m/s
        val r = SportMotionCheck.check(0x12, pts, tenMinutes, 1980.0)
        assertEquals(r.message, Verdict.MISLABELLED_MOVED, r.verdict)
        assertTrue(r.message, r.message.contains("mislabelled"))
    }

    @Test
    fun anOutdoorRunThatCoveredGroundIsConfirmed() {
        val r = SportMotionCheck.check(0x01, line(600, 2.5), tenMinutes, 1500.0)
        assertEquals(r.message, Verdict.CONFIRMED_MOVING, r.verdict)
    }

    @Test
    fun anOutdoorRunThatNeverLeftATwentyMetreCircleIsFlagged() {
        // The forgotten-workout case, and the treadmill-with-the-wrong-sport case.
        val r = SportMotionCheck.check(0x01, cluster(600, 6.0), tenMinutes, 0.0)
        assertEquals(r.message, Verdict.MISLABELLED_STILL, r.verdict)
        assertTrue(r.message, r.message.contains("treadmill"))
    }

    @Test
    fun aShortStillRunIsNotJudged() {
        // Three minutes standing at the start line is normal; do not accuse anyone yet.
        val r = SportMotionCheck.check(0x01, cluster(150, 6.0), 3 * 60_000L, 0.0)
        assertEquals(r.message, Verdict.INCONCLUSIVE, r.verdict)
    }

    @Test
    fun withoutEnoughUsableFixesNothingIsClaimed() {
        val r = SportMotionCheck.check(0x29, cluster(10, 4.0), tenMinutes, 0.0)
        assertEquals(Verdict.NO_GPS, r.verdict)
        val poor = cluster(600, 4.0, accuracy = 80f)
        assertEquals("80 m fixes are not usable", Verdict.NO_GPS, SportMotionCheck.check(0x29, poor, tenMinutes, 0.0).verdict)
    }

    @Test
    fun pausedFixesAreIgnored() {
        // A rower who paused, walked to the water fountain and came back: the paused excursion must not count.
        val still = cluster(600, 4.0)
        val excursion = line(60, 2.0).map { it.copy(time = 700_000L + it.time, paused = true) }
        val r = SportMotionCheck.check(0x29, still + excursion, tenMinutes * 4, 0.0)
        assertEquals(r.message, Verdict.CONFIRMED_STATIONARY, r.verdict)
    }

    @Test
    fun sportsThatImplyNothingNeverContradict() {
        assertEquals(Verdict.INCONCLUSIVE, SportMotionCheck.check(0x0C, cluster(600, 4.0), tenMinutes, 0.0).verdict)
        assertEquals(Verdict.CONFIRMED_MOVING, SportMotionCheck.check(0x0C, line(600, 2.5), tenMinutes, 1500.0).verdict)
    }

    @Test
    fun radiusIsMeasuredFromTheCentroid() {
        assertEquals(0.0, SportMotionCheck.radiusM(emptyList()), 1e-9)
        val r = SportMotionCheck.radiusM(cluster(100, 10.0))
        assertTrue("radius $r should be about the jitter", r in 5.0..12.0)
        val l = SportMotionCheck.radiusM(line(101, 1.0))   // 100 m line -> radius ~50 m
        assertTrue("line radius $l", l in 45.0..55.0)
    }
}
