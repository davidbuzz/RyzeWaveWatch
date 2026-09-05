package au.buzz.ryzewave.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import au.buzz.ryzewave.App
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.findphone.FindPhoneRinger

/**
 * Debug builds only (registered in `src/debug/AndroidManifest.xml`): injects watch events from adb so phone-side
 * behaviour can be exercised without the watch. The event goes into the same [FindPhoneRinger.onEvent] the
 * `WatchApi.events` collector feeds. The receiver is exported (adb needs that) but guarded with
 * `android:permission="android.permission.DUMP"` in `src/debug/AndroidManifest.xml`: the adb shell holds DUMP,
 * ordinary apps cannot obtain it, so only adb (or the system) can inject events.
 *
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.FIND_PHONE --ez start true
 *     adb shell am broadcast -n au.buzz.ryzewave/.debug.DebugEventReceiver -a au.buzz.ryzewave.debug.FIND_PHONE --ez start false
 */
class DebugEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIND_PHONE -> {
                val start = intent.getBooleanExtra(EXTRA_START, true)
                Log.i(TAG, "injecting WatchEvent.FindPhone(start=$start) (debug hook)")
                App.graph.findPhone.onEvent(WatchEvent.FindPhone(start))
            }
            else -> Log.i(TAG, "ignored ${intent.action}")
        }
    }

    companion object {
        const val TAG = "DebugEvent"
        const val ACTION_FIND_PHONE = "au.buzz.ryzewave.debug.FIND_PHONE"
        const val EXTRA_START = "start"
    }
}
