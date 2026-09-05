package au.buzz.ryzewave.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import au.buzz.ryzewave.App
import au.buzz.ryzewave.ble.WatchApiImpl
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import au.buzz.ryzewave.workout.WorkoutService

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
 * Workout state machine — [op] is start|pause|resume|stop|steps. pause/resume/stop are injected as
 * *watch-originated* [WatchEvent.WorkoutControl] events (exactly the path a real button press takes), so the
 * controller pauses/resumes/stops, TTS speaks and GPS keeps running without a real watch. `steps` injects a
 * [WatchEvent.WorkoutRealtime] with `--ei n <count>`. `start` launches the foreground workout the normal way
 * (this DOES send `FD 11` to the watch if one is connected — run it with the watch disconnected):
 *
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op start
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op steps --ei n 1234
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op pause
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op resume
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.WORKOUT --es op stop
 */
class DebugEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIND_PHONE -> {
                val start = intent.getBooleanExtra(EXTRA_START, true)
                Log.i(TAG, "injecting WatchEvent.FindPhone(start=$start) (debug hook)")
                App.graph.findPhone.onEvent(WatchEvent.FindPhone(start))
            }
            ACTION_WORKOUT -> onWorkout(context, intent)
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
            else -> Log.i(TAG, "WORKOUT: unknown op '$op'")
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
        const val EXTRA_START = "start"
        const val EXTRA_OP = "op"
        const val EXTRA_N = "n"
    }
}
