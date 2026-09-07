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
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.ui.WorkoutBridgeHolder
import au.buzz.ryzewave.workout.AndroidMotionSampler
import au.buzz.ryzewave.workout.AndroidSpeaker
import au.buzz.ryzewave.workout.AndroidStuckWorkoutAlerter
import au.buzz.ryzewave.workout.DefaultStrideModel
import au.buzz.ryzewave.workout.RestingHrBaseline
import au.buzz.ryzewave.workout.StuckWorkoutMonitor
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

        // No workout can be active at process start, so any endTime-NULL row is an orphan (a crash, or the
        // former stop-cancellation race) — close it so it stops showing "in progress" and counts in the day.
        val processStart = System.currentTimeMillis()
        scope.launch {
            try {
                val closed = repo.closeOrphanedWorkouts(before = processStart)
                if (closed > 0) {
                    Log.i(TAG, "closed $closed orphaned workout row(s)")
                    exportNew("orphan repair")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "orphaned-workout repair failed", t)
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

        // Opt-in GPS breadcrumb: the switch in Settings starts/stops the location foreground service.
        scope.launch {
            settings.breadcrumbEnabled.distinctUntilChanged().collect { on ->
                Log.i(TAG, "breadcrumb ${if (on) "on: starting" else "off: stopping"} the service")
                if (on) au.buzz.ryzewave.workout.BreadcrumbService.start(app) else au.buzz.ryzewave.workout.BreadcrumbService.stop(app)
            }
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

        // Stuck-in-exercise-mode detector (docs/PLAN.md): every workout — the app's own and one the watch started by
        // itself — is judged against its sport's expected activity signature over a rolling window. No expected
        // signal for the whole window → spoken warning + high-priority notification with Stop, then an auto-stop
        // after a grace (short at night with the wearer's HR at sleeping level: the former night workout guard).
        // App-started workouts are only auto-stopped when the Settings switch says so. Lives in the process the
        // BLE foreground service keeps alive, so it works with the screen off; speech goes through the shared engine.
        val speaker = AndroidSpeaker(app)
        val stuckMonitor = StuckWorkoutMonitor(
            watch = watch,
            controller = controller,
            scope = scope,
            alerter = AndroidStuckWorkoutAlerter(app),
            speaker = speaker::speak,
            motion = AndroidMotionSampler(app),
            restingBaseline = {
                val now = System.currentTimeMillis()
                RestingHrBaseline.of(repo.hrBetween(now - RestingHrBaseline.LOOKBACK_MS, now).first())
            },
            periodicHr = { from, to ->
                repo.hrBetween(from, to).first().filter { it.source == SampleSource.AUTO || it.source == SampleSource.HISTORY }
            },
            detectorEnabled = { settings.stuckDetectorEnabled.first() },
            autoStopAppWorkouts = { settings.stuckAutoStopAppWorkouts.first() },
            log = { Log.i(AndroidStuckWorkoutAlerter.TAG, it) },
        )

        return Graph(
            repo = repo, settings = settings, watch = watch, health = health, notifications = notifications,
            findPhone = findPhone, speaker = speaker, stuckMonitor = stuckMonitor,
        )
    }
}
