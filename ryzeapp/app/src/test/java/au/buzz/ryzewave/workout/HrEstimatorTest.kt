package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The heart-rate estimator, replayed over two real runs (no coordinates in the fixtures):
 *
 *  - `hr_dropout_run_20260919`: the watch lost the wrist from roughly 13:30 to 19:00 into a 36-minute run and read
 *    95-113 at an unchanged pace, between stretches of 140-160. The estimator must flag that window, estimate
 *    about 145 through it, and flag nothing else — in particular not the warm-up climb or the cool-down fall.
 *  - `hr_clean_run_20260918`: a clean run. Nothing may be flagged.
 */
class HrEstimatorTest {

    private class Fixture(val points: List<TrackPoint>, val samples: List<HrSample>, val restingBpm: Int)

    private fun load(name: String): Fixture {
        val lines = checkNotNull(javaClass.classLoader!!.getResourceAsStream("$name.csv")).bufferedReader().readLines()
        val rest = lines.first { it.startsWith("# restingHr=") }.substringAfter("=").trim().toInt()
        val points = ArrayList<TrackPoint>()
        val samples = ArrayList<HrSample>()
        for (l in lines) {
            if (l.isBlank() || l.startsWith("#")) continue
            val f = l.split(",")
            when (f[0]) {
                "G" -> points += TrackPoint(
                    workoutId = 1L, time = f[1].toLong(), lat = 0.0, lon = 0.0, accuracyM = 5f,
                    speedMps = f[2].toFloat(), altitudeM = f[3].toDoubleOrNull(), accepted = true,
                    cumulativeM = null, paused = f[4] == "1",
                )
                "H" -> samples += HrSample(time = f[1].toLong(), bpm = f[2].toInt(), source = SampleSource.WORKOUT)
            }
        }
        return Fixture(points, samples, rest)
    }

    /** Per-minute: median watch bpm, median estimate, fraction flagged. */
    private fun minutes(replayed: List<HrSample>): Map<Int, Triple<Int, Int, Double>> =
        replayed.groupBy { (it.time / 60_000L).toInt() }.mapValues { (_, v) ->
            Triple(
                v.map { it.bpm }.sorted()[v.size / 2],
                v.map { it.estimate!! }.sorted()[v.size / 2],
                v.count { it.flagged }.toDouble() / v.size,
            )
        }

    @Test
    fun flagsTheDropoutAndNothingElse() {
        val f = load("hr_dropout_run_20260919")
        val out = HrEstimator.replay(f.points, f.samples, f.restingBpm)
        val m = minutes(out)
        val flaggedMinutes = m.filter { it.value.third > 0.5 }.keys.sorted()
        assertEquals("exactly the dropout", listOf(14, 15, 16, 17, 18), flaggedMinutes)
        for (min in 14..18) {
            val (watch, est, _) = m.getValue(min)
            assertTrue("minute $min: watch $watch is a dropout", watch < 115)
            assertTrue("minute $min: estimate $est should be about 145", est in 135..152)
        }
    }

    @Test
    fun theWarmUpClimbAndTheCoolDownFallAreNeverFlagged() {
        // Those ramps are the recovery data; the floor must not apply before warm-up and must lift once moving stops.
        val f = load("hr_dropout_run_20260919")
        val m = minutes(HrEstimator.replay(f.points, f.samples, f.restingBpm))
        for (min in listOf(0, 1, 2, 3)) assertEquals("warm-up minute $min", 0.0, m.getValue(min).third, 0.0)
        for (min in listOf(32, 33, 34, 35)) assertEquals("cool-down minute $min", 0.0, m.getValue(min).third, 0.0)
        // and the estimate follows the real ramps rather than pinning them to the floor
        assertTrue("estimate climbs with the wearer at minute 1", m.getValue(1).second < 115)
        assertTrue("estimate falls with the wearer at minute 35", m.getValue(35).second < 125)
    }

    @Test
    fun tracksTheWatchWithinAFewPercentWhenTheWatchIsSane() {
        val f = load("hr_dropout_run_20260919")
        val m = minutes(HrEstimator.replay(f.points, f.samples, f.restingBpm))
        // minute 19 is the re-acquisition minute (the watch itself reads 116-157 inside it), so it is excluded
        for (min in (4..13) + (20..31)) {
            val (watch, est, _) = m.getValue(min)
            assertTrue("minute $min: watch $watch vs estimate $est", abs(watch - est) <= 0.03 * watch + 1)
        }
    }

