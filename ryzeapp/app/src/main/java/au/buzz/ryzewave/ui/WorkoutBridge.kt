package au.buzz.ryzewave.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import au.buzz.ryzewave.App
import au.buzz.ryzewave.Graph
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class WorkoutPhase { IDLE, STARTING, RUNNING, PAUSED, STOPPING }

/** What the Workout screen shows while a session runs. Mirrors `workout.WorkoutState` (elapsed, distance, pace, hr, gps). */
data class WorkoutUiState(
    val phase: WorkoutPhase = WorkoutPhase.IDLE,
    val sportType: Int = UiDefaults.DEFAULT_SPORT_TYPE,
    val workoutId: Long = 0L,
    val startTime: Long = 0L,
    val elapsedSeconds: Int = 0,
    val distanceMeters: Double = 0.0,
    val paceSecPerKm: Double = 0.0,
    val speedMps: Double = 0.0,
    val hr: Int? = null,
    val calories: Int = 0,
    val gpsAccuracyM: Float? = null,
    val gpsFixes: Int = 0,
    val gpsAccepted: Int = 0,
    val message: String? = null,
) {
    val active: Boolean get() = phase == WorkoutPhase.RUNNING || phase == WorkoutPhase.PAUSED

    /**
     * Coarse GPS quality label from the last fix's accuracy, aligned with the tracker's bands: "good" and "fair"
     * fixes give an accurate distance, "usable" (≤ 60 m, the tracker's hard cut) still measures but coarsely
     * without a Doppler speed, "poor" fixes are dropped and the distance stalls.
     */
    val gpsQuality: String
        get() = when {
            gpsAccuracyM == null -> "no fix"
            gpsAccuracyM <= GPS_GOOD_M -> "good"
            gpsAccuracyM <= GPS_FAIR_M -> "fair"
            gpsAccuracyM <= GPS_USABLE_M -> "usable"
            else -> "poor"
        }

    companion object {
        const val GPS_GOOD_M = 10f
        const val GPS_FAIR_M = 20f
        /** Same as the tracker's maxAccuracyM (DefaultGpsDistanceTracker): beyond this the fix is dropped. */
        const val GPS_USABLE_M = 60f
    }
}

/**
 * The UI's view of a workout session. The `workout` package's `WorkoutController` is adapted to this
 * interface by the integration step via [WorkoutBridgeHolder.install]; until that happens
 * [WatchWorkoutBridge] (watch control + in-process fused-location tracking) is used.
 */
interface WorkoutBridge {
    val state: StateFlow<WorkoutUiState>
    fun start(sportType: Int)
    fun pause()
    fun resume()
    fun stop()
}

object WorkoutBridgeHolder {
    @Volatile
    private var installed: WorkoutBridge? = null

    /** Called once at wiring time (GraphFactory / workout package) to plug in the real controller. */
    fun install(bridge: WorkoutBridge) {
        installed = bridge
    }

    fun get(context: Context): WorkoutBridge {
        installed?.let { return it }
        synchronized(this) {
            installed?.let { return it }
            val b = WatchWorkoutBridge(context.applicationContext, App.graph)
            installed = b
            return b
        }
    }
}

/**
 * Fallback session driver: drives `WatchApi.start/update/pause/resume/stopWorkout`, collects live HR from
 * `WatchApi.liveHr`, takes fused-location fixes at 1 Hz with the docs/PLAN.md section 3b filter (accuracy <= 60 m,
 * ignore sub-accuracy movement when nearly stationary, haversine between accepted fixes, rolling-window pace),
 * pushes metrics to the watch face every second and stores `Workout` + `TrackPoint`s through the repository.
 * It runs in-process (no foreground location service), so it only tracks while the app process is alive.
 */
class WatchWorkoutBridge(context: Context, private val graph: Graph) : WorkoutBridge {
    private val app: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(WorkoutUiState())
    override val state: StateFlow<WorkoutUiState> = _state.asStateFlow()

