package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.protocol.Protocol
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Drives the real [WorkoutController] + [DefaultGpsDistanceTracker] with the synthetic 20-minute run, with a
 * fake repository / watch (Fakes.kt) and a virtual clock. Every `updateWorkout` the controller pushes is
 * re-encoded with the real [Protocol.encSportUpdate] (what WatchApiImpl.updateWorkout sends as FD 44) so the
 * km / hundredths / pace bytes the watch face would show are what is asserted.
 */
class SyntheticRunWorkoutControllerTest {
    private val repo = FakeRepo()
    private val watch = FakeWatch()
    private val settings = FakeSettings(UserProfile(heightCm = 182, weightKg = 80, age = 45, male = true))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val errors = CopyOnWriteArrayList<String>()

    @Volatile private var now = SyntheticRun.T0

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(tracker: DefaultGpsDistanceTracker) = WorkoutController(
        repo = repo, watch = watch, settings = settings, scope = scope,
        tracker = tracker, clock = { now }, tickMs = 5L,
        onError = { message, _ -> errors += message },
    )

    /** The FD 44 payload bytes: (kmInt, kmFrac, paceMin, paceSec) per Protocol.encSportUpdate (lines 297-312). */
    private data class Fd44(val duration: Int, val km: Int, val hundredths: Int, val paceMin: Int, val paceSec: Int, val calories: Int) {
        val hundredthsTotal get() = km * 100 + hundredths
        val paceSecPerKm get() = paceMin * 60 + paceSec
    }

    private fun encode(u: FakeWatch.Update): Fd44 {
        val b = Protocol.encSportUpdate(1, u.duration, u.calories, u.distance, u.pace)
        assertEquals(13, b.size)
        assertEquals(0xFD, b[0].toInt() and 0xFF)
        assertEquals(Protocol.SPORT_UPDATE, b[1].toInt() and 0xFF)
        val h = b[4].toInt() and 0xFF; val m = b[5].toInt() and 0xFF; val s = b[6].toInt() and 0xFF
        return Fd44(
            duration = h * 3600 + m * 60 + s,
            km = b[9].toInt() and 0xFF, hundredths = b[10].toInt() and 0xFF,
            paceMin = b[11].toInt() and 0xFF, paceSec = b[12].toInt() and 0xFF,
            calories = ((b[7].toInt() and 0xFF) shl 8) or (b[8].toInt() and 0xFF),
        )
    }

    /** Feeds the fixes at 1 s virtual intervals, letting the ticker push at least one FD 44 every [tickEvery] fixes. */
    private suspend fun feed(ctl: WorkoutController, fixes: List<SyntheticFix>, tickEvery: Int = 10) {
        var n = 0
        for (f in fixes) {
            now = f.time
            ctl.onLocation(f.time, f.lat, f.lon, f.accuracyM, f.speedMps, 20.0)
            if (++n % tickEvery == 0) {
                val before = watch.updates.size
                awaitUntil("tick after fix $n") { watch.updates.size > before }
            }
        }
    }

