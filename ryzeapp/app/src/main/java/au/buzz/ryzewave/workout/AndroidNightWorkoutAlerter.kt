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
 * Android side of [NightWorkoutGuardController.NightWorkoutAlerter]: a high-priority heads-up notification when
 * a workout the watch started overnight looks accidental, with a Stop action (and a body tap) that goes to
 * [WatchService], which calls [au.buzz.ryzewave.workout.NightWorkoutGuardController.stopNow]. The notification is
 * removed on [clear] (the user's Stop, or the auto-stop after the grace period).
 */
class AndroidNightWorkoutAlerter(private val context: Context) : NightWorkoutGuardController.NightWorkoutAlerter {

    override fun warn() {
        val nm = notificationManager() ?: return
        try {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Accidental workout guard", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Warns when the watch starts a workout while you may be asleep"
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val stop = WatchService.nightWorkoutStopIntent(context)
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_watch)
                .setContentTitle("Workout started while you may be asleep")
                .setContentText("Tap to stop it before it spoils your sleep tracking")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(stop)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_watch), "Stop", stop).build())
                .build()
            nm.notify(NOTIFICATION_ID, n)
            Log.i(TAG, "accidental-night-workout notification posted")
        } catch (e: Exception) {
            Log.w(TAG, "notification failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun clear() {
        try {
            notificationManager()?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancel notification: ${e.message}")
        }
    }

    private fun notificationManager(): NotificationManager? = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val TAG = "NightWorkoutGuard"
        const val CHANNEL_ID = "night_workout_guard"
        const val NOTIFICATION_ID = 1003
    }
}
