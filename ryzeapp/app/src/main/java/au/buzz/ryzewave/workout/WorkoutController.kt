package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.GpsDistanceTracker
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.StrideModel
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.core.WorkoutControlAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Drives one workout at a time: keeps the clock, the GPS distance model, the live HR stream and the watch
 * face in sync, and persists a [Workout] row plus its [TrackPoint]s / HR samples through the repository.
 *
 * Threading: [start], [pause], [resume], [stop] are suspend and serialised by a mutex; [onLocation],
 * [onGpsAvailability] and [reportError] can be called from any thread (the location callback runs on the
 * main looper). A ticker on [scope] fires every [tickMs]: it updates elapsed time / calories, pushes the
 * metrics to the watch with `FD 44` (skipping a tick if the previous push is still in flight) and flushes
 * buffered track points and HR samples to the repository.
 *
 * Calories: every tick that the GPS distance grew is credited from the ACSM walking/running equations for the
 * *segment* covered since the last progress (so a stretch bridged after a GPS outage is credited for its
 * distance, not for the resting rate the ticks in between used); ticks without progress are credited from the
 * live heart rate (Keytel et al. 2005) when it is fresh, else at the standing rate. The credit given while
 * waiting for progress is netted against the segment credit so a bridged gap is not counted twice.
 *
 * No Android imports here so it is unit-testable with fakes; [WorkoutService] hosts the process-wide instance.
 */
