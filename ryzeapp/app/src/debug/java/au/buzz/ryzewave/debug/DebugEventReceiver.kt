package au.buzz.ryzewave.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import au.buzz.ryzewave.App
import au.buzz.ryzewave.ble.WatchApiImpl
import au.buzz.ryzewave.ble.WatchService
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import au.buzz.ryzewave.health.HealthConnectMapping
import au.buzz.ryzewave.health.SleepReconstruction
import au.buzz.ryzewave.workout.StuckWorkoutMonitor
import au.buzz.ryzewave.workout.WorkoutService
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug builds only (registered in `src/debug/AndroidManifest.xml`): injects watch events from adb so phone-side
 * behaviour can be exercised without the watch. The receiver is exported (adb needs that) but guarded with
 * `android:permission="android.permission.DUMP"`: the adb shell holds DUMP, ordinary apps cannot obtain it, so
 * only adb (or the system) can inject events.
 *
 * Find-my-phone:
 *
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.FIND_PHONE --ez start true
 *
 * Workout state machine — [op] is start|pause|resume|stop|steps|watchstart. pause/resume/stop are injected as
 * *watch-originated* [WatchEvent.WorkoutControl] events (exactly the path a real button press takes), so the
 * controller pauses/resumes/stops, TTS speaks and GPS keeps running without a real watch. `steps` injects a
 * [WatchEvent.WorkoutRealtime] with `--ei n <count>`. `start` launches the foreground workout the normal way
 * (this DOES send `FD 11` to the watch if one is connected — run it with the watch disconnected). `watchstart`
 * simulates the watch starting a workout on its own and drives the accidental-night-workout guard (feature B):
 * it emits a real `WorkoutControl(START)` event AND forces the guard with explicit inputs so the notification +
 * auto-stop can be seen on demand (extras `--el at <ms>`, `--ei hr <bpm>`, `--ez moving <bool>`, `--el grace <ms>`):
 *
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op start
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op steps --ei n 1234
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op pause
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op resume
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op stop
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op watchstart --ei hr 58 --el grace 8000
 *
 * Honest reconstructed / manual sleep (feature A): write a generic-asleep session over [start, end) while
 * keeping whatever real stages the watch already recorded in that window (unless `--ez preserve false`), then
 * re-export the night to Health Connect. Times are epoch millis; `preserve` defaults true:
 *
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.SLEEP \
 *         --el start 1788610500000 --el end 1788642000000 --ez preserve true
 */
class DebugEventReceiver : BroadcastReceiver() {

    /** Own scope for the async DB / export work of the SLEEP hook (onReceive returns immediately). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIND_PHONE -> {
                val start = intent.getBooleanExtra(EXTRA_START, true)
                Log.i(TAG, "injecting WatchEvent.FindPhone(start=$start) (debug hook)")
                App.graph.findPhone.onEvent(WatchEvent.FindPhone(start))
            }
            ACTION_WORKOUT -> onWorkout(context, intent)
            ACTION_SLEEP -> onSleep(intent)
            ACTION_LINK -> {
                // `--ez auto false` keeps this phone off the watch (persisted; e.g. the test phone while the Pixel owns
                // the watch's single BLE link); `--ez auto true` restores normal auto-connect.
                val pending = goAsync()
                scope.launch {
                    try {
                        if (intent.hasExtra("auto")) {
                            val auto = intent.getBooleanExtra("auto", true)
                            App.graph.settings.setAutoConnect(auto)
                            if (auto) WatchService.connect(context) else WatchService.pause(context)
                            Log.i(TAG, "LINK: autoConnect=$auto")
                        }
                        if (intent.hasExtra("breadcrumb")) {
                            val on = intent.getBooleanExtra("breadcrumb", false)
                            App.graph.settings.setBreadcrumbEnabled(on)
                            Log.i(TAG, "LINK: breadcrumb=$on")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> Log.i(TAG, "ignored ${intent.action}")
        }
    }

    private fun onWorkout(context: Context, intent: Intent) {
        val op = intent.getStringExtra(EXTRA_OP)?.lowercase()
        val watch = App.graph.watch as? WatchApiImpl
        when (op) {
            "start" -> {
                Log.i(TAG, "debug hook: starting a workout via WorkoutService (may send FD 11 if connected)")
                WorkoutService.start(context.applicationContext)
            }
            "pause" -> inject(watch, WatchEvent.WorkoutControl(WorkoutControlAction.PAUSE))
            "resume" -> inject(watch, WatchEvent.WorkoutControl(WorkoutControlAction.RESUME))
            "stop" -> inject(watch, WatchEvent.WorkoutControl(WorkoutControlAction.STOP))
            "steps" -> {
                val n = intent.getIntExtra(EXTRA_N, 0)
                inject(watch, WatchEvent.WorkoutRealtime(sportType = 1, steps = n, calories = 0, distanceMeters = 0.0))
            }
            "watchstart" -> onWatchStart(watch, intent)
            else -> Log.i(TAG, "WORKOUT: unknown op '$op'")
        }
    }

    /**
     * Simulate the watch starting a workout by itself and drive the stuck-workout detector deterministically:
     * optional extras force the detector's inputs for this session — `--el window <ms>` (judging window),
     * `--el grace <ms>` (auto-stop grace), `--ez night true|false` (force the night classification instead of the
     * clock), `--ei hr <bpm>` (feed this HR every evaluation; default 58 = sleeping), `--ez moving true` (feed GPS
     * movement). Then the real `WorkoutControl(START)` event is emitted; realtime pushes with a flat or rising step
     * count can follow through the `rt` op to exercise the steps indicator.
     */
    private fun onWatchStart(watch: WatchApiImpl?, intent: Intent) {
        val override = StuckWorkoutMonitor.DebugOverride(
            windowMs = if (intent.hasExtra(EXTRA_WINDOW)) intent.getLongExtra(EXTRA_WINDOW, 0L) else null,
            graceMs = if (intent.hasExtra(EXTRA_GRACE)) intent.getLongExtra(EXTRA_GRACE, 0L) else null,
            night = if (intent.hasExtra(EXTRA_NIGHT)) intent.getBooleanExtra(EXTRA_NIGHT, true) else null,
            hrBpm = if (intent.hasExtra(EXTRA_HR)) intent.getIntExtra(EXTRA_HR, 0) else 58,
            moving = intent.getBooleanExtra(EXTRA_MOVING, false),
        )
        Log.i(TAG, "debug watchstart: $override")
        App.graph.stuckMonitor.debugOverride(override)
        inject(watch, WatchEvent.WorkoutControl(WorkoutControlAction.START))
    }

