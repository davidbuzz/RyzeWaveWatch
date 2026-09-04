package au.buzz.ryzewave.ui

import au.buzz.ryzewave.workout.WorkoutController
import au.buzz.ryzewave.workout.WorkoutState
import au.buzz.ryzewave.workout.WorkoutPhase as ControllerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Adapts the workout package's [WorkoutController] to the UI's [WorkoutBridge]. The integration step creates one
 * with the process-wide controller (hosted by `workout.WorkoutService`) and installs it with
 * [WorkoutBridgeHolder.install]; [beforeStart] / [afterStop] are hooks for starting and stopping the foreground
 * location service around the session. Until it is installed the UI falls back to [WatchWorkoutBridge].
 */
class ControllerWorkoutBridge(
    private val controller: WorkoutController,
    private val scope: CoroutineScope,
    private val beforeStart: suspend (sportType: Int) -> Unit = {},
    private val afterStop: suspend () -> Unit = {},
) : WorkoutBridge {
    override val state: StateFlow<WorkoutUiState> =
        controller.state.map { it.toUi() }.stateIn(scope, SharingStarted.Eagerly, controller.state.value.toUi())

    override fun start(sportType: Int) {
        scope.launch {
            beforeStart(sportType)
            controller.start(sportType)
        }
    }

    override fun pause() {
        scope.launch { controller.pause() }
    }

    override fun resume() {
        scope.launch { controller.resume() }
    }

    override fun stop() {
        scope.launch {
            controller.stop()
            afterStop()
        }
    }
}

/** `workout.WorkoutState` → the UI's view of it (a STOPPED controller shows as IDLE with the final numbers). */
fun WorkoutState.toUi(): WorkoutUiState = WorkoutUiState(
    phase = when (state) {
        ControllerPhase.RUNNING -> WorkoutPhase.RUNNING
        ControllerPhase.PAUSED -> WorkoutPhase.PAUSED
        ControllerPhase.STOPPED -> WorkoutPhase.IDLE
    },
    sportType = sportType,
    workoutId = workoutId ?: 0L,
    startTime = startTime ?: 0L,
    elapsedSeconds = elapsedSeconds,
    distanceMeters = distanceMeters,
    paceSecPerKm = paceSecPerKm,
    speedMps = speedMps,
    hr = lastHr,
    calories = calories,
    gpsAccuracyM = gpsAccuracyM,
    gpsFixes = trackPointCount,
    gpsAccepted = acceptedPointCount,
    message = error,
)
