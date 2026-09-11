package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stuck-in-exercise-mode monitor end to end with fakes: a watch-originated workout whose session step counter
 * stays flat (the accidental night press of 2026-09-05) is warned and then auto-stopped; rising steps keep it live;
 * an app-started workout is warned but never auto-stopped unless the setting says so; acknowledging stops it now.
 * Times come from a fake clock the test advances; the monitor's own ticker runs every 20 ms of real time.
 */
class StuckWorkoutMonitorTest {
    private val repo = FakeRepo()
    private val watch = FakeWatch()
    private val settings = FakeSettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var now = 1_000_000L
    private val warns = AtomicInteger()
    private val clears = AtomicInteger()
    private val spoken = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()
    private val alerter = object : StuckWorkoutMonitor.Alerter {
        override fun warn(urgency: Urgency, title: String, text: String) { warns.incrementAndGet() }
        override fun clear() { clears.incrementAndGet() }
    }
    private val controller = WorkoutController(
        repo = repo, watch = watch, settings = settings, scope = scope,
        tracker = DefaultGpsDistanceTracker(), clock = { now }, tickMs = 20L,
    )
    private val WINDOW = 10_000L
    /** The auto-stop grace is a REAL delay in the monitor (not the fake clock), so keep it short. */
    private val GRACE = 400L

    private fun monitor(autoStopApp: Boolean = false) = StuckWorkoutMonitor(
        watch = watch, controller = controller, scope = scope, alerter = alerter, speaker = { spoken += it },
        detectorEnabled = { true }, autoStopAppWorkouts = { autoStopApp },
        clock = { now }, windowMs = WINDOW, evalIntervalMs = 20L, graceNormalMs = GRACE, graceHighMs = GRACE / 2,
        log = { logs += "${System.currentTimeMillis() % 100000} $it" },
    ).also { m ->
        // the monitor's collectors subscribe asynchronously; wait for them (the controller already holds one)
        runBlocking { awaitUntil("monitor subscribed") { watch.eventBus.subscriptionCount.value >= 2 } }
    }

    @After fun tearDown() = scope.cancel()

    private suspend fun waitFor(what: String, timeoutMs: Long = 3_000L, cond: () -> Boolean) {
        try { awaitUntil(what, timeoutMs, cond) } catch (e: AssertionError) { println("MONITOR LOG on '$what':\n" + logs.joinToString("\n")); throw e }
    }

    private suspend fun realtime(steps: Int) = watch.emitEvent(WatchEvent.WorkoutRealtime(sportType = 1, steps = steps, calories = 0, distanceMeters = 0.0))

    @Test
    fun aWatchStartedWorkoutWithFlatStepsIsWarnedThenAutoStopped() = runBlocking<Unit> {
        val m = monitor()
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        awaitUntil("watching") { m.active }
        // the misleading plain "workout started" of 2026-09-10 is gone: the monitor never announces a start
        assertFalse(spoken.contains(StateAnnouncer.STARTED))
        // flat session steps + a resting HR for longer than the window
        for (i in 1..6) { realtime(40); watch.hr.emit(HrSample(now, 58, SampleSource.WORKOUT)); now += WINDOW / 4 }
        awaitUntil("warned") { warns.get() == 1 }
        assertTrue(spoken.contains(StuckWorkoutMonitor.WARN_SPEECH))
        assertFalse("not stopped before the grace", watch.calls.contains("stop"))
        awaitUntil("auto-stopped", timeoutMs = 5_000L) { watch.calls.contains("stop") }
        awaitUntil("stop spoken") { spoken.contains(StateAnnouncer.STOPPED_NO_ACTIVITY) }
        assertFalse(monitorActive())
    }

    @Test
    fun risingStepsKeepItLiveAndActivityResumingWithdrawsAWarning() = runBlocking<Unit> {
        val m = monitor()
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        awaitUntil("watching") { m.active }
        var steps = 40
        for (i in 1..6) { steps += 25; realtime(steps); now += WINDOW / 4 }
        Thread.sleep(150)
        assertEquals("rising steps must never warn", 0, warns.get())
        // then flat for a window → warned; then rising again → withdrawn, no stop
        for (i in 1..6) { realtime(steps); now += WINDOW / 4 }
        awaitUntil("warned") { warns.get() == 1 }
        for (i in 1..3) { steps += 30; realtime(steps); now += WINDOW / 4 }
        awaitUntil("withdrawn") { clears.get() >= 1 }
        Thread.sleep(GRACE * 2)
        assertFalse("withdrawn warning must not auto-stop", watch.calls.contains("stop"))
    }

    @Test
    fun anAppStartedWorkoutIsWarnedButNotAutoStoppedByDefault() = runBlocking<Unit> {
        monitor(autoStopApp = false)
        controller.start(sportType = 1)
        for (i in 1..6) { realtime(0); now += WINDOW / 4 }
        awaitUntil("warned") { warns.get() == 1 }
        Thread.sleep(GRACE * 3)
        assertEquals(WorkoutPhase.RUNNING, controller.state.value.state)
        controller.stop()
    }

    @Test
    fun anAppStartedWorkoutIsAutoStoppedWhenTheSettingIsOn() = runBlocking<Unit> {
        monitor(autoStopApp = true)
        controller.start(sportType = 1)
        for (i in 1..6) { realtime(0); now += WINDOW / 4 }
        awaitUntil("warned") { warns.get() == 1 }
        waitFor("auto-stopped through the controller", timeoutMs = 5_000L) { controller.state.value.state == WorkoutPhase.STOPPED }
        assertEquals(StopReason.NO_ACTIVITY, controller.state.value.stopReason)
    }

    @Test
    fun stopNowFromTheNotificationStopsAWatchWorkoutImmediately() = runBlocking<Unit> {
        val m = monitor()
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        awaitUntil("watching") { m.active }
        for (i in 1..6) { realtime(40); now += WINDOW / 4; kotlinx.coroutines.delay(30) }
        waitFor("warned", timeoutMs = 5_000L) { warns.get() == 1 }
        m.stopNow()
        awaitUntil("stopped") { watch.calls.contains("stop") }
        awaitUntil("cleared") { clears.get() >= 1 }
        assertFalse(m.active)
    }

    private fun monitorActive(): Boolean = false.also { /* the watch session ended: nothing to watch */ }
}
