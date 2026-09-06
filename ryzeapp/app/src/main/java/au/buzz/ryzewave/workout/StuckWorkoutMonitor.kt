package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import au.buzz.ryzewave.protocol.SportTypes
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The stuck-in-exercise-mode detector (docs/PLAN.md): watches every workout — the app's own
 * ([WorkoutController] session, [Origin.APP]) and one the *watch* started by itself (a watch-originated
 * [WorkoutControlAction.START], [Origin.WATCH]) — reduces what it observes to [ActivitySignals], judges them
 * against the sport's expected signature ([SportSignature]) with [StuckModeDetector] every [evalIntervalMs],
 * and escalates:
 *
 *  1. the first time the workout looks stuck: speak "[WARN_SPEECH]" and post a high-priority notification with
 *     a Stop action ([Alerter.warn]);
 *  2. when the grace elapses without acknowledgement and an auto-stop applies: stop the workout on the normal
 *     path — [WorkoutController.stop] for an app session (which also tells the watch and makes the service
 *     announce [StateAnnouncer.STOPPED_NO_ACTIVITY]), [WatchApi.stopWorkout] for a watch-only session (spoken
 *     here) — and clear the notification.
 *  3. if activity resumes first: cancel the pending auto-stop, clear the notification, log.
 *
 * Auto-stop applies to a watch-originated workout whenever the detector is enabled, and to an app-initiated one
 * only when [autoStopAppWorkouts] is on (Settings). The grace is [StuckModeDetector.GRACE_HIGH_MS] for a HIGH
 * verdict (night + sleeping HR + still phone: the former night workout guard) and
 * [StuckModeDetector.GRACE_NORMAL_MS] otherwise.
 *
 * Signals: watch session steps from every realtime push ([WatchEvent.WorkoutRealtime], flat counts included, so
 * "no steps" is measured rather than unknown); the phone step counter, GPS distance/speed from the controller
 * state (app sessions only — a watch-only workout has no GPS session, so GPS stays UNKNOWN); heart rate from
 * [WatchApi.liveHr] plus the periodic auto/history samples ([periodicHr]) for a watch that is not streaming;
 * phone motion from [MotionSource] (started per session). The resting baseline comes from [restingBaseline].
 *
 * A watch-originated start is also announced ("[StateAnnouncer.STARTED]") so an accidental wrist press is
 * heard immediately; app starts are announced by [WorkoutService] from the controller's state, so each start is
 * spoken exactly once. Pause/resume of a watch-only session (`FD 22`/`FD 33`) suspend the judgement; a resume
 * restarts the window. No Android imports; unit-tested with fakes.
 */
