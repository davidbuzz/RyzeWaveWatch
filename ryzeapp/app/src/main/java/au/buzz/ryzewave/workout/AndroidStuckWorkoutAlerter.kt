package au.buzz.ryzewave.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.util.Log
import au.buzz.ryzewave.R
import au.buzz.ryzewave.ble.WatchService

/**
 * Android side of [StuckWorkoutMonitor.Alerter]: a high-priority heads-up notification when a running workout
 * shows no activity, with a Stop action (and a body tap) that goes to [WatchService], which calls
 * [StuckWorkoutMonitor.stopNow]. Removed on [clear] (the user's Stop, activity resuming, or the auto-stop).
 */
class AndroidStuckWorkoutAlerter(private val context: Context) : StuckWorkoutMonitor.Alerter {

    override fun warn(urgency: Urgency, title: String, text: String) {
        val nm = notificationManager() ?: return
        try {
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)      // the night-guard channel this replaces
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stuck-workout detector", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Warns when a running workout shows no activity (e.g. the watch was bumped in your sleep)"
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val stop = WatchService.stuckWorkoutStopIntent(context)
            val body = if (urgency == Urgency.HIGH) "$text before it spoils your sleep tracking" else text
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_watch)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(stop)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_watch), "Stop", stop).build())
                .build()
            nm.notify(NOTIFICATION_ID, n)
            Log.i(TAG, "no-activity notification posted ($urgency): \"$title\" / \"$body\"")
        } catch (e: Exception) {
            Log.w(TAG, "notification failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun clear() {
        try {
            notificationManager()?.cancel(NOTIFICATION_ID)
            Log.i(TAG, "no-activity notification cleared")
        } catch (e: Exception) {
            Log.w(TAG, "cancel notification: ${e.message}")
        }
    }

    private fun notificationManager(): NotificationManager? = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val TAG = "StuckWorkout"
        const val CHANNEL_ID = "stuck_workout"
        const val LEGACY_CHANNEL_ID = "night_workout_guard"
        const val NOTIFICATION_ID = 1003
    }
}