    @Test
    fun twentyMinuteRunPushesGrowingDistanceAndPaceToTheWatchAndPersistsIt() = runBlocking<Unit> {
        val tracker = DefaultGpsDistanceTracker()
        val ctl = controller(tracker)
        val fixes = SyntheticRun.fixes(accuracyM = 6f, dopplerSpeedMps = 3.0f)
        now = SyntheticRun.T0
        val id = ctl.start(1)
        assertEquals(1L, id)
        feed(ctl, fixes)
        // let the clock reach exactly 20:00 and a final tick run
        now = SyntheticRun.T0 + 1200_000L
        val before = watch.updates.size
        awaitUntil("final tick") { watch.updates.size > before }
        val final = ctl.stop()
        assertNotNull(final)
        final!!

        val pushes = watch.updates.map { encode(it) }
        println("SYNTHETIC_CTL pushes=${pushes.size} first=${pushes.first()} last=${pushes.last()}")
        val sample = (0..1200 step 120).mapNotNull { t -> pushes.minByOrNull { abs(it.duration - t) } }.distinct()
        println("SYNTHETIC_CTL sample=" + sample.joinToString { "${it.duration}s=${it.km}.${"%02d".format(it.hundredths)}km@${it.paceMin}:${"%02d".format(it.paceSec)}" })
        println("SYNTHETIC_CTL tracker: accepted=${tracker.acceptedCount} rejJitter=${tracker.rejectedJitterCount} rejSpike=${tracker.rejectedSpikeCount} rejAcc=${tracker.rejectedAccuracyCount} distance=%.1f".format(tracker.distanceMeters))
        println("SYNTHETIC_CTL final row: $final")
        println("SYNTHETIC_CTL errors=$errors trackPoints=${repo.points().size} accepted=${repo.points().count { it.accepted }}")

        // (a) the km/hundredths never go backwards and grow across the run
        val hundredths = pushes.map { it.hundredthsTotal }
        assertTrue("FD 44 distance went backwards: $hundredths", hundredths.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("distance never grew past 0.01 km: $hundredths", hundredths.last() >= 340)   // 3.40 km (5 % under 3.60)
        assertTrue("last FD 44 distance ${hundredths.last()} hundredths exceeds 3.78 km (5 % over)", hundredths.last() <= 378)   // 3.78 km
        assertEquals(3, pushes.last().km)
        // the distance moves through many values on the way (one FD 44 per ~10 fixes = 0.03 km steps at
        // 3 m/s, so about 120 distinct readings across 3.6 km), rather than sticking at a few
        val distinct = hundredths.distinct().size
        assertTrue("expected many distinct distance values, got $distinct of ${pushes.size} pushes", distinct >= 100)
        // no push after the first minute reports 0.00 / 0.01 km (the vendor-app symptom)
        assertTrue(pushes.filter { it.duration >= 60 }.all { it.hundredthsTotal >= 15 })

        // (b) pace is non-zero (and near 5:33 min/km) on every push after the window warms up
        val warm = pushes.filter { it.duration >= 30 }
        val zeroPace = warm.filter { it.paceSecPerKm == 0 }
        assertTrue("zero pace on ${zeroPace.size}/${warm.size} pushes after 30 s: ${zeroPace.take(5)}", zeroPace.isEmpty())
        val offPace = warm.filter { abs(it.paceSecPerKm - 333) > 40 }
        assertTrue("pace outside 4:53..6:13 min/km on ${offPace.size}/${warm.size} pushes, e.g. ${offPace.take(3)}", offPace.isEmpty())
        assertTrue(pushes.last().duration == 1200)
        assertTrue(pushes.last().calories > 0)

        // (c) stop() writes the Workout row with the accumulated distance
        assertEquals(1L, final.id)
        assertEquals(now, final.end)
        assertEquals(1200, final.durationSeconds)
        assertEquals(tracker.distanceMeters, final.distanceMeters, 0.0)
        assertTrue("row distance %.1f".format(final.distanceMeters), abs(final.distanceMeters - 3600.0) / 3600.0 <= 0.05)
        assertEquals(final, repo.updates().last())
        assertEquals(final.distanceMeters, repo.workouts().first().first().distanceMeters, 0.0)
        assertEquals(1200, repo.points().size)
        assertEquals(1200, repo.points().count { it.accepted })
        assertTrue("errors: $errors", errors.isEmpty())
    }

    /** Same run through the controller with a 30 s outage: distance is bridged; pace shows 0 while fixes are stale. */
    @Test
    fun thirtySecondGapThroughTheController() = runBlocking<Unit> {
        val tracker = DefaultGpsDistanceTracker()
        val ctl = controller(tracker)
        val fixes = SyntheticRun.fixes(accuracyM = 6f, dopplerSpeedMps = 3.0f, gapFromS = 600, gapLengthS = 30)
        now = SyntheticRun.T0
        ctl.start(1)
        feed(ctl, fixes.filter { it.time < SyntheticRun.T0 + 600_000L })
        // the outage: the clock advances, ticks run, no fixes arrive
        for (s in 600 until 630) {
            now = SyntheticRun.T0 + s * 1000L
            val before = watch.updates.size
            awaitUntil("tick during gap at $s s") { watch.updates.size > before }
        }
        val gapPushes = watch.updates.map { encode(it) }.filter { it.duration in 600..629 }
        val stale = gapPushes.filter { it.duration > 615 }
        println("SYNTHETIC_CTL_GAP pushes during gap: " + gapPushes.distinctBy { it.duration }.joinToString { "${it.duration}s=${it.km}.${"%02d".format(it.hundredths)}@${it.paceMin}:${"%02d".format(it.paceSec)}" })
        // WorkoutController.tick line 328: STALE_FIX_MS = 15 s -> pace and speed pushed as 0 while stale
        assertTrue("stale pushes must carry pace 0: $stale", stale.all { it.paceSecPerKm == 0 })
        assertTrue(gapPushes.filter { it.duration <= 15 + 600 }.any { it.paceSecPerKm > 0 })
        feed(ctl, fixes.filter { it.time >= SyntheticRun.T0 + 630_000L })
        now = SyntheticRun.T0 + 1200_000L
        val before = watch.updates.size
        awaitUntil("final tick") { watch.updates.size > before }
        val final = ctl.stop()!!
        println("SYNTHETIC_CTL_GAP final: distance=%.1f accepted=${tracker.acceptedCount} spikes=${tracker.rejectedSpikeCount} row=$final".format(final.distanceMeters))
        assertEquals(0, tracker.rejectedSpikeCount)
        assertTrue("gap run row distance %.1f m not within 5 %% of 3600".format(final.distanceMeters), abs(final.distanceMeters - 3600.0) / 3600.0 <= 0.05)
        val hundredths = watch.updates.map { encode(it).hundredthsTotal }
        assertTrue(hundredths.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1170, repo.points().size)
    }

    /** The 25 m accuracy run through the controller: what the watch face and the row show (reported, not forced). */
    @Test
    fun accuracy25mThroughTheController_reported() = runBlocking<Unit> {
        val tracker = DefaultGpsDistanceTracker()
        val ctl = controller(tracker)
        now = SyntheticRun.T0
        ctl.start(1)
        feed(ctl, SyntheticRun.fixes(accuracyM = 25f, dopplerSpeedMps = 3.0f), tickEvery = 50)
        now = SyntheticRun.T0 + 1200_000L
        val before = watch.updates.size
        awaitUntil("final tick") { watch.updates.size > before }
        val final = ctl.stop()!!
        val last = encode(watch.updates.last())
        println("SYNTHETIC_CTL_25M last push=$last row=$final state.trackPoints=${ctl.state.value.trackPointCount} accepted=${ctl.state.value.acceptedPointCount}")
        assertEquals(0, last.hundredthsTotal)
        assertEquals(0, last.paceSecPerKm)
        assertEquals(0.0, final.distanceMeters, 0.0)
        assertEquals(1200, final.durationSeconds)
        assertEquals(1200, repo.points().size)          // the raw fixes are still stored, flagged accepted=false
        assertEquals(0, repo.points().count { it.accepted })
    }
}
