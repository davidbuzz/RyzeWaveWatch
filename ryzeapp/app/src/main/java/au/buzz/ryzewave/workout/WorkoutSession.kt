package au.buzz.ryzewave.workout

import android.content.Context
import android.util.Log
import au.buzz.ryzewave.App
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide home of the one [WorkoutController]. The controller has to outlive any screen (it is what the
 * foreground [WorkoutService] feeds and what the Workout screen observes), so it is created lazily from
 * `App.graph` and kept here. The integration step may [install] a differently wired instance before anything
 * else touches it (e.g. from `GraphFactory`).
 *
 * The command helpers route through [WorkoutService] so GPS tracking runs as a foreground location service.
 */
object WorkoutSession {
    private const val TAG = "WorkoutSession"

    /** Scope for the controller's ticker and HR collection; lives as long as the process. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("workout"))

    @Volatile
    private var installed: WorkoutController? = null

    /** Replace the controller (wiring/tests). Has no effect on a workout that is already running. */
    fun install(controller: WorkoutController) {
        installed = controller
    }

    fun controller(): WorkoutController {
        installed?.let { return it }
        synchronized(this) {
            installed?.let { return it }
            val graph = App.graph
            val created = WorkoutController(
                repo = graph.repo,
                watch = graph.watch,
                settings = graph.settings,
                scope = scope,
                onError = { message, cause -> Log.w(TAG, message, cause) },
            )
            installed = created
            return created
        }
    }

    /** Live workout state; safe to read from any thread. */
    val state: StateFlow<WorkoutState>
        get() = controller().state

    fun start(context: Context, sportType: Int = WorkoutService.DEFAULT_SPORT_TYPE) =
        WorkoutService.start(context, sportType)

    fun pause(context: Context) = WorkoutService.pause(context)
    fun resume(context: Context) = WorkoutService.resume(context)
    fun stop(context: Context) = WorkoutService.stop(context)
}
