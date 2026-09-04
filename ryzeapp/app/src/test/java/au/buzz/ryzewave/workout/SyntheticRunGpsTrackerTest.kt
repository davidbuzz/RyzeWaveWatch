package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Feeds [DefaultGpsDistanceTracker] (the real class, default thresholds) a synthetic 20-minute run and checks
 * the accumulated distance against the 3600 m truth. Every variant prints its measured numbers so the
 * behaviour of the ones the tracker is *designed* to reject (accuracy 25 m > maxAccuracyM 20 m) is reported
 * rather than hidden.
 */
class SyntheticRunGpsTrackerTest {

    private class Result(val name: String, val t: DefaultGpsDistanceTracker, val fixes: Int, val finalPace: Double, val finalSpeed: Double, val minPaceAfter60s: Double, val zeroPaceTicksAfter60s: Int) {
        val errorPct get() = (t.distanceMeters - SyntheticRun.TRUE_DISTANCE_M) / SyntheticRun.TRUE_DISTANCE_M * 100.0
        override fun toString() = "[$name] fixes=$fixes accepted=${t.acceptedCount} rejAccuracy=${t.rejectedAccuracyCount} " +
            "rejJitter=${t.rejectedJitterCount} rejSpike=${t.rejectedSpikeCount} distance=%.1f m (truth 3600 m, error %.2f%%) ".format(t.distanceMeters, errorPct) +
            "finalPace=%.1f s/km finalSpeed=%.2f m/s minPaceAfter60s=%.1f zeroPaceFixesAfter60s=%d".format(finalPace, finalSpeed, minPaceAfter60s, zeroPaceTicksAfter60s)
    }

    private fun run(name: String, fixes: List<SyntheticFix>): Result {
        val t = DefaultGpsDistanceTracker()
        var minPace = Double.MAX_VALUE
        var zeroPace = 0
        for (f in fixes) {
            t.addFix(f.time, f.lat, f.lon, f.accuracyM, f.speedMps)
            if (f.time - SyntheticRun.T0 >= 60_000L) {
                if (t.paceSecPerKm == 0.0) zeroPace++ else minPace = minOf(minPace, t.paceSecPerKm)
            }
        }
        val r = Result(name, t, fixes.size, t.paceSecPerKm, t.speedMps, if (minPace == Double.MAX_VALUE) 0.0 else minPace, zeroPace)
        println("SYNTHETIC_RUN $r")
        return r
    }

    private fun assertWithin5Percent(r: Result) {
        assertTrue("$r: expected within 5% of 3600 m", abs(r.errorPct) <= 5.0)
    }

    /** Variant A: accuracy 6 m, Doppler 3.0 m/s: the normal outdoor case, must be within 5 %. */
    @Test
    fun accuracy6mWithDopplerIsWithin5Percent() {
        val r = run("A accuracy=6m doppler=3.0", SyntheticRun.fixes(accuracyM = 6f, dopplerSpeedMps = 3.0f))
        assertEquals(1200, r.t.acceptedCount)                       // nothing rejected on a clean run
        assertWithin5Percent(r)
        // pace: 3.0 m/s = 333.3 s/km; the rolling window should settle close to that and never show 0 once warm
        assertTrue("pace after 60 s never 0 but was 0 on ${r.zeroPaceTicksAfter60s} fixes", r.zeroPaceTicksAfter60s == 0)
        assertEquals(333.3, r.finalPace, 25.0)
    }

    /**
     * Variant B: accuracy 25 m with Doppler speed. DefaultGpsDistanceTracker.kt line 71 drops every fix whose
     * accuracy exceeds maxAccuracyM = 20 f (line 30), so this run is expected to record NOTHING. The test
     * asserts the behaviour the code actually has and prints the numbers; it does not pretend it works.
     */
    @Test
    fun accuracy25mWithDopplerRecordsNothing_reported() {
        val r = run("B accuracy=25m doppler=3.0", SyntheticRun.fixes(accuracyM = 25f, dopplerSpeedMps = 3.0f))
        assertEquals(1200, r.t.rejectedAccuracyCount)
        assertEquals(0, r.t.acceptedCount)
        assertEquals(0.0, r.t.distanceMeters, 0.0)
        assertEquals(0.0, r.t.paceSecPerKm, 0.0)
    }