class WorkoutController(
    private val repo: HealthRepository,
    private val watch: WatchApi,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val tracker: GpsDistanceTracker = DefaultGpsDistanceTracker(),
    private val strideModel: StrideModel = DefaultStrideModel(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000L,
    /**
     * How long a watch-originated pause/resume must stay SETTLED before it is applied. The watch has been seen to
     * flood `FD 22`/`FD 33` every 1-5 s with junk payloads (captures/pixel_run2_20260906); a reversal within this
     * window is treated as noise and ignored, so a single spurious toggle never thrashes the workout or the TTS.
     * App-button pause/resume are applied immediately (they never go through this gate).
     */
    private val watchControlDebounceMs: Long = WATCH_CONTROL_DEBOUNCE_MS,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
    /** Called once per workout, after [stop] has written the final row (e.g. to export it to Health Connect). */
    private val onFinished: (Workout) -> Unit = {},
) {
    private val _state = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    /** Serialises the control operations and the ticker against each other. */
    private val control = Mutex()

    /** Guards the tracker, the pending buffers and the HR counters (touched from non-suspend callbacks). */
    private val lock = Any()
    private val pendingPoints = ArrayList<TrackPoint>()
    private val pendingHr = ArrayList<HrSample>()
    private var hrSum = 0L
    private var hrCount = 0
    private var hrMax = 0

    @Volatile private var workoutId = 0L
    @Volatile private var sportType = 1
    @Volatile private var startTime = 0L
    @Volatile private var activeMsBefore = 0L
    @Volatile private var runningSince: Long? = null
    @Volatile private var lastTickTime = 0L
    @Volatile private var lastFixWallTime = 0L
    private var lastPersistedElapsed = -1
    private var weightKg = UserProfile().weightKg.toDouble()
    private var ageYears = UserProfile().age
    private var male = UserProfile().male
    private var caloriesKcal = 0.0
    private var calDistanceAtProgress = 0.0
    private var calProgressTime = 0L
    private var calCreditedSinceProgress = 0.0
    private var calRegimeSpeed = 0.0
    @Volatile private var lastHrTime = 0L

    /** Highest per-session step count seen in the watch's realtime pushes; the workout's step total. */
    @Volatile private var watchStepsMax = 0
    /** Steps counted by the phone's step counter for this workout (paused excluded); -1 = none reported. */
    @Volatile private var phoneStepsValue = -1

    /** Per-session stride (metres/step), read from settings at [start]; feeds the step-based distance estimate. */
    @Volatile private var walkStrideM = 0.0
    @Volatile private var runStrideM = 0.0

    /** Guards the watch-control debounce state below (mutated from the events collector and the debounce timer). */
    private val watchGate = Any()
    /** The phase a watch pause/resume is waiting to settle into, or null when nothing is pending. */
    private var pendingWatchPhase: WorkoutPhase? = null
    private var watchDebounceJob: Job? = null

    private var hrJob: Job? = null
    private var tickerJob: Job? = null
    private var watchUpdateJob: Job? = null

    init {
        // Watch-originated control (its physical buttons) and realtime step pushes drive the SAME state machine
        // as the app's buttons. A long-lived collector (not tied to start/stop) so a watch STOP can call stop()
        // without cancelling the coroutine it runs on.
        scope.launch { watch.events.collect { onWatchEvent(it) } }
    }

    /** Applies a watch-originated event. Control is ignored unless it makes sense for the current phase. */
    private suspend fun onWatchEvent(e: WatchEvent) {
        when (e) {
            is WatchEvent.WorkoutControl -> when (e.action) {
                // Pause/resume from the watch are DEBOUNCED: the watch floods junk FD 22/FD 33; only a state that
                // settles beyond the window is applied and announced. A reversal within the window is dropped as noise.
                WorkoutControlAction.PAUSE -> onWatchPauseResume(WorkoutPhase.PAUSED)
                WorkoutControlAction.RESUME -> onWatchPauseResume(WorkoutPhase.RUNNING)
                WorkoutControlAction.STOP -> if (_state.value.state != WorkoutPhase.STOPPED) stop(fromWatch = true)
                // The app cannot meaningfully begin a session from a watch press here (no GPS/foreground): ignore.
                WorkoutControlAction.START -> Unit
            }
            is WatchEvent.WorkoutRealtime -> onWatchSteps(e.steps)
            else -> Unit
        }
    }

    /**
     * A watch-originated pause ([want] = PAUSED) or resume ([want] = RUNNING). It is not applied immediately: the
     * watch echoes these controls in floods of junk 13-byte `FD 22`/`FD 33` (captures/pixel_run2_20260906), so we
     * wait [watchControlDebounceMs] and only apply the change if the watch is still asking for the opposite of the
     * current state — a reversal (or a return to the current state) within the window cancels the pending change.
     */
    private fun onWatchPauseResume(want: WorkoutPhase) {
        synchronized(watchGate) {
            val current = _state.value.state
            if (current == WorkoutPhase.STOPPED) {
                cancelPendingWatchControlLocked()          // no active workout: nothing to pause/resume
                return
            }
            when {
                // The watch is back on the current state: a reversal of whatever was pending — treat as noise.
                want == current -> cancelPendingWatchControlLocked()
                // Same target already pending: let the running timer decide, do not restart it.
                want == pendingWatchPhase -> Unit
                // A new, opposite target: (re)arm the settle timer.
                else -> {
                    pendingWatchPhase = want
                    watchDebounceJob?.cancel()
                    watchDebounceJob = scope.launch {
                        delay(watchControlDebounceMs)
                        settleWatchControl(want)
                    }
                }
            }
        }
    }

    /** Fires [watchControlDebounceMs] after a watch pause/resume: applies it iff it is still the pending, opposite state. */
    private suspend fun settleWatchControl(want: WorkoutPhase) {
        synchronized(watchGate) {
            if (pendingWatchPhase != want) return          // superseded, reversed, or cancelled while we waited
            pendingWatchPhase = null
            watchDebounceJob = null
        }
        when (want) {
            WorkoutPhase.PAUSED -> pause(fromWatch = true)
            WorkoutPhase.RUNNING -> resume(fromWatch = true)
            WorkoutPhase.STOPPED -> Unit
        }
    }

    private fun cancelPendingWatchControlLocked() {
        pendingWatchPhase = null
        watchDebounceJob?.cancel()
        watchDebounceJob = null
    }

    /** Clears any pending watch pause/resume (a user action or a new session overrides watch noise). */
    private fun cancelPendingWatchControl() = synchronized(watchGate) { cancelPendingWatchControlLocked() }

    /**
     * Starts a workout: inserts the [Workout] row, subscribes to [WatchApi.liveHr], tells the watch
     * (`FD 11 <type> 01`) and starts the one-second ticker. Returns the workout id. If a workout is already
     * active, it is left alone and its id is returned.
     */
    suspend fun start(sportType: Int = 1): Long = control.withLock {
        if (_state.value.state != WorkoutPhase.STOPPED) return@withLock workoutId
        val now = clock()
        val profile = try {
            settings.profile.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("profile unavailable, using defaults", e)
            UserProfile()
        }
        weightKg = profile.weightKg.toDouble().takeIf { it > 0.0 } ?: UserProfile().weightKg.toDouble()
        ageYears = profile.age.takeIf { it > 0 } ?: UserProfile().age
        male = profile.male
        val stride = try {
            settings.stride.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("stride settings unavailable, using defaults", e)
            StrideSettings()
        }
        walkStrideM = strideModel.walkStrideM(profile, stride)
        runStrideM = strideModel.runStrideM(profile, stride)

        cancelPendingWatchControl()
        synchronized(lock) {
            tracker.reset()
            pendingPoints.clear()
            pendingHr.clear()
            hrSum = 0L
            hrCount = 0
            hrMax = 0
        }
        this.sportType = sportType
        startTime = now
        activeMsBefore = 0L
        runningSince = now
        lastTickTime = now
        lastFixWallTime = 0L
        lastPersistedElapsed = -1
        caloriesKcal = 0.0
        calDistanceAtProgress = 0.0
        calProgressTime = now
        calCreditedSinceProgress = 0.0
        calRegimeSpeed = 0.0
        lastHrTime = 0L
        watchStepsMax = 0
        phoneStepsValue = -1

        val id = repo.insertWorkout(
            Workout(
                start = now, end = null, sportType = sportType, distanceMeters = 0.0,
                durationSeconds = 0, avgHr = null, maxHr = null, calories = 0,
            )
        )
        workoutId = id
        val previous = _state.value
        _state.value = WorkoutState(
            state = WorkoutPhase.RUNNING, workoutId = id, sportType = sportType, startTime = now,
            gpsAvailable = previous.gpsAvailable,
            walkStrideMeters = walkStrideM, runStrideMeters = runStrideM,
            // The service reports GPS problems before it asks us to start: keep them visible.
            hostError = previous.hostError, errorSeq = previous.errorSeq,
        )

        hrJob?.cancel()
        // UNDISPATCHED so the subscription exists before the watch is told to start streaming.
        hrJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            watch.liveHr.collect { onHr(it) }
        }
        watchCall("startWorkout") { watch.startWorkout(sportType) }

        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                tick()
            }
        }
        id
    }

    /**
     * Pauses the workout. [fromWatch] is set when the watch itself paused (an unsolicited `FD 22`): the watch
     * has already changed state, so the pause is NOT sent back to it — that would loop. An app-button pause
     * ([fromWatch] false) still sends `FD 22`. Either way the state change is identical to the rest of the app.
     */
    suspend fun pause(fromWatch: Boolean = false): Unit = control.withLock {
        if (_state.value.state != WorkoutPhase.RUNNING) return@withLock
        if (!fromWatch) cancelPendingWatchControl()    // an app-button pause overrides any pending watch noise
        val now = clock()
        runningSince?.let { activeMsBefore += max(0L, now - it) }
        runningSince = null
        val elapsed = elapsedSeconds(now)
        _state.update { it.copy(state = WorkoutPhase.PAUSED, elapsedSeconds = elapsed, speedMps = 0.0, paceSecPerKm = 0.0) }
        if (!fromWatch) watchCall("pauseWorkout") { watch.pauseWorkout() }
        flush()
        persistWorkout(end = null)
    }

    /** Resumes a paused workout. [fromWatch] (an unsolicited `FD 33`) skips sending `FD 33` back to the watch. */
    suspend fun resume(fromWatch: Boolean = false): Unit = control.withLock {
        if (_state.value.state != WorkoutPhase.PAUSED) return@withLock
        if (!fromWatch) cancelPendingWatchControl()    // an app-button resume overrides any pending watch noise
        val now = clock()
        runningSince = now
        lastTickTime = now
        synchronized(lock) {
            (tracker as? DefaultGpsDistanceTracker)?.markGap()
            calDistanceAtProgress = tracker.distanceMeters
        }
        calProgressTime = now
        calCreditedSinceProgress = 0.0
        _state.update { it.copy(state = WorkoutPhase.RUNNING) }
        if (!fromWatch) watchCall("resumeWorkout") { watch.resumeWorkout() }
    }

    /**
     * Ends the workout whether it is RUNNING or PAUSED: stops the ticker and HR stream, tells the watch
     * (`FD 00 <type> 01`, unless [fromWatch] — the watch already stopped itself), flushes the buffers and writes
     * the final [Workout] row (end, active-time duration, distance, avg/max HR, calories, steps). Returns that
     * row, or null when nothing was running. [reason] is published as [WorkoutState.stopReason] (defaults to
     * WATCH / USER by [fromWatch]); the stuck-workout detector passes [StopReason.NO_ACTIVITY] so the spoken
     * cue says why.
     */
    suspend fun stop(fromWatch: Boolean = false, reason: StopReason? = null): Workout? = control.withLock {
        if (_state.value.state == WorkoutPhase.STOPPED) return@withLock null
        cancelPendingWatchControl()
        val why = reason ?: if (fromWatch) StopReason.WATCH else StopReason.USER
        val now = clock()
        runningSince?.let { activeMsBefore += max(0L, now - it) }
        runningSince = null
        tickerJob?.cancel()
        tickerJob = null
        hrJob?.cancel()
        hrJob = null
        // let an in-flight FD 44 finish rather than cancelling half-way through a GATT write
        watchUpdateJob?.let { job -> withTimeoutOrNull(WATCH_TIMEOUT_MS) { job.join() } }
        watchUpdateJob = null

        val elapsed = elapsedSeconds(now)
        val distance = synchronized(lock) { tracker.distanceMeters }
        val calories = caloriesKcal.roundToInt()
        // Publishing STOPPED makes WorkoutService stop itself, which cancels the very scope this runs on
        // (onDestroy -> scope.cancel()). Everything after the update — the watch stop command, the buffer
        // flush and above all the final row write that sets `end` — must therefore be shielded from that
        // cancellation, or the workout stays flagged "in progress" forever (seen 2026-09-07: four rows).
        _state.update {
            it.copy(
                state = WorkoutPhase.STOPPED, elapsedSeconds = elapsed, distanceMeters = distance,
                calories = calories, speedMps = 0.0,
                paceSecPerKm = if (distance > 0.0 && elapsed > 0) elapsed / distance * 1000.0 else 0.0,
                stopReason = why,
            )
        }
        val final = buildWorkout(end = now, elapsed = elapsed, distance = distance)
        withContext(NonCancellable) {
            if (!fromWatch) watchCall("stopWorkout") { watch.stopWorkout() }
            flush()
            try {
                repo.updateWorkout(final)
            } catch (e: Exception) {
                onError("updateWorkout(final) failed", e)
                setError("save: ${e.message}")
            }
            try {
                onFinished(final)
            } catch (e: Exception) {
                onError("onFinished failed", e)
            }
        }
        final
    }

    /**
     * Feed a location fix (any thread). While RUNNING it goes through the distance filter and is stored as a
     * [TrackPoint] (accepted or not). While PAUSED the fix is still STORED — tagged [TrackPoint.paused], never
     * counted (accepted = false) and adding no distance — so the track stays continuous and a missed resume
     * never loses the route. GPS is left running on pause for exactly this reason (see [WorkoutService]).
     */
    fun onLocation(time: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float, altitudeM: Double?) {
        val phase = _state.value.state
        if (phase == WorkoutPhase.STOPPED) return
        lastFixWallTime = clock()
        if (phase == WorkoutPhase.PAUSED) {
            val distance: Double
            synchronized(lock) {
                distance = tracker.distanceMeters            // unchanged: paused fixes must not add distance
                pendingPoints += TrackPoint(
                    workoutId = workoutId, time = time, lat = lat, lon = lon, accuracyM = accuracyM,
                    speedMps = speedMps, altitudeM = altitudeM, accepted = false, cumulativeM = distance,
                    paused = true, steps = stepsSoFar(),
                )
            }
            _state.update {
                it.copy(gpsAccuracyM = accuracyM, gpsAvailable = true, trackPointCount = it.trackPointCount + 1)
            }
            return
        }
        val accepted: Boolean
        val distance: Double
        val pace: Double
        val speed: Double
        synchronized(lock) {
            accepted = tracker.addFix(time, lat, lon, accuracyM, speedMps)
            distance = tracker.distanceMeters
            pendingPoints += TrackPoint(
                workoutId = workoutId, time = time, lat = lat, lon = lon, accuracyM = accuracyM,
                speedMps = speedMps, altitudeM = altitudeM, accepted = accepted, cumulativeM = distance,
                steps = stepsSoFar(),
            )
            pace = tracker.paceSecPerKm
            speed = tracker.speedMps
        }
        _state.update {
            it.copy(
                distanceMeters = distance, paceSecPerKm = pace, speedMps = speed,
                gpsAccuracyM = accuracyM, gpsAvailable = true, gpsStale = false,
                trackPointCount = it.trackPointCount + 1,
                acceptedPointCount = it.acceptedPointCount + (if (accepted) 1 else 0),
            )
        }
    }

    /** From the location provider's availability callback. */
    fun onGpsAvailability(available: Boolean) {
        _state.update { if (it.gpsAvailable == available) it else it.copy(gpsAvailable = available) }
    }

    /**
     * The watch's per-session step count from a realtime push (any thread). The maximum seen is the workout's
     * step total; it only ever rises. Ignored once STOPPED.
     */
    /**
     * The session step count to stamp on a track point: the watch's own per-workout total, or the phone's when
     * the watch has not reported one. Both only ever rise, so the stored series is monotonic and a later
     * calibration can difference it over any window ([StrideCalibration]).
     */
    private fun stepsSoFar(): Int? = when {
        watchStepsMax > 0 -> watchStepsMax
        phoneStepsValue > 0 -> phoneStepsValue
        else -> null
    }

    fun onWatchSteps(steps: Int) {
        if (_state.value.state == WorkoutPhase.STOPPED) return
        if (steps <= watchStepsMax) return
        watchStepsMax = steps
        _state.update { it.copy(steps = steps) }
    }

    /** The phone step counter's tally for this workout (paused steps already excluded). Ignored once STOPPED. */
    fun onPhoneSteps(steps: Int) {
        if (_state.value.state == WorkoutPhase.STOPPED) return
        if (steps < 0 || steps == phoneStepsValue) return
        phoneStepsValue = steps
        _state.update { it.copy(phoneSteps = steps) }
    }

    /**
     * Surface a problem from the host service (missing location permission, GPS unavailable, foreground start
     * refused). It stays in [WorkoutState.hostError] until [clearReportedError]; watch calls do not touch it.
     */
    fun reportError(message: String) {
        _state.update { it.copy(hostError = message, errorSeq = it.errorSeq + 1) }
    }

    /** The host's problem is gone (e.g. location updates started). */
    fun clearReportedError() {
        _state.update { if (it.hostError == null) it else it.copy(hostError = null, errorSeq = it.errorSeq + 1) }
    }

    // ---- internals -------------------------------------------------------------------------------------

    private fun onHr(sample: HrSample) {
        if (_state.value.state == WorkoutPhase.STOPPED) return
        if (sample.bpm <= 0) return
        val tagged = HrSample(time = sample.time, bpm = sample.bpm, source = SampleSource.WORKOUT)
        lastHrTime = clock()
        val avg: Int
        val maxHr: Int
        synchronized(lock) {
            pendingHr += tagged
            hrSum += sample.bpm
            hrCount++
            hrMax = max(hrMax, sample.bpm)
            avg = (hrSum / hrCount).toInt()
            maxHr = hrMax
        }
        _state.update {
            it.copy(lastHr = sample.bpm, avgHr = avg, maxHr = maxHr, hrSamples = appendCapped(it.hrSamples, tagged))
        }
    }

    private suspend fun tick(): Unit = control.withLock {
        val phase = _state.value.state
        val now = clock()
        if (phase == WorkoutPhase.RUNNING) {
            val dtS = (now - lastTickTime).coerceIn(0L, MAX_TICK_GAP_MS) / 1000.0
            lastTickTime = now
            val stale = lastFixWallTime > 0L && now - lastFixWallTime > STALE_FIX_MS
            val distance: Double
            val pace: Double
            val speed: Double
            synchronized(lock) {
                distance = tracker.distanceMeters
                pace = if (stale) 0.0 else tracker.paceSecPerKm
                speed = if (stale) 0.0 else tracker.speedMps
            }
            creditCalories(now, dtS, distance, speed)
            val calories = caloriesKcal.roundToInt()
            val elapsed = elapsedSeconds(now)
            _state.update {
                it.copy(
                    elapsedSeconds = elapsed, distanceMeters = distance, paceSecPerKm = pace,
                    speedMps = speed, calories = calories, gpsStale = stale,
                )
            }
            pushToWatch(elapsed, distance, pace, calories)
            flush()
            if (elapsed - lastPersistedElapsed >= PERSIST_EVERY_S) {
                lastPersistedElapsed = elapsed
                persistWorkout(end = null)
            }
        } else {
            lastTickTime = now
            flush()      // HR keeps arriving while paused
        }
    }

    /**
     * One tick of the calorie model (see the class KDoc). [distance] is the tracker's cumulative distance now,
     * [dopplerSpeed] the receiver speed of the last fix (0 when stale) — only used to pick walk vs run when a
     * bridged segment implies an impossible speed.
     */
    private fun creditCalories(now: Long, dtS: Double, distance: Double, dopplerSpeed: Double) {
        val dd = distance - calDistanceAtProgress
        if (dd > 0.0) {
            val segS = max((now - calProgressTime) / 1000.0, dtS).coerceAtLeast(0.001)
            val v = dd / segS
            val regime = when {
                v <= MAX_PLAUSIBLE_MPS -> v.also { calRegimeSpeed = it }
                calRegimeSpeed > 0.0 -> calRegimeSpeed
                dopplerSpeed > 0.0 -> dopplerSpeed
                else -> v
            }
            val segment = caloriesForSegment(dd, segS, regime, weightKg)
            caloriesKcal += max(segment - calCreditedSinceProgress, 0.0)
            calCreditedSinceProgress = 0.0
            calDistanceAtProgress = distance
            calProgressTime = now
        } else {
            val hr = _state.value.lastHr
            val hrFresh = hr != null && hr > 0 && lastHrTime > 0L && now - lastHrTime <= HR_FRESH_MS
            val perSecond = if (hrFresh) {
                max(hrCaloriesPerMinute(hr!!, weightKg, ageYears, male), STANDING_MET * weightKg / 60.0) / 60.0
            } else {
                STANDING_MET * weightKg / 3600.0
            }
            val credit = perSecond * dtS
            caloriesKcal += credit
            calCreditedSinceProgress += credit
        }
    }

    private fun pushToWatch(elapsed: Int, distance: Double, pace: Double, calories: Int) {
        if (watchUpdateJob?.isActive == true) return   // previous FD 44 still on the BLE queue: skip this second
        watchUpdateJob = scope.launch {
            watchCall("updateWorkout", timeoutMs = UPDATE_TIMEOUT_MS) {
                watch.updateWorkout(elapsed, distance, pace, calories)
            }
        }
    }

    private suspend fun flush() {
        val points: List<TrackPoint>
        val hr: List<HrSample>
        synchronized(lock) {
            points = if (pendingPoints.isEmpty()) emptyList() else ArrayList(pendingPoints)
            pendingPoints.clear()
            hr = if (pendingHr.isEmpty()) emptyList() else ArrayList(pendingHr)
            pendingHr.clear()
        }
        if (points.isNotEmpty()) {
            try {
                repo.insertTrackPoints(points)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("insertTrackPoints failed (${points.size} dropped)", e)
            }
        }
        if (hr.isNotEmpty()) {
            try {
                repo.upsertHr(hr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("upsertHr failed (${hr.size} dropped)", e)
            }
        }
    }

    private suspend fun persistWorkout(end: Long?) {
        val now = clock()
        val distance = synchronized(lock) { tracker.distanceMeters }
        val w = buildWorkout(end = end, elapsed = elapsedSeconds(now), distance = distance)
        try {
            repo.updateWorkout(w)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("updateWorkout failed", e)
        }
    }

    private fun buildWorkout(end: Long?, elapsed: Int, distance: Double): Workout {
        val avg: Int?
        val maxHr: Int?
        synchronized(lock) {
            avg = if (hrCount > 0) (hrSum / hrCount).toInt() else null
            maxHr = if (hrCount > 0) hrMax else null
        }
        return Workout(
            id = workoutId, start = startTime, end = end, sportType = sportType,
            distanceMeters = distance, durationSeconds = elapsed, avgHr = avg, maxHr = maxHr,
            calories = caloriesKcal.roundToInt(),
            steps = if (watchStepsMax > 0) watchStepsMax else null,
            phoneSteps = if (phoneStepsValue >= 0) phoneStepsValue else null,
        )
    }

    private fun elapsedSeconds(now: Long): Int {
        val running = runningSince?.let { max(0L, now - it) } ?: 0L
        return ((activeMsBefore + running) / 1000L).toInt()
    }

    /** Runs a watch call with a timeout; failures are logged and shown in the state but never abort the workout. */
    private suspend fun watchCall(name: String, timeoutMs: Long = WATCH_TIMEOUT_MS, block: suspend () -> Unit): Boolean {
        return try {
            val ok = withTimeoutOrNull(timeoutMs) { block(); true } ?: false
            if (ok) clearError() else setError("$name: watch did not answer")
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("$name failed", e)
            setError("$name: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(error = message, errorSeq = it.errorSeq + 1) }
    }

    /** Only the watch-call error is cleared; a host problem ([WorkoutState.hostError]) stays. */
    private fun clearError() {
        _state.update { if (it.error == null) it else it.copy(error = null, errorSeq = it.errorSeq + 1) }
    }

    private fun appendCapped(list: List<HrSample>, sample: HrSample): List<HrSample> =
        if (list.size < MAX_HR_SAMPLES) list + sample else list.drop(list.size - MAX_HR_SAMPLES + 1) + sample

    companion object {
        const val WATCH_TIMEOUT_MS = 5_000L
        const val UPDATE_TIMEOUT_MS = 3_000L
        const val STALE_FIX_MS = 15_000L

        /**
         * A watch-originated pause/resume must stay settled this long before it is applied and announced. Longer than
         * the watch's junk `FD 22`/`FD 33` flood interval (1-5 s seen on 2026-09-06), so a spurious toggle is dropped.
         */
        const val WATCH_CONTROL_DEBOUNCE_MS = 8_000L
        const val PERSIST_EVERY_S = 5
        const val MAX_TICK_GAP_MS = 60_000L
        const val MAX_HR_SAMPLES = 6 * 3600

        /** Standing / very slow: 1.3 MET. */
        const val STANDING_MET = 1.3

        /** Live HR older than this is not used for the calorie estimate. */
        const val HR_FRESH_MS = 15_000L

        /** Above this a segment speed is a bridged GPS gap, not a running speed (matches the tracker's spike cap). */
        const val MAX_PLAUSIBLE_MPS = 12.0

        /** The ACSM walking equation holds to ~1.9 m/s and the running one from ~2.2 m/s: blend in between. */
        const val WALK_RUN_BLEND_FROM_MPS = 1.8
        const val WALK_RUN_BLEND_TO_MPS = 2.2

        /** 0 = pure walking equation, 1 = pure running equation, linear in between. */
        fun runWeight(speedMps: Double): Double =
            ((speedMps - WALK_RUN_BLEND_FROM_MPS) / (WALK_RUN_BLEND_TO_MPS - WALK_RUN_BLEND_FROM_MPS)).coerceIn(0.0, 1.0)

        /**
         * Metabolic equivalent for a ground speed, from the ACSM walking (VO2 = 0.1·v + 3.5) and running
         * (VO2 = 0.2·v + 3.5, v in m/min) equations, 1 MET = 3.5 ml/kg/min. Below 0.3 m/s we are standing
         * (1.3 MET); pure walking up to 1.8 m/s, pure running from 2.2 m/s (7.9 km/h), a linear blend in
         * between so Doppler noise around 7 km/h does not flip the rate by 80 % every second.
         */
        fun metForSpeed(speedMps: Double): Double {
            if (speedMps.isNaN() || speedMps < 0.3) return STANDING_MET
            val vMin = speedMps * 60.0
            val walk = 1.0 + 0.1 * vMin / 3.5
            val run = 1.0 + 0.2 * vMin / 3.5
            val w = runWeight(speedMps)
            return (1.0 - w) * walk + w * run
        }

        /** kcal burned in [seconds] at [speedMps] for a [weightKg] body: MET × kg × h. */
        fun caloriesFor(speedMps: Double, weightKg: Double, seconds: Double): Double =
            metForSpeed(speedMps) * weightKg * seconds / 3600.0

        /**
         * kcal for a segment of [meters] covered in [seconds]: the time term (1 MET) plus the ACSM distance term
         * with the walk/run slope chosen by [regimeSpeedMps]. Equals [caloriesFor] when the regime speed is the
         * segment's own speed; for a bridged gap it credits the distance at the pre-gap regime.
         */
        fun caloriesForSegment(meters: Double, seconds: Double, regimeSpeedMps: Double, weightKg: Double): Double {
            if (meters <= 0.0 || seconds <= 0.0) return 0.0
            val w = runWeight(regimeSpeedMps)
            val slope = 0.1 + 0.1 * w                       // ml/kg/min per m/min
            val timeMet = if (regimeSpeedMps < 0.3) STANDING_MET else 1.0
            return (timeMet * seconds + slope * meters * 60.0 / 3.5) * weightKg / 3600.0
        }

        /**
         * Heart-rate based energy expenditure, Keytel et al. (2005), kcal/min; can go negative at very low HR,
         * callers floor it at the standing rate.
         */
        fun hrCaloriesPerMinute(hr: Int, weightKg: Double, ageYears: Int, male: Boolean): Double =
            if (male) (-55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * ageYears) / 4.184
            else (-20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.0740 * ageYears) / 4.184
    }
}
