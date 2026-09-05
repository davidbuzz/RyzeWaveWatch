package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches for a *watch-originated* workout start ([WatchEvent.WorkoutControl] with [WorkoutControlAction.START])
 * and, when [NightWorkoutGuard] flags it as a likely accidental overnight touch, warns the user and stops the
 * exercise mode before it can corrupt the night's sleep tracking.
 *
 * A watch-started workout has no app-side [WorkoutController] session (the app cannot run GPS/foreground from a
 * wrist press), so "stop the workout" means telling the watch to leave exercise mode ([WatchApi.stopWorkout] →
 * `FD 00`). App-initiated workouts never reach this class: the app's own start is consumed as a command echo and
 * never surfaces as a [WorkoutControlAction.START] event, and [isAppWorkoutActive] is checked as a second guard,
 * so an app-initiated workout is never auto-stopped.
 *
 * On a likely-accidental start it posts a high-priority notification (via [NightWorkoutAlerter]) with a Stop
 * action, and arms an auto-stop after [graceMs]; the Stop action ([stopNow]) or the timer both call
 * [WatchApi.stopWorkout]. No Android imports here so it is unit-testable; [AndroidNightWorkoutAlerter] and the
 * graph wiring supply the real notification and input suppliers.
 */
class NightWorkoutGuardController(
    private val watch: WatchApi,
    private val scope: CoroutineScope,
    private val alerter: NightWorkoutAlerter,
    /** True while the app itself is running a workout: such a start is never treated as accidental. */
    private val isAppWorkoutActive: () -> Boolean,
    /** Average of the recent periodic HR samples (see [NightWorkoutGuard.restingHr]); null when unknown. */
    private val recentRestingHr: suspend () -> Int?,
    /** Movement seen in the first ~60 s (false when no GPS session is running, as for a watch-only start). */
    private val gpsMovement: suspend () -> Boolean = { false },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val graceMs: Long = GRACE_MS,
    private val restingThreshold: Int = NightWorkoutGuard.RESTING_HR_BPM,
    private val log: (String) -> Unit = { println("NightWorkoutGuard: $it") },
) {

    /** The Android side: a high-priority "you may be asleep" notification with a Stop action. */
    interface NightWorkoutAlerter {
        fun warn()
        fun clear()
    }

    private val lock = Any()
    private var autoStop: Job? = null

    /** True while a warning is up and the auto-stop is armed (tests / diagnostics). */
    @Volatile
    var armed = false
        private set

    init {
        scope.launch { watch.events.collect { onEvent(it) } }
    }

    private suspend fun onEvent(e: WatchEvent) {
        if (e is WatchEvent.WorkoutControl && e.action == WorkoutControlAction.START) onWatchWorkoutStart(clock())
    }

    /** A watch-originated workout START at [startTime]: gather live inputs and act on the guard's decision. */
    suspend fun onWatchWorkoutStart(startTime: Long) {
        if (isAppWorkoutActive()) {
            log("ignoring watch-originated START: an app-initiated workout is active (never auto-stopped)")
            return
        }
        val hr = safeSuspend("recentRestingHr", null) { recentRestingHr() }
        val moving = safeSuspend("gpsMovement", false) { gpsMovement() }
        act(startTime, hr, moving, graceMs)
    }

    /**
     * Debug/demo hook (adb WORKOUT watchstart): run the guard with forced inputs and grace, bypassing the live
     * HR/GPS suppliers so the notification + auto-stop can be exercised on demand. Still honours [isAppWorkoutActive].
     */
    fun debugForceStart(startTime: Long, recentHr: Int?, gpsMovement: Boolean, graceOverrideMs: Long? = null) {
        scope.launch {
            if (isAppWorkoutActive()) {
                log("debug watchstart ignored: an app-initiated workout is active")
                return@launch
            }
            act(startTime, recentHr, gpsMovement, graceOverrideMs ?: graceMs)
        }
    }

    private fun act(startTime: Long, hr: Int?, moving: Boolean, grace: Long) {
        val decision = NightWorkoutGuard.evaluate(NightWorkoutGuard.Inputs(startTime, hr, moving), zone, restingThreshold)
        log(
            "watch-originated workout start at ${TIME_FMT.format(Instant.ofEpochMilli(startTime).atZone(zone))} " +
                "-> ${if (decision.accidental) "LIKELY ACCIDENTAL" else "allowed"} (${decision.reason})",
        )
        if (!decision.accidental) return
        synchronized(lock) {
            armed = true
            autoStop?.cancel()
            autoStop = scope.launch {
                delay(grace)
                log("grace period (${grace / 1000} s) elapsed without acknowledgement; auto-stopping the watch workout")
                doStop("auto-stop")
            }
        }
        try {
            alerter.warn()
        } catch (e: Exception) {
            log("alerter.warn failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** From the notification's Stop action: stop the watch workout now (user acknowledged). */
    fun stopNow() {
        scope.launch { doStop("user") }
    }

    private suspend fun doStop(reason: String) {
        val proceed = synchronized(lock) {
            autoStop?.cancel()
            autoStop = null
            if (armed) {
                armed = false
                true
            } else {
                false
            }
        }
        if (!proceed) return
        log("stopping the watch workout ($reason)")
        try {
            watch.stopWorkout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("stopWorkout failed: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            alerter.clear()
        } catch (e: Exception) {
            log("alerter.clear failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun <T> safeSuspend(what: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$what failed: ${e.message ?: e.javaClass.simpleName}")
        fallback
    }

    companion object {
        /** Auto-stop this long after a likely-accidental start if the user has not acknowledged. */
        const val GRACE_MS = 3 * 60_000L

        /** How far back the resting-HR average looks. */
        const val RECENT_HR_WINDOW_MS = 30 * 60_000L

        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