    /**
     * Variant C: accuracy 8 m, receiver reports no speed (0.0). Rule 2 (reported < 1.0 m/s and
     * d < max(1.5 × max(accuracy, anchorAccuracy), 3 m)) treats every fix closer than 12 m to the anchor as
     * jitter; the anchor stays, so the distance accumulates in >= 12 m hops (about 3600 / 13.5 = 270 of them).
     * The 5 % band is asserted because the class KDoc promises slow real movement still accumulates; the
     * numbers are printed either way. (With the old radius of 1 × accuracy = 8 m the hops averaged 9.6 m and
     * the run came out 5.19 % long.)
     */
    @Test
    fun accuracy8mWithoutDopplerAccumulatesInHops() {
        val r = run("C accuracy=8m doppler=0.0", SyntheticRun.fixes(accuracyM = 8f, dopplerSpeedMps = 0.0f))
        assertTrue("some fixes must be absorbed as jitter", r.t.rejectedJitterCount > 0)
        assertTrue("accepted ${r.t.acceptedCount}", r.t.acceptedCount in 200..400)
        assertWithin5Percent(r)
    }

    /**
     * Variant D: accuracy 6 m, Doppler 3.0, but fixes 600..629 (30 s) are missing. markGap() is deliberately
     * not called for an outage (lines 148-153): the first fix after the gap is 90 m from the anchor over 30 s,
     * implied 3 m/s < maxSpeedMps 12 and 90 m < allowed = max(3.0, 2.5) * 30 + 6 + 6 = 102 m (line 105), so
     * the straight line bridges the gap and the distance stays within 5 %.
     */
    @Test
    fun thirtySecondGapIsBridgedWithin5Percent() {
        val r = run("D accuracy=6m doppler=3.0 gap=600..629s", SyntheticRun.fixes(accuracyM = 6f, dopplerSpeedMps = 3.0f, gapFromS = 600, gapLengthS = 30))
        assertEquals(1170, r.fixes)
        assertEquals(1170, r.t.acceptedCount)
        assertEquals(0, r.t.rejectedSpikeCount)
        assertWithin5Percent(r)
    }

    /**
     * Sensitivity of variant A to the jitter model: independent per-fix noise of sigma 0 / 0.5 / 1 / 2 / 3 m, and
     * temporally correlated noise (AR(1) phi = 0.9 / 0.98, stationary sigma 2 m) as a Kalman-smoothed provider
     * would give. Reported only (printed), no band asserted: the point is to show how much of the error is the
     * noise model. The exact 0-jitter run must be within 0.1 %.
     */
    @Test
    fun jitterSensitivityReported() {
        val exact = run("A0 accuracy=6m doppler=3.0 jitter=0", SyntheticRun.fixes(6f, 3.0f, jitterSigmaM = 0.0))
        assertEquals(3600.0, exact.t.distanceMeters, 3.6)
        for (sigma in doubleArrayOf(0.5, 1.0, 2.0, 3.0)) {
            run("A independent jitter sigma=$sigma m", SyntheticRun.fixes(6f, 3.0f, jitterSigmaM = sigma))
            run("C independent jitter sigma=$sigma m doppler=0 acc=8", SyntheticRun.fixes(8f, 0.0f, jitterSigmaM = sigma))
        }
        for (phi in doubleArrayOf(0.9, 0.98)) {
            run("A correlated jitter phi=$phi sigma=2 m", SyntheticRun.fixes(6f, 3.0f, jitterSigmaM = 2.0, phi = phi))
            run("C correlated jitter phi=$phi sigma=2 m doppler=0 acc=8", SyntheticRun.fixes(8f, 0.0f, jitterSigmaM = 2.0, phi = phi))
        }
        for (seed in longArrayOf(1L, 7L, 99L)) {
            run("A seed=$seed independent sigma=2 m", SyntheticRun.fixes(6f, 3.0f, seed = seed))
        }
    }

    /** Extra: what a slightly longer outage does (the plausibility rule 3 is the limit, not the gap length). */
    @Test
    fun longerGapsReported() {
        for (gap in intArrayOf(60, 120, 300)) {
            val r = run("D' accuracy=6m doppler=3.0 gap=600..${600 + gap - 1}s", SyntheticRun.fixes(accuracyM = 6f, dopplerSpeedMps = 3.0f, gapFromS = 600, gapLengthS = gap))
            assertEquals(0, r.t.rejectedSpikeCount)
            assertWithin5Percent(r)
        }
    }
}