    private val fused: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(app)
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) onFix(l)
        }
    }
    private var gpsOn = false
    private var ticker: Job? = null
    private var hrJob: Job? = null
    private var workoutId = 0L
    private var startTime = 0L
    private var elapsed = 0
    private var weightKg = 75
    private var distance = 0.0
    private var lastAccepted: Location? = null
    private val cum = ArrayList<Pt>()
    private val pending = ArrayList<TrackPoint>()
    private val pendingHr = ArrayList<HrSample>()
    private var hrSum = 0L
    private var hrCount = 0
    private var hrMax = 0
    private var fixes = 0
    private var accepted = 0

    override fun start(sportType: Int) {
        if (_state.value.phase != WorkoutPhase.IDLE) return
        resetCounters()
        _state.value = WorkoutUiState(phase = WorkoutPhase.STARTING, sportType = sportType)
        scope.launch {
            var msg: String? = null
            weightKg = runCatching { graph.settings.profile.first().weightKg }.getOrDefault(75)
            try {
                graph.watch.startWorkout(sportType)
            } catch (e: Exception) {
                msg = "Watch did not start the workout: ${e.message ?: e.javaClass.simpleName}"
            }
            startTime = System.currentTimeMillis()
            workoutId = try {
                graph.repo.insertWorkout(
                    Workout(
                        start = startTime, end = null, sportType = sportType, distanceMeters = 0.0,
                        durationSeconds = 0, avgHr = null, maxHr = null, calories = 0,
                    )
                )
            } catch (e: Exception) {
                msg = "Workout not saved: ${e.message ?: e.javaClass.simpleName}"
                0L
            }
            startGps()
            hrJob = graph.watch.liveHr.onEach { s ->
                if (s.bpm <= 0) return@onEach
                hrSum += s.bpm
                hrCount++
                if (s.bpm > hrMax) hrMax = s.bpm
                pendingHr.add(HrSample(time = s.time, bpm = s.bpm, source = SampleSource.WORKOUT))
                _state.update { it.copy(hr = s.bpm) }
            }.launchIn(scope)
            _state.update { it.copy(phase = WorkoutPhase.RUNNING, workoutId = workoutId, startTime = startTime, message = msg) }
            ticker = scope.launch {
                while (isActive) {
                    delay(1000L)
                    tick()
                }
            }
        }
    }

    private suspend fun tick() {
        if (_state.value.phase != WorkoutPhase.RUNNING) return
        elapsed += 1
        val now = System.currentTimeMillis()
        val pace = ChartData.currentPace(cum, now, distance)
        val cal = calories()
        _state.update { it.copy(elapsedSeconds = elapsed, distanceMeters = distance, paceSecPerKm = pace, calories = cal) }
        try {
            graph.watch.updateWorkout(elapsed, distance, pace, cal)
        } catch (e: Exception) {
            Log.d(TAG, "updateWorkout: ${e.message}")
        }
        if (pending.size >= 20 || pendingHr.size >= 20) flushPoints()
    }

    /** Rough kcal: weight x km x 1.036 (walking/running rule of thumb). */
    private fun calories(): Int = (weightKg * (distance / 1000.0) * 1.036).roundToInt()

    override fun pause() {
        if (_state.value.phase != WorkoutPhase.RUNNING) return
        _state.update { it.copy(phase = WorkoutPhase.PAUSED) }
        scope.launch {
            try {
                graph.watch.pauseWorkout()
            } catch (e: Exception) {
                Log.d(TAG, "pauseWorkout: ${e.message}")
            }
        }
    }

    override fun resume() {
        if (_state.value.phase != WorkoutPhase.PAUSED) return
        lastAccepted = null // movement while paused does not count
        _state.update { it.copy(phase = WorkoutPhase.RUNNING) }
        scope.launch {
            try {
                graph.watch.resumeWorkout()
            } catch (e: Exception) {
                Log.d(TAG, "resumeWorkout: ${e.message}")
            }
        }
    }

    override fun stop() {
        val phase = _state.value.phase
        if (phase == WorkoutPhase.IDLE || phase == WorkoutPhase.STOPPING) return
        _state.update { it.copy(phase = WorkoutPhase.STOPPING) }
        ticker?.cancel()
        ticker = null
        hrJob?.cancel()
        hrJob = null
        stopGps()
        scope.launch {
            try {
                graph.watch.stopWorkout()
            } catch (e: Exception) {
                Log.d(TAG, "stopWorkout: ${e.message}")
            }
            flushPoints()
            if (workoutId != 0L) {
                val w = Workout(
                    id = workoutId,
                    start = startTime,
                    end = System.currentTimeMillis(),
                    sportType = _state.value.sportType,
                    distanceMeters = distance,
                    durationSeconds = elapsed,
                    avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else null,
                    maxHr = if (hrMax > 0) hrMax else null,
                    calories = calories(),
                )
                try {
                    graph.repo.updateWorkout(w)
                } catch (e: Exception) {
                    Log.w(TAG, "updateWorkout failed", e)
                }
            }
            _state.value = WorkoutUiState()
        }
    }

    private fun onFix(loc: Location) {
        val st = _state.value
        if (!st.active) return
        fixes++
        val acc = loc.accuracy
        val now = if (loc.time > 0) loc.time else System.currentTimeMillis()
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        var ok = st.phase == WorkoutPhase.RUNNING && loc.hasAccuracy() && acc <= MAX_ACCURACY_M
        if (ok) {
            val prev = lastAccepted
            if (prev != null) {
                val d = ChartData.haversineM(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
                if (d < acc && speed < STATIONARY_MPS) ok = false else distance += d
            }
        }
        if (ok) {
            lastAccepted = loc
            accepted++
            cum.add(Pt(now, distance))
        }
        if (workoutId != 0L) {
            pending.add(
                TrackPoint(
                    workoutId = workoutId, time = now, lat = loc.latitude, lon = loc.longitude,
                    accuracyM = acc, speedMps = speed,
                    altitudeM = if (loc.hasAltitude()) loc.altitude else null, accepted = ok,
                    cumulativeM = distance,
                )
            )
        }
        _state.update {
            it.copy(
                gpsAccuracyM = if (loc.hasAccuracy()) acc else null,
                gpsFixes = fixes, gpsAccepted = accepted,
                speedMps = speed.toDouble(), distanceMeters = distance,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsOn) return
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            gpsOn = true
        } catch (e: SecurityException) {
            _state.update { it.copy(message = "Location permission missing: no GPS distance") }
        } catch (e: Exception) {
            _state.update { it.copy(message = "GPS unavailable: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun stopGps() {
        if (!gpsOn) return
        gpsOn = false
        try {
            fused.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.d(TAG, "removeLocationUpdates: ${e.message}")
        }
    }

    /** Persists the buffered track points and workout HR samples (the detail chart reads them back). */
    private suspend fun flushPoints() {
        if (pending.isNotEmpty()) {
            val batch = ArrayList(pending)
            pending.clear()
            try {
                graph.repo.insertTrackPoints(batch)
            } catch (e: Exception) {
                Log.w(TAG, "insertTrackPoints failed", e)
            }
        }
        if (pendingHr.isNotEmpty()) {
            val batch = ArrayList(pendingHr)
            pendingHr.clear()
            try {
                graph.repo.upsertHr(batch)
            } catch (e: Exception) {
                Log.w(TAG, "upsertHr failed", e)
            }
        }
    }

    private fun resetCounters() {
        workoutId = 0L
        startTime = 0L
        elapsed = 0
        distance = 0.0
        lastAccepted = null
        cum.clear()
        pending.clear()
        pendingHr.clear()
        hrSum = 0L
        hrCount = 0
        hrMax = 0
        fixes = 0
        accepted = 0
    }

    companion object {
        private const val TAG = "WorkoutBridge"
        const val MAX_ACCURACY_M = WorkoutUiState.GPS_USABLE_M
        const val STATIONARY_MPS = 0.5f
    }
}
