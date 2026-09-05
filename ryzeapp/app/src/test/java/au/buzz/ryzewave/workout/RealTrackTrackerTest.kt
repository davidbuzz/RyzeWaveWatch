package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the first real outdoor walk (the 146-fix Pixel fixture behind [RealTrack]) through the real
 * [DefaultGpsDistanceTracker] with default thresholds and prints the distance it produces, next to the 155.2 m the
 * phone stored on the day (build 6 rules: last speed × whole interval, any first fix as the anchor).
 *
 * The walk was paused at about +114 s and resumed at +118 s (the three missing fixes and the +117.8 s fix stored
 * as accepted with an unchanged running total are the controller's `markGap()` on resume), so the replay calls
 * `markGap()` before that fix the way the controller did; without it the numbers are printed too.
 *
 * The true length of the loop is unknown (Buzz has not measured it), so the assertions are regression guards on
 * the algorithm, not a truth check: the phantom hop from the 52 m first fix (about 45 m off the loop) must be gone,
 * so the result is below the stored figure, and the walk must not collapse.
 */
class RealTrackTrackerTest {

    private class Replay(val name: String, val t: DefaultGpsDistanceTracker, val maxCumulativeDiffM: Double) {
        override fun toString() = "[$name] fixes=146 accepted=${t.acceptedCount} rejAccuracy=${t.rejectedAccuracyCount} " +
            "rejJitter=${t.rejectedJitterCount} rejSpike=${t.rejectedSpikeCount} deferredFirst=${t.deferredFirstFixCount} " +
            "reAnchored=${t.reAnchoredCount} distance=%.1f m (stored on the phone %.1f m, max |cumulative diff| %.1f m)"
                .format(t.distanceMeters, RealTrack.workout.distanceMeters, maxCumulativeDiffM)
    }

    private fun replay(name: String, mimicPause: Boolean): Replay {
        val t = DefaultGpsDistanceTracker()
        var maxDiff = 0.0
        for (p in RealTrack.pixelOutdoorWalk()) {
            if (mimicPause && p.time == RESUME_FIX_TIME) t.markGap()
            t.addFix(p.time, p.lat, p.lon, p.accuracyM, p.speedMps)
            p.cumulativeM?.let { maxDiff = maxOf(maxDiff, kotlin.math.abs(t.distanceMeters - it)) }
        }
        val r = Replay(name, t, maxDiff)
        println("REAL_TRACK $r")
        return r
    }

    @Test
    fun pixelWalkReplayedThroughTheTracker() {
        val r = replay("pixel walk, pause mimicked", mimicPause = true)
        replay("pixel walk, no markGap", mimicPause = false)
        val stored = RealTrack.workout.distanceMeters
        assertTrue("$r: the 52 m first fix must no longer anchor the walk 45 m off the loop", r.t.distanceMeters < stored - 10.0)
        assertTrue("$r: the walk must not collapse", r.t.distanceMeters > 100.0)
        assertTrue("$r: expected 120..150 m (build 14 rules give 136.4 m; the truth is unknown)", r.t.distanceMeters in 120.0..150.0)
        assertEquals(2, r.t.rejectedAccuracyCount)                 // the 132 m and 67 m fixes, unchanged
        assertTrue("first fix (52 m) deferred", r.t.deferredFirstFixCount >= 1)
    }

    companion object {
        /** The +117.8 s fix: first fix after the pause, stored as accepted with the running total unchanged. */
        const val RESUME_FIX_TIME = 1_788_561_434_028L
    }
}
