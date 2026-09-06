package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stride calibration, split by gait. The headline case is a replay of a real workout
 * (`mixed_run_20260906_steps.csv`): an Outdoor Running session in which the wearer deliberately sprinted at each
 * end and ambled through the middle. The old calibration divided the totals — 772 m / 874 steps = 0.883 m — and
 * filed that single blended number as the *walking* stride, because the average speed of 1.42 m/s fell under a
 * fixed 2 m/s threshold. Neither the number nor the label was right.
 */
class StrideCalibrationTest {

    private val buzz = UserProfile(heightCm = 182, weightKg = 80, age = 50, male = true)

    /** Real recorded run: elapsedMs, cumulative GPS metres, watch session steps, paused. No coordinates. */
    private fun realRun(): List<TrackPoint> =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("mixed_run_20260906_steps.csv"))
            .bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split(",")
                TrackPoint(
                    workoutId = 1L, time = f[0].toLong(), lat = 0.0, lon = 0.0, accuracyM = 5f,
                    speedMps = 0f, altitudeM = null, accepted = true,
                    cumulativeM = f[1].toDouble(), paused = f[3] == "1", steps = f[2].toInt(),
                )
            }

    private fun synthetic(
        windows: Int,
        strideM: Double,
        cadenceSpm: Double,
        windowSec: Double = 15.0,
        startId: Long = 0L,
    ): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        var t = startId
        var dist = 0.0
        var steps = 0
        out += TrackPoint(1L, t, 0.0, 0.0, 5f, 0f, null, true, dist, false, steps)
        repeat(windows) {
            val ds = (cadenceSpm * windowSec / 60.0).toInt()
            steps += ds
            dist += ds * strideM
            t += (windowSec * 1000).toLong()
            out += TrackPoint(1L, t, 0.0, 0.0, 5f, 0f, null, true, dist, false, steps)
        }
        return out
    }

    // ---------------------------------------------------------------- the real run

    @Test
    fun realMixedRunProducesBothStridesInsteadOfOneBlendedNumber() {
        val out = StrideCalibration.calibrate(realRun(), buzz, SportTypes_OUTDOOR_RUNNING)
        val walk = assertNotNull("walking band", out.walk).let { out.walk!! }
        val run = assertNotNull("running band", out.run).let { out.run!! }

        // The blended figure the old code stored must not appear as either band.
        assertTrue("walk stride must not be the blended 0.883", kotlin.math.abs(walk.strideM - 0.883) > 0.05)
        assertTrue("run stride must not be the blended 0.883", kotlin.math.abs(run.strideM - 0.883) > 0.05)

        // Both bands land near what the wearer's height predicts (0.746 walking, 0.994 running).
        assertTrue("walking $walk", walk.strideM in 0.60..0.78)
        assertTrue("running $run", run.strideM in 0.85..1.05)
        assertTrue("running stride must exceed walking", run.strideM > walk.strideM + 0.15)
        assertTrue("message names both", out.message.contains("walking") && out.message.contains("running"))
    }

    @Test
    fun realRunIsNotFiledAsWalkingDespiteItsLowAverageSpeed() {
        // 772 m over 543 s is 1.42 m/s: under any fixed run threshold, yet the session was a run.
        val out = StrideCalibration.calibrate(realRun(), buzz, SportTypes_OUTDOOR_RUNNING)
        assertNotNull("the running band must exist", out.run)
        assertTrue("running distance is substantial", out.run!!.distanceM > 200.0)
    }

    // ---------------------------------------------------------------- the sport gates the result

    @Test
    fun rowingAndSwimmingAreStrokeCountsNotSteps() {
        // The arms move in time with the effort, so the counter is real - a stroke count - but never a stride.
        for (sport in listOf(0x29 /* Rower */, 0x04 /* Swimming */, 0x17 /* Boating */)) {
            val out = StrideCalibration.calibrate(realRun(), buzz, sport)
            assertNull("sport $sport must not set a walking stride", out.walk)
            assertNull("sport $sport must not set a running stride", out.run)
            assertEquals(SportGait.STROKES, StrideCalibration.sportGait(sport))
            assertTrue(out.message, out.message.contains("strokes"))
        }
    }

    @Test
    fun cyclingCountsNothingUsefulBecauseTheHandsAreStill() {
        // A wrist cannot see pedals: the hands sit on the bars, so the counter is road buzz, not cadence.
        for (sport in listOf(0x02 /* Cycling */, 0x12 /* Spinning */)) {
            val out = StrideCalibration.calibrate(realRun(), buzz, sport)
            assertNull(out.walk)
            assertNull(out.run)
            assertEquals(SportGait.NONE, StrideCalibration.sportGait(sport))
            assertTrue(out.message, out.message.contains("hands still"))
        }
    }

    @Test
    fun repetitionSportsRefuseDeliberatelyNotByAccident() {
        // Gym work, martial arts and dance count repetitions of the wrist, not travel. These used to fall through
        // to the permissive default and were only safe because such sessions carry no GPS distance.
        for (sport in listOf(0x14 /* Sit-ups */, 0x22 /* Boxing */, 0x61 /* HIIT */, 0x6D /* Push-up */)) {
            assertEquals("sport $sport", SportGait.REPS, StrideCalibration.sportGait(sport))
            val out = StrideCalibration.calibrate(realRun(), buzz, sport)
            assertNull(out.walk)
            assertNull(out.run)
            assertTrue(out.message, out.message.contains("repetitions"))
        }
    }

    @Test
    fun racketSportsAreNotAGaitAndSaySo() {
        for (sport in listOf(0x07 /* Tennis */, 0x05 /* Badminton */, 0x60 /* Pickleball */)) {
            assertEquals("sport $sport", SportGait.COURT, StrideCalibration.sportGait(sport))
            val out = StrideCalibration.calibrate(realRun(), buzz, sport)
            assertNull(out.walk); assertNull(out.run)
            assertTrue(out.message, out.message.contains("shuffles"))
        }
    }

    @Test
    fun snorkelingIsFinDrivenNotAStrokeSport() {
        // Arms relaxed at the sides; propulsion is the fin kick. The wrist counts nothing useful.
        assertEquals(SportGait.NONE, StrideCalibration.sportGait(0x6B))
    }

    @Test
    fun aWalkingSportOnlyEverTeachesTheWalkingStride() {
        // Same track, declared as Outdoor Walking: the fast windows must not become a running stride.
        val out = StrideCalibration.calibrate(realRun(), buzz, 0x23)
        assertNotNull("walking band", out.walk)
        assertNull("a walking sport cannot produce a running stride", out.run)
    }

    // ---------------------------------------------------------------- bodies that are not Buzz

    @Test
    fun shortSlowJoggerIsRunningEvenThoughStrideAndSpeedAreSmall() {
        // Synthetic: 155 cm, 0.60 m stride (ratio 0.39 — walking-shaped), cadence 168 (running).
        // Speed is 1.68 m/s, below the speed a taller person walks at.
        val small = UserProfile(heightCm = 155, weightKg = 55, age = 68, male = false)
        val out = StrideCalibration.calibrate(synthetic(windows = 20, strideM = 0.60, cadenceSpm = 168.0), small, null)
        assertNotNull("cadence alone must identify the jog", out.run)
        assertEquals(0.60, out.run!!.strideM, 0.02)
        assertNull("nothing here was walking", out.walk)
    }

    @Test
    fun tallBriskWalkerIsWalkingDespiteALongStride() {
        // Synthetic: 190 cm, 0.84 m stride (ratio 0.44), cadence 132 -> 1.85 m/s. Long step, unhurried feet.
        val tall = UserProfile(heightCm = 190, weightKg = 90, age = 35, male = true)
        val out = StrideCalibration.calibrate(synthetic(windows = 20, strideM = 0.84, cadenceSpm = 132.0), tall, null)
        assertNotNull("walking band", out.walk)
        assertEquals(0.84, out.walk!!.strideM, 0.02)
        assertNull("a brisk walk is not a run", out.run)
    }

    @Test
    fun classifyUsesBothSignalsNotSpeedAlone() {
        val small = UserProfile(heightCm = 155, male = false)
        // Slow jogger: short stride, high cadence, modest speed.
        assertEquals(Gait.RUN, StrideCalibration.classify(0.60, 168.0, 1.68, small))
        // Brisk walker of the same height: same speed, low cadence, short stride.
        assertEquals(Gait.WALK, StrideCalibration.classify(0.72, 120.0, 1.44, small))
        // Between the two: nothing is claimed.
        assertEquals(Gait.UNKNOWN, StrideCalibration.classify(0.72, 148.0, 1.78, small))
    }

    @Test
    fun transitionSpeedScalesWithHeightButOnlyGently() {
        val tall = StrideCalibration.walkRunSpeedMps(UserProfile(heightCm = 190))
        val short = StrideCalibration.walkRunSpeedMps(UserProfile(heightCm = 150))
        assertTrue("taller transitions later", tall > short)
        assertTrue("but not dramatically: $short..$tall", tall - short < 0.5)
    }

    // ---------------------------------------------------------------- guards

    @Test
    fun anImplausibleStrideIsReportedNotSaved() {
        // 1.4 m per step for a 182 cm walker is far beyond the 0.746 m the height implies.
        val out = StrideCalibration.calibrate(synthetic(windows = 20, strideM = 1.40, cadenceSpm = 110.0), buzz, 0x23)
        assertNull(out.walk)
        assertTrue(out.notes.toString(), out.notes.any { it.contains("too far from") })
    }

    @Test
    fun tooLittleEvidenceChangesNothing() {
        val out = StrideCalibration.calibrate(synthetic(windows = 2, strideM = 0.75, cadenceSpm = 110.0), buzz, 0x23)
        assertNull(out.walk)
        assertNull(out.run)
    }

    @Test
    fun pointsWithoutStepCountsAreIgnored() {
        val noSteps = synthetic(windows = 20, strideM = 0.75, cadenceSpm = 110.0).map { it.copy(steps = null) }
        val out = StrideCalibration.calibrate(noSteps, buzz, 0x23)
        assertNull(out.walk)
        assertTrue(out.message, out.message.contains("step counts"))
    }

    @Test
    fun pausedStretchesAreExcluded() {
        val pts = synthetic(windows = 20, strideM = 0.75, cadenceSpm = 110.0)
        val withPause = pts.mapIndexed { i, p -> if (i in 5..9) p.copy(paused = true) else p }
        val out = StrideCalibration.calibrate(withPause, buzz, 0x23)
        assertNotNull(out.walk)
        assertEquals("the paused stretch must not distort the stride", 0.75, out.walk!!.strideM, 0.03)
    }

    private companion object {
        const val SportTypes_OUTDOOR_RUNNING = 0x01
    }
}
