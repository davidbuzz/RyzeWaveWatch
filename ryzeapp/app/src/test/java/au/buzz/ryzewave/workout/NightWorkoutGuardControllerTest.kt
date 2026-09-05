package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightWorkoutGuardControllerTest {

    private val watch = FakeWatch()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val utc: ZoneId = ZoneId.of("UTC")

    private val warns = AtomicInteger(0)
    private val clears = AtomicInteger(0)
    private val alerter = object : NightWorkoutGuardController.NightWorkoutAlerter {
        override fun warn() { warns.incrementAndGet() }
        override fun clear() { clears.incrementAndGet() }
    }

    private val appWorkoutActive = AtomicBoolean(false)

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 9, 6).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun controller(
        startTime: Long,
        restingHr: Int? = 58,
        moving: Boolean = false,
        graceMs: Long = 50L,
    ) = NightWorkoutGuardController(
        watch = watch,
        scope = scope,
        alerter = alerter,
        isAppWorkoutActive = { appWorkoutActive.get() },
        recentRestingHr = { restingHr },
        gpsMovement = { moving },
        zone = utc,
        clock = { startTime },
        graceMs = graceMs,
        log = {},
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun accidentalNightWorkoutWarnsThenAutoStops() = runBlocking<Unit> {
        val ctl = controller(startTime = at(2, 30), restingHr = 58, graceMs = 50L)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        awaitUntil("guard warned") { warns.get() == 1 }
        assertTrue(ctl.armed)
        // the auto-stop fires after the grace period and stops the watch's exercise mode
        awaitUntil("auto-stop stopped the watch") { watch.calls.contains("stop") }
        awaitUntil("notification cleared") { clears.get() == 1 }
        assertFalse(ctl.armed)
        assertEquals(1, watch.calls.count { it == "stop" })
    }

    @Test
    fun appInitiatedWorkoutIsNeverAutoStopped() = runBlocking<Unit> {
        appWorkoutActive.set(true)                       // the app itself is running a workout
        val ctl = controller(startTime = at(2, 30), restingHr = 58, graceMs = 30L)
        // even a full accidental-looking watch START must be ignored while an app workout is active
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        delay(200L)                                      // well past the grace period
        assertFalse(ctl.armed)
        assertEquals(0, warns.get())
        assertFalse(watch.calls.contains("stop"))
    }

    @Test
    fun daytimeWatchWorkoutIsAllowed() = runBlocking<Unit> {
        val ctl = controller(startTime = at(13, 0), restingHr = 58, graceMs = 30L)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        delay(200L)
        assertFalse(ctl.armed)
        assertEquals(0, warns.get())
        assertFalse(watch.calls.contains("stop"))
    }

    @Test
    fun highDaytimeHeartRateWatchWorkoutIsAllowed() = runBlocking<Unit> {
        val ctl = controller(startTime = at(2, 30), restingHr = 120, graceMs = 30L)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        delay(200L)
        assertFalse(ctl.armed)
        assertEquals(0, warns.get())
        assertFalse(watch.calls.contains("stop"))
    }

    @Test
    fun stopNowAcknowledgesImmediatelyAndCancelsTheAutoStop() = runBlocking<Unit> {
        // long grace so the auto-stop cannot fire before the user's Stop
        val ctl = controller(startTime = at(2, 30), restingHr = 58, graceMs = 10_000L)
        ctl.debugForceStart(at(2, 30), recentHr = 58, gpsMovement = false, graceOverrideMs = 10_000L)
        awaitUntil("guard warned") { warns.get() == 1 }
        assertTrue(ctl.armed)
        ctl.stopNow()
        awaitUntil("user stop stopped the watch") { watch.calls.contains("stop") }
        assertFalse(ctl.armed)
        // give the (cancelled) auto-stop a chance to (not) fire: still exactly one stop
        delay(100L)
        assertEquals(1, watch.calls.count { it == "stop" })
        assertEquals(1, clears.get())
    }

    @Test
    fun debugForceStartIsIgnoredWhileAnAppWorkoutRuns() = runBlocking<Unit> {
        appWorkoutActive.set(true)
        val ctl = controller(startTime = at(2, 30), restingHr = 58, graceMs = 30L)
        ctl.debugForceStart(at(2, 30), recentHr = 58, gpsMovement = false, graceOverrideMs = 30L)
        delay(200L)
        assertFalse(ctl.armed)
        assertEquals(0, warns.get())
        assertFalse(watch.calls.contains("stop"))
    }
}