    @Test
    fun aCleanRunIsNeverFlagged() {
        val f = load("hr_clean_run_20260918")
        val out = HrEstimator.replay(f.points, f.samples, f.restingBpm)
        assertEquals("no flagged samples on a clean run", 0, out.count { it.flagged })
    }

    @Test
    fun repairReplacesOnlyTheFlaggedSamplesAndKeepsTheWatchValue() {
        val f = load("hr_dropout_run_20260919")
        val replayed = HrEstimator.replay(f.points, f.samples, f.restingBpm)
        val changed = HrEstimator.repair(replayed)
        assertTrue("about five minutes of samples", changed.size in 250..400)
        for (s in changed) {
            assertTrue("repaired sample carries the (low) watch value", s.measured != null && s.measured!! < s.bpm)
            assertEquals("bpm is now the estimate", s.estimate, s.bpm)
            assertTrue(s.repaired)
        }
        // a second repair of the already-repaired list changes nothing
        val again = replayed.map { r -> changed.firstOrNull { it.time == r.time } ?: r }
        assertEquals(0, HrEstimator.repair(again).size)
    }

    @Test
    fun aReadingBelowTheFloorIsRejectedOnlyOnceWarmedUpAndMoving() {
        val e = HrEstimator(restingBpm = 65)
        var t = 0L
        // still: 90 bpm is fine
        assertTrue(e.onHr(t, 90).accepted)
        // start moving at running pace, heart climbing through the floor: accepted (not warmed up yet)
        for (i in 1..60) { t += 1000; e.onFix(t, 2.0, 50.0); assertTrue("climb $i", e.onHr(t, 90 + i).accepted) }
        // now sits at 150 for a minute: warmed up
        for (i in 1..60) { t += 1000; e.onFix(t, 2.0, 50.0); e.onHr(t, 150) }
        assertTrue(e.isWarmedUp)
        // a sudden 100 while still running is rejected, and after 15 s it is flagged
        var flagged = false
        for (i in 1..20) { t += 1000; e.onFix(t, 2.0, 50.0); val v = e.onHr(t, 100); assertTrue("reject $i", !v.accepted); flagged = v.flagged }
        assertTrue(flagged)
        // the estimate held near 150 through the fault
        assertTrue("estimate ${e.estimate}", e.estimate in 140..155)
        // stop moving: the same 100 is now a legitimate recovery reading, not a fault
        for (i in 1..90) { t += 1000; e.onFix(t, 0.0, 50.0) }
        val v = e.onHr(t, 100)
        assertTrue("100 bpm while standing is plausible", !v.moving)
    }

    @Test
    fun recoveryIsMeasuredFromTheLastStrideNotTheStopButton() {
        val f = load("hr_dropout_run_20260919")
        val r = HeartRateRecovery.of(f.points, f.samples)
        assertNotNull(r)
        r!!
        // the wearer stopped running about 31.5 min in and walked about for four minutes before pressing Stop
        assertTrue("effort end ${r.effortEnd / 60000.0}", r.effortEnd / 60_000.0 in 30.5..32.5)
        assertTrue("hr at end ${r.hrAtEnd}", r.hrAtEnd in 140..165)
        assertTrue("peak ${r.peakHr}", r.peakHr in 160..180)
        assertNotNull(r.drop1min); assertNotNull(r.drop2min)
        // HRR = peak - rate one minute after the last stride
        assertTrue("one-minute drop ${r.drop1min}", r.drop1min!! in 15..60)
        assertTrue("two-minute drop ${r.drop2min}", r.drop2min!! > r.drop1min!!)
    }

    @Test
    fun recoveryIsNullWithoutSamplesAfterTheEffort() {
        val f = load("hr_dropout_run_20260919")
        val r = HeartRateRecovery.of(f.points, f.samples)!!
        val truncated = f.samples.filter { it.time <= r.effortEnd + 20_000L }
        val r2 = HeartRateRecovery.of(f.points, truncated)!!
        assertNull(r2.drop1min)
        assertNull(r2.drop2min)
    }
}
