package au.buzz.ryzewave

import android.util.Log
import au.buzz.ryzewave.ble.createWatchApi
import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.data.DataStoreSettingsStore
import au.buzz.ryzewave.data.Db
import au.buzz.ryzewave.data.RoomHealthRepository
import au.buzz.ryzewave.health.HealthConnectExporter
import au.buzz.ryzewave.ui.WorkoutBridgeHolder
import au.buzz.ryzewave.workout.DefaultStrideModel
import au.buzz.ryzewave.workout.WorkoutController
import au.buzz.ryzewave.workout.WorkoutSession
import au.buzz.ryzewave.workout.WorkoutUiBridge
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Builds the process-wide [Graph]: Room repository + DataStore settings (data), stride model (workout),
 * the BLE [au.buzz.ryzewave.core.WatchApi] (ble) and the Health Connect exporter (health). Also installs the
 * foreground-service workout controller behind the UI's `WorkoutBridge` and exports new data to Health
 * Connect after every successful watch sync when the user has enabled it.
 *
 * Must not touch `App.graph` (it is being assigned from the return value of [create]).
 */
object GraphFactory {
    private const val TAG = "GraphFactory"

    /** Process-lifetime scope for the BLE link, the reconnect loop and the post-sync export hook. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("graph"))

    fun create(app: App): Graph {
        val settings = DataStoreSettingsStore(app)
        val stride = DefaultStrideModel()
        val repo = RoomHealthRepository(Db.get(app), stride, settings)
        val watch = createWatchApi(app, repo, settings, scope)
        val health = HealthConnectExporter(app, repo, settings, stride)

        /** Health Connect: export what is new (steps / HR / SpO2 / sleep / workouts) when the user has enabled it. */
        suspend fun exportNew(reason: String) {
            try {
                if (!settings.healthConnectEnabled.first()) return
                val result = health.exportNew()
                Log.i(TAG, "Health Connect export after $reason: $result")
            } catch (t: Throwable) {
                Log.w(TAG, "Health Connect export after $reason failed", t)
            }
        }

        // Workout: one controller for the foreground service and the Workout screen. A finished workout goes to
        // Health Connect straight away (session + distance + its HR samples), not only after the next watch sync.
        val controller = WorkoutController(
            repo = repo,
            watch = watch,
            settings = settings,
            scope = WorkoutSession.scope,
            onError = { message, cause -> Log.w(TAG, message, cause) },
            onFinished = { workout -> scope.launch { exportNew("workout ${workout.id}") } },
        )
        WorkoutSession.install(controller)
        WorkoutBridgeHolder.install(WorkoutUiBridge(app, controller, WorkoutSession.scope))

        // Health Connect: push whatever is new after each completed sync (lastSyncTime changes when syncAll finishes).
        scope.launch {
            watch.status
                .map { it.lastSyncTime }
                .filterNotNull()
                .distinctUntilChanged()
                .collect {
                    if (watch.status.value.state == ConnectionState.SYNCING) return@collect
                    exportNew("sync")
                }
        }

        return Graph(repo = repo, settings = settings, watch = watch, health = health)
    }
}
