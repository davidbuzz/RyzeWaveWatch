package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.GpsDistanceTracker
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.core.Workout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000L,
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

    private var hrJob: Job? = null
    private var tickerJob: Job? = null
    private var watchUpdateJob: Job? = null

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

    suspend fun pause(): Unit = control.withLock {
        if (_state.value.state != WorkoutPhase.RUNNING) return@withLock
        val now = clock()
        runningSince?.let { activeMsBefore += max(0L, now - it) }
        runningSince = null
        val elapsed = elapsedSeconds(now)
        _state.update { it.copy(state = WorkoutPhase.PAUSED, elapsedSeconds = elapsed, speedMps = 0.0, paceSecPerKm = 0.0) }
        watchCall("pauseWorkout") { watch.pauseWorkout() }
        flush()
        persistWorkout(end = null)
    }

    suspend fun resume(): Unit = control.withLock {
        if (_state.value.state != WorkoutPhase.PAUSED) return@withLock
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
        watchCall("resumeWorkout") { watch.resumeWorkout() }
    }

    /**
     * Ends the workout: stops the ticker and HR stream, tells the watch (`FD 00 <type> 01`), flushes the buffers
     * and writes the final [Workout] row (end, distance, duration, avg/max HR, calories). Returns that row, or
     * null when nothing was running.
     */
    suspend fun stop(): Workout? = control.withLock {
        if (_state.value.state == WorkoutPhase.STOPPED) return@withLock null
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
        _state.update {
            it.copy(
                state = WorkoutPhase.STOPPED, elapsedSeconds = elapsed, distanceMeters = distance,
                calories = calories, speedMps = 0.0,
                paceSecPerKm = if (distance > 0.0 && elapsed > 0) elapsed / distance * 1000.0 else 0.0,
            )
        }
        watchCall("stopWorkout") { watch.stopWorkout() }
        flush()
        val final = buildWorkout(end = now, elapsed = elapsed, distance = distance)
        try {
            repo.updateWorkout(final)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("updateWorkout(final) failed", e)
            setError("save: ${e.message}")
        }
        try {
            onFinished(final)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("onFinished failed", e)
        }
        final
    }

    /**
     * Feed a location fix (any thread). While RUNNING it goes through the distance filter and is stored as a
     * [TrackPoint] (accepted or not); while PAUSED only the GPS quality is updated.
     */
    fun onLocation(time: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float, altitudeM: Double?) {
        val phase = _state.value.state
        if (phase == WorkoutPhase.STOPPED) return
        lastFixWallTime = clock()
        if (phase == WorkoutPhase.PAUSED) {
            _state.update { it.copy(gpsAccuracyM = accuracyM, gpsAvailable = true) }
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
            )
            pace = tracker.paceSecPerKm
            speed = tracker.speedMps
        }
        _state.update {
            it.copy(
                distanceMeters = distance, paceSecPerKm = pace, speedMps = speed,
                gpsAccuracyM = accuracyM, gpsAvailable = true,
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
                    speedMps = speed, calories = calories,
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