    /**
     * Honest reconstructed / manual sleep. Reads the existing stages of the night that [EXTRA_END] falls into,
     * fills [start, end) with generic-asleep around whatever real stages the watch kept, writes it through the
     * repository (replacing the night) and re-exports the night to Health Connect. Logs the totals at INFO.
     */
    private fun onSleep(intent: Intent) {
        val start = intent.getLongExtra(EXTRA_START_MS, -1L)
        val end = intent.getLongExtra(EXTRA_END_MS, -1L)
        val preserve = intent.getBooleanExtra(EXTRA_PRESERVE, true)
        if (start < 0L || end <= start) {
            Log.w(TAG, "SLEEP: need --el start <ms> and --el end <ms> with end > start (got start=$start end=$end)")
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                val repo = App.graph.repo
                val zone = ZoneId.systemDefault()
                val dayStart = HealthConnectMapping.startOfDay(HealthConnectMapping.sleepMorning(start, zone), zone)
                val existing = repo.sleepForNight(dayStart).first()
                val result = SleepReconstruction.fillWindow(start, end, existing, preserve)
                // Keep any real stages that fall in the night but outside [start, end) too (nothing is lost).
                val outside = if (preserve) {
                    existing.filter { it.minutes > 0 && (it.start + it.minutes * 60_000L <= start || it.start >= end) }
                } else {
                    emptyList()
                }
                val toWrite = (result.stages + outside).sortedBy { it.start }
                repo.replaceSleepForNight(dayStart, toWrite)
                Log.i(
                    TAG,
                    "SLEEP reconstructed night $dayStart: window ${(end - start) / 60_000L} min, " +
                        "total asleep ${result.totalAsleepMin} min (${result.totalAsleepMin / 60}h${result.totalAsleepMin % 60}m), " +
                        "${result.genericBlocks} generic-asleep blocks + ${result.stagedBlocks} kept staged " +
                        "(+${outside.size} outside window), preserve=$preserve",
                )
                val export = App.graph.health.exportNew()
                Log.i(TAG, "SLEEP: Health Connect re-export -> $export")
            } catch (e: Exception) {
                Log.w(TAG, "SLEEP hook failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun inject(watch: WatchApiImpl?, event: WatchEvent) {
        if (watch == null) {
            Log.w(TAG, "cannot inject $event: watch is not a WatchApiImpl")
            return
        }
        Log.i(TAG, "injecting $event (debug hook)")
        watch.injectEvent(event)
    }

    companion object {
        const val TAG = "DebugEvent"
        const val ACTION_FIND_PHONE = "au.buzz.ryzewave.debug.FIND_PHONE"
        const val ACTION_WORKOUT = "au.buzz.ryzewave.debug.WORKOUT"
        const val ACTION_LINK = "au.buzz.ryzewave.debug.LINK"
        const val ACTION_SLEEP = "au.buzz.ryzewave.debug.SLEEP"
        const val EXTRA_START = "start"
        const val EXTRA_OP = "op"
        const val EXTRA_N = "n"
        // SLEEP extras
        const val EXTRA_START_MS = "start"
        const val EXTRA_END_MS = "end"
        const val EXTRA_PRESERVE = "preserve"
        // watchstart extras
        const val EXTRA_AT = "at"
        const val EXTRA_HR = "hr"
        const val EXTRA_MOVING = "moving"
        const val EXTRA_WINDOW = "window"
        const val EXTRA_NIGHT = "night"
        const val EXTRA_GRACE = "grace"
    }
}
