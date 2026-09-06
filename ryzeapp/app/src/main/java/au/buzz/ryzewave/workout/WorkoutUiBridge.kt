package au.buzz.ryzewave.workout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import au.buzz.ryzewave.ui.WorkoutBridge
import au.buzz.ryzewave.ui.WorkoutUiState
import au.buzz.ryzewave.ui.WorkoutPhase as UiPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapts [WorkoutController] + [WorkoutService] to the UI's `WorkoutBridge`, so the Workout screen drives the
 * real foreground tracker instead of its in-process fallback. Wire once at start-up:
 * `WorkoutBridgeHolder.install(WorkoutUiBridge(app))`. Commands go through the service; STARTING / STOPPING
 * are shown while the service and the watch are doing the work (bounded by [TRANSITION_TIMEOUT_MS]).
 */
class WorkoutUiBridge(
    context: Context,
    private val controller: WorkoutController = WorkoutSession.controller(),
    private val scope: CoroutineScope = WorkoutSession.scope,
) : WorkoutBridge {
    private val app: Context = context.applicationContext
    private val transition = MutableStateFlow<UiPhase?>(null)
    private var transitionJob: Job? = null

    @Volatile
    private var requestedSportType: Int = WorkoutService.DEFAULT_SPORT_TYPE

    override val state: StateFlow<WorkoutUiState> =
        combine(controller.state, transition) { s, t -> toUi(s, t, requestedSportType) }
            .stateIn(scope, SharingStarted.Eagerly, toUi(controller.state.value, null, requestedSportType))

    override fun start(sportType: Int) {
        if (controller.state.value.isActive) return
        requestedSportType = sportType
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Without precise location the foreground service cannot even start on Android 14+; say so at once.
            controller.reportError("Precise location permission required for GPS distance")
            return
        }
        beginTransition(UiPhase.STARTING) { it.isActive }
        WorkoutService.start(app, sportType)
    }

    override fun pause() {
        if (controller.state.value.isRunning) WorkoutService.pause(app)
    }

    override fun resume() {
        if (controller.state.value.state == WorkoutPhase.PAUSED) WorkoutService.resume(app)
    }

    override fun stop() {
        if (!controller.state.value.isActive) return
        beginTransition(UiPhase.STOPPING) { !it.isActive }
        WorkoutService.stop(app)
    }

    /**
     * Shows [phase] until the controller satisfies [done], a new error is reported while the controller is still
     * inactive (the service could not start: no permission, foreground start refused), or [TRANSITION_TIMEOUT_MS].
     */
    private fun beginTransition(phase: UiPhase, done: (WorkoutState) -> Boolean) {
        transitionJob?.cancel()
        transition.value = phase
        val initialSeq = controller.state.value.errorSeq
        transitionJob = scope.launch {
            withTimeoutOrNull(TRANSITION_TIMEOUT_MS) {
                controller.state.first { done(it) || (!it.isActive && it.errorSeq != initialSeq) }
            }
            transition.compareAndSet(phase, null)
        }
    }

    companion object {
        const val TRANSITION_TIMEOUT_MS = 20_000L

        /** Pure mapping from the controller's state (+ an optional pending transition) to the UI model. */
        fun toUi(s: WorkoutState, transition: UiPhase?, requestedSportType: Int): WorkoutUiState {
            val phase = when {
                transition == UiPhase.STARTING && !s.isActive -> UiPhase.STARTING
                transition == UiPhase.STOPPING && s.isActive -> UiPhase.STOPPING
                s.state == WorkoutPhase.RUNNING -> UiPhase.RUNNING
                s.state == WorkoutPhase.PAUSED -> UiPhase.PAUSED
                else -> UiPhase.IDLE
            }
            return when (phase) {
                UiPhase.IDLE -> WorkoutUiState(message = s.message)
                UiPhase.STARTING -> WorkoutUiState(phase = phase, sportType = requestedSportType, message = s.message)
                else -> WorkoutUiState(
                    phase = phase,
                    sportType = s.sportType,
                    workoutId = s.workoutId ?: 0L,
                    startTime = s.startTime ?: 0L,
                    elapsedSeconds = s.elapsedSeconds,
                    distanceMeters = s.distanceMeters,
                    paceSecPerKm = s.paceSecPerKm,
                    speedMps = s.speedMps,
                    hr = s.lastHr,
                    calories = s.calories,
                    gpsAccuracyM = s.gpsAccuracyM,
                    gpsFixes = s.trackPointCount,
                    gpsAccepted = s.acceptedPointCount,
                    gpsAvailable = s.gpsAvailable,
                    gpsStale = s.gpsStale,
                    steps = s.steps,
                    walkStrideMeters = s.walkStrideMeters,
                    runStrideMeters = s.runStrideMeters,
                    message = s.message,
                )
            }
        }
    }
}
