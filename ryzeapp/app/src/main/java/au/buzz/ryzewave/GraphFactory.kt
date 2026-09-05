package au.buzz.ryzewave

import android.util.Log
import au.buzz.ryzewave.ble.createWatchApi
import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.data.DataStoreSettingsStore
import au.buzz.ryzewave.data.Db
import au.buzz.ryzewave.data.RoomHealthRepository
import au.buzz.ryzewave.findphone.AndroidFindPhoneAlerter
import au.buzz.ryzewave.findphone.FindPhoneRinger
import au.buzz.ryzewave.health.HealthConnectExporter
import au.buzz.ryzewave.notify.NotificationForwarder
import au.buzz.ryzewave.ui.WorkoutBridgeHolder
import au.buzz.ryzewave.workout.DefaultStrideModel
import au.buzz.ryzewave.workout.WorkoutController
import au.buzz.ryzewave.workout.WorkoutSession
import au.buzz.ryzewave.workout.WorkoutUiBridge
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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

        // Health Connect: the daily DistanceRecords are steps × stride, so a change of the *effective* stride
        // (calibration, manual edit, reset — or a profile height change while the strides are derived from it)
        // re-exports the affected days right away; the planner's stride marker decides which days moved.
        scope.launch {
            combine(settings.profile, settings.stride) { profile, s -> stride.walkStrideM(profile, s) to stride.runStrideM(profile, s) }
                .distinctUntilChanged()
                .drop(1)                                    // the stored value at start-up is not a change
                .collect { (walk, run) -> exportNew("stride change (walk $walk m, run $run m)") }
        }

        // Phone -> watch notifications: the listener service hands posted notifications to this forwarder.
        val notifications = NotificationForwarder(
            settings, watch, scope, ownPackage = app.packageName,
            log = { m, t -> if (t == null) Log.i(TAG, "notify: $m") else Log.w(TAG, "notify: $m", t) },
        )

        // Find my phone: the watch's `D1 0A 01` rings the phone (alarm ringtone + vibration + Stop notification)
        // until `D1 0A 00`, the Stop action (through WatchService) or 30 s. Lives in the process the foreground
        // service keeps alive, so it rings with the screen off.
        val findPhone = FindPhoneRinger(
            AndroidFindPhoneAlerter(app), scope,
            log = { m, t -> if (t == null) Log.i(AndroidFindPhoneAlerter.TAG, m) else Log.w(AndroidFindPhoneAlerter.TAG, m, t) },
        )
        scope.launch { watch.events.collect { findPhone.onEvent(it) } }

        return Graph(
            repo = repo, settings = settings, watch = watch, health = health, notifications = notifications,
            findPhone = findPhone,
        )
    }
}