class StuckWorkoutMonitor(
    private val watch: WatchApi,
    private val controller: WorkoutController,
    private val scope: CoroutineScope,
    private val alerter: Alerter,
    private val speaker: (String) -> Unit,
    private val motion: MotionSource = MotionSource.NONE,
    /** Resting HR from the last day of periodic samples ([RestingHrBaseline]); null → the fallback. */
    private val restingBaseline: suspend () -> Int? = { null },
    /** Periodic (AUTO / HISTORY) HR samples in [from, to] — the watch's own 10-minute measurements. */
    private val periodicHr: suspend (from: Long, to: Long) -> List<HrSample> = { _, _ -> emptyList() },
    private val detectorEnabled: suspend () -> Boolean = { true },
    private val autoStopAppWorkouts: suspend () -> Boolean = { false },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = StuckModeDetector.WINDOW_MS,
    private val evalIntervalMs: Long = EVAL_INTERVAL_MS,
    private val graceNormalMs: Long = StuckModeDetector.GRACE_NORMAL_MS,
    private val graceHighMs: Long = StuckModeDetector.GRACE_HIGH_MS,
    private val log: (String) -> Unit = { println("StuckWorkoutMonitor: $it") },
) {
    /** The Android side: the high-priority "no activity" notification with a Stop action. */
    interface Alerter {
        fun warn(urgency: Urgency, title: String, text: String)
        fun clear()
    }

    /** A low-rate accelerometer: reports the variance of |a| per block while started. */
    interface MotionSource {
        fun start(onVariance: (time: Long, variance: Double) -> Unit)
        fun stop()

        object NONE : MotionSource {
            override fun start(onVariance: (time: Long, variance: Double) -> Unit) {}
            override fun stop() {}
        }
    }

    enum class Origin { APP, WATCH }

    /** Forced inputs for the debug `watchstart` hook; consumed by the next session. */
    data class DebugOverride(
        val windowMs: Long? = null,
        val graceMs: Long? = null,
        /** Force the night classification (true/false) instead of the clock. */
        val night: Boolean? = null,
        /** Feed this HR every evaluation (a wearer at this level). */
        val hrBpm: Int? = null,
        /** Feed GPS movement every evaluation. */
        val moving: Boolean = false,
    )

    private inner class Session(val origin: Origin, val startTime: Long, var sportType: Int?, val debug: DebugOverride?) {
        val signals = ActivitySignals(windowMs = debug?.windowMs ?: windowMs)
        @Volatile var running = true
        @Volatile var armed = false
        var urgency = Urgency.NORMAL
        var monitored = true
        var autoStop: Job? = null
        var ticker: Job? = null
        var baselineLoaded = false
        var lastTrackPoints = 0
        var periodicCursor = startTime
        var debugDistance = 0.0
        var evaluations = 0

        init {
            signals.reset(startTime)
        }
    }

    private val mutex = Mutex()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var pendingDebug: DebugOverride? = null

    /** True while a warning is up (tests / diagnostics). */
    val armed: Boolean get() = session?.armed == true

    /** True while a workout (app or watch) is being watched. */
    val active: Boolean get() = session != null

    val origin: Origin? get() = session?.origin

    init {
        scope.launch { watch.events.collect { onWatchEvent(it) } }
        scope.launch { watch.liveHr.collect { onLiveHr(it) } }
        scope.launch {
            var last: WorkoutPhase? = null
            controller.state.collect { st ->
                if (st.state != last) {
                    val prev = last
                    last = st.state
                    onAppPhase(prev, st)
                }
            }
        }
    }

    /** Debug hook (adb WORKOUT watchstart): forced inputs for the next session. */
    fun debugOverride(override: DebugOverride?) {
        pendingDebug = override
        log("debug override for the next session: $override")
    }

    /** From the notification's Stop action: the user acknowledged, stop the workout now. */
    fun stopNow() {
        scope.launch { stopSession("user (notification)", speakNoActivity = false) }
    }

    // ---- inputs ----------------------------------------------------------------------------------------

    private suspend fun onAppPhase(prev: WorkoutPhase?, st: WorkoutState) {
        when (st.state) {
            WorkoutPhase.RUNNING -> if (prev == WorkoutPhase.PAUSED) resumeSession(Origin.APP) else beginSession(Origin.APP, st.sportType)
            WorkoutPhase.PAUSED -> pauseSession(Origin.APP)
            WorkoutPhase.STOPPED -> if (prev != null) endSession(Origin.APP, "app workout stopped (${st.stopReason ?: "?"})")
        }
    }

    private suspend fun onWatchEvent(e: WatchEvent) {
        when (e) {
            is WatchEvent.WorkoutControl -> when (e.action) {
                WorkoutControlAction.START -> beginSession(Origin.WATCH, null)
                WorkoutControlAction.PAUSE -> pauseSession(Origin.WATCH)
                WorkoutControlAction.RESUME -> resumeSession(Origin.WATCH)
                WorkoutControlAction.STOP -> {
                    val ended = endSession(Origin.WATCH, "watch stopped its workout")
                    if (ended) speak(StateAnnouncer.STOPPED)
                }
            }
            is WatchEvent.WorkoutRealtime -> {
                val s = session ?: return
                s.signals.onWatchSteps(clock(), e.steps)
                if (s.origin == Origin.WATCH && s.sportType == null && e.sportType > 0) {
                    s.sportType = e.sportType
                    log("watch workout sport from the realtime push: ${SportSignature.describe(e.sportType)}")
                }
            }
            else -> Unit
        }
    }

    private fun onLiveHr(sample: HrSample) {
        session?.signals?.onHr(clock(), sample.bpm)
    }

    // ---- session lifecycle ---------------------------------------------------------------------------

    private suspend fun beginSession(origin: Origin, sportType: Int?) {
        val enabled = safe("detectorEnabled", true) { detectorEnabled() }
        val toSpeak = mutex.withLock {
            val current = session
            if (current != null) {
                if (origin == Origin.WATCH) {
                    log("watch-originated START ignored: a ${current.origin.name.lowercase()} workout is already being watched")
                    return
                }
                // The app took over (its FD 11 puts the watch in the app's exercise mode): the watch-only session is done.
                tearDown(current, "superseded by an app workout")
            }
            val now = clock()
            val s = Session(origin, now, sportType, pendingDebug)
            pendingDebug = null
            s.monitored = enabled
            session = s
            if (enabled) {
                s.ticker = scope.launch {
                    while (isActive) {
                        delay(evalIntervalMs)
                        evaluate()
                    }
                }
                try {
                    motion.start { _, variance -> session?.signals?.onMotion(clock(), variance) }
                } catch (e: Exception) {
                    log("motion source failed to start: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            log(
                "watching ${origin.name.lowercase()}-originated workout: ${SportSignature.describe(sportType)}, " +
                    "window ${s.signals.windowMs / 1000} s" +
                    (if (enabled) "" else " — detector disabled in Settings, only announcing") +
                    (if (s.debug != null) ", debug ${s.debug}" else ""),
            )
            origin == Origin.WATCH
        }
        // A watch press announces itself; the app's own start is spoken by the service from the controller state.
        if (toSpeak) speak(StateAnnouncer.STARTED)
    }

    private suspend fun pauseSession(origin: Origin) = mutex.withLock {
        val s = session ?: return
        if (s.origin != origin) return
        s.running = false
        log("${origin.name.lowercase()} workout paused: judgement suspended")
    }

    private suspend fun resumeSession(origin: Origin) = mutex.withLock {
        val s = session ?: return
        if (s.origin != origin) return
        s.running = true
        s.signals.reset(clock())
        log("${origin.name.lowercase()} workout resumed: window restarted")
    }

    /** Ends the session of [origin] (if that is the one being watched); true when there was one. */
    private suspend fun endSession(origin: Origin, why: String): Boolean = mutex.withLock {
        val s = session ?: return false
        if (s.origin != origin) return false
        tearDown(s, why)
        true
    }

    /** Under the mutex. */
    private fun tearDown(s: Session, why: String) {
        s.ticker?.cancel()
        s.autoStop?.cancel()
        s.ticker = null
        s.autoStop = null
        if (s.armed) {
            s.armed = false
            try {
                alerter.clear()
            } catch (e: Exception) {
                log("alerter.clear failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (s.monitored) {
            try {
                motion.stop()
            } catch (e: Exception) {
                log("motion source failed to stop: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (session === s) session = null
        log("stopped watching the ${s.origin.name.lowercase()} workout: $why")
    }

    // ---- judgement -------------------------------------------------------------------------------------

    private suspend fun evaluate() {
        val s = session ?: return
        if (!s.running) return
        if (!s.baselineLoaded) {
            s.baselineLoaded = true
            val baseline = safe("restingBaseline", null) { restingBaseline() } ?: StuckModeDetector.FALLBACK_RESTING_HR_BPM
            s.signals.restingBaselineBpm = baseline
            log("resting HR baseline $baseline bpm (elevated from ${baseline + StuckModeDetector.HR_MARGIN_BPM}, sleeping below ${baseline + StuckModeDetector.SLEEP_MARGIN_BPM})")
        }
        val now = clock()
        // Periodic HR the watch measured on its own (AUTO pushes / synced history) — the only HR of a watch that
        // is not streaming, and what the night guard used.
        val periodic = safe("periodicHr", emptyList()) { periodicHr(s.periodicCursor, now) }
        for (h in periodic) {
            if (h.bpm > 0 && h.time > s.periodicCursor && h.time <= now &&
                (h.source == SampleSource.AUTO || h.source == SampleSource.HISTORY)
            ) {
                s.signals.onHr(h.time, h.bpm)
            }
        }
        s.periodicCursor = now

        mutex.withLock {
            if (session !== s || !s.running) return
            if (s.origin == Origin.APP) sampleController(s, now)
            s.debug?.let { d ->
                d.hrBpm?.let { s.signals.onHr(now, it) }
                if (d.moving) {
                    s.debugDistance += 20.0
                    s.signals.onGps(now, s.debugDistance, 2.0)
                }
            }
            val snap = s.signals.snapshot(now)
            val night = s.debug?.night ?: StuckModeDetector.isNight(now, zone)
            val expected = SportSignature.expected(s.sportType)
            val verdict = StuckModeDetector.evaluate(expected, snap.states, snap.windowCovered, night, snap.hrSleeping)
            s.evaluations++
            log(
                "${s.origin.name.lowercase()} ${sportName(s.sportType)} ${snap.detail} " +
                    (if (night) "night " else "") + "-> ${verdict.reason}" + (if (s.armed) " [warned]" else ""),
            )
            when {
                verdict.likelyStuck && !s.armed -> arm(s, verdict)
                s.armed && verdict.live -> disarm(s, "activity resumed (${verdict.reason})")
                else -> Unit
            }
        }
    }

    /** App session: the controller's state carries the phone steps and the GPS progress; sampled once per evaluation. */
    private fun sampleController(s: Session, now: Long) {
        val st = controller.state.value
        st.phoneSteps?.let { s.signals.onPhoneSteps(now, it) }
        if (st.trackPointCount != s.lastTrackPoints) {
            s.lastTrackPoints = st.trackPointCount
            s.signals.onGps(now, st.distanceMeters, st.speedMps)
        }
    }

    /** Under the mutex: first "stuck" verdict of this session → warn, and arm the auto-stop where it applies. */
    private suspend fun arm(s: Session, verdict: StuckModeDetector.Verdict) {
        s.armed = true
        s.urgency = verdict.urgency
        val grace = s.debug?.graceMs ?: if (verdict.urgency == Urgency.HIGH) graceHighMs else graceNormalMs
        val autoStop = when (s.origin) {
            Origin.WATCH -> true
            Origin.APP -> safe("autoStopAppWorkouts", false) { autoStopAppWorkouts() }
        }
        log(
            "LIKELY STUCK (${verdict.urgency}): ${verdict.reason}; warning" +
                if (autoStop) ", auto-stop in ${grace / 1000} s unless activity resumes or the user stops it"
                else ", no auto-stop (app-started workout; enable it in Settings)",
        )
        if (autoStop) {
            s.autoStop = scope.launch {
                log("grace timer started: ${grace} ms")
                delay(grace)
                log("grace timer fired after ${grace / 1000} s")
                onGraceElapsed(s, grace)
            }.also { job -> job.invokeOnCompletion { cause -> if (cause != null) log("grace timer ended early: ${cause.javaClass.simpleName} ${cause.message ?: ""}") } }
        }
        speak(WARN_SPEECH)
        try {
            alerter.warn(verdict.urgency, WARN_TITLE, WARN_TEXT)
        } catch (e: Exception) {
            log("alerter.warn failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Under the mutex. */
    private fun disarm(s: Session, why: String) {
        s.armed = false
        s.autoStop?.cancel()
        s.autoStop = null
        log("warning withdrawn: $why")
        try {
            alerter.clear()
        } catch (e: Exception) {
            log("alerter.clear failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun onGraceElapsed(s: Session, grace: Long) {
        // This runs INSIDE the grace timer's coroutine: detach it from the session first, or stopSession() would
        // cancel the very job that is executing the stop and abort it half-way (found by StuckWorkoutMonitorTest).
        val proceed = mutex.withLock {
            val ok = session === s && s.armed
            if (ok) s.autoStop = null
            ok
        }
        if (!proceed) {
            log("grace elapsed but the session is no longer armed/current: ignored")
            return
        }
        log("grace (${grace / 1000} s) elapsed without acknowledgement or activity: auto-stopping the workout")
        stopSession("auto-stop, no activity", speakNoActivity = true)
    }

    /**
     * Stops the watched workout: an app session through the controller (which tells the watch, finalises the row
     * and makes the service speak the stop reason), a watch-only session with `FD 00` directly.
     */
    private suspend fun stopSession(reason: String, speakNoActivity: Boolean) {
        val self = kotlin.coroutines.coroutineContext[Job]
        val s = mutex.withLock {
            val current = session ?: return
            current.autoStop?.let { if (it !== self) it.cancel() }
            current.autoStop = null
            current
        }
        log("stopping the ${s.origin.name.lowercase()} workout ($reason)")
        when (s.origin) {
            Origin.APP -> {
                // The controller's STOPPED transition comes back through onAppPhase and ends the session.
                try {
                    controller.stop(reason = if (speakNoActivity) StopReason.NO_ACTIVITY else StopReason.USER)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("controller.stop failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            Origin.WATCH -> {
                try {
                    watch.stopWorkout()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("stopWorkout failed: ${e.message ?: e.javaClass.simpleName}")
                }
                val ended = endSession(Origin.WATCH, reason)
                if (ended) speak(if (speakNoActivity) StateAnnouncer.STOPPED_NO_ACTIVITY else StateAnnouncer.STOPPED)
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private fun speak(text: String) {
        try {
            speaker(text)
        } catch (e: Exception) {
            log("speak failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun sportName(sportType: Int?): String = if (sportType == null) "sport ?" else SportTypes.name(sportType)

    private suspend fun <T> safe(what: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$what failed: ${e.message ?: e.javaClass.simpleName}")
        fallback
    }

    companion object {
        /** How often the signals are judged. */
        const val EVAL_INTERVAL_MS = 30_000L

        const val WARN_SPEECH = "Workout running but no activity detected. Tap to stop."
        const val WARN_TITLE = "Workout running but no activity detected"
        const val WARN_TEXT = "Tap to stop it"
    }
}
