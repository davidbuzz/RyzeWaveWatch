package au.buzz.ryzewave.findphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import au.buzz.ryzewave.R
import au.buzz.ryzewave.ble.WatchService

/**
 * The Android side of find-my-phone: the default alarm ringtone (falling back to the ringtone, then the
 * notification sound) looped on the alarm stream at full volume — the point is to be heard, so the ringer mode
 * and the current alarm volume are deliberately ignored (the volume is restored on stop) — a repeating
 * vibration with alarm attributes (so it also runs under Do Not Disturb where alarms are allowed), and a
 * high-priority notification whose Stop action (and body tap / swipe) goes to [WatchService], which calls
 * [FindPhoneRinger.stop]. Each part fails on its own: a missing ringtone does not stop the vibration.
 *
 * Runs in the process kept alive by the [WatchService] foreground service, so it works with the screen off.
 */
class AndroidFindPhoneAlerter(private val context: Context) : FindPhoneAlerter {

    private var player: MediaPlayer? = null
    private var savedAlarmVolume = -1

    override fun startAlarm() {
        showNotification()
        raiseAlarmVolume()
        startRingtone()
        startVibration()
    }

    override fun stopAlarm() {
        stopRingtone()
        stopVibration()
        restoreAlarmVolume()
        notificationManager()?.cancel(NOTIFICATION_ID)
    }

    // ------------------------------------------------------------------ sound

    private fun startRingtone() {
        for (type in RINGTONE_TYPES) {
            val uri: Uri = try {
                RingtoneManager.getDefaultUri(type) ?: continue
            } catch (e: Exception) {
                continue
            }
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(ALARM_AUDIO)
                mp.setDataSource(context, uri)
                mp.isLooping = true
                mp.setVolume(1f, 1f)
                mp.prepare()
                mp.start()
                player = mp
                Log.i(TAG, "ringtone $uri playing on the alarm stream")
                return
            } catch (e: Exception) {
                Log.w(TAG, "ringtone $uri failed: ${e.message ?: e.javaClass.simpleName}")
                try {
                    mp.release()
                } catch (ignored: Exception) {
                }
            }
        }
        Log.w(TAG, "no ringtone could be played; vibration only")
    }

    private fun stopRingtone() {
        val mp = player ?: return
        player = null
        try {
            if (mp.isPlaying) mp.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop ringtone: ${e.message}")
        }
        try {
            mp.release()
        } catch (e: Exception) {
            Log.w(TAG, "release ringtone: ${e.message}")
        }
    }

    private fun raiseAlarmVolume() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (current < max) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                savedAlarmVolume = current
                Log.i(TAG, "alarm volume raised $current -> $max for the ring")
            }
        } catch (e: Exception) {
            // SecurityException under some Do Not Disturb modes: ring at whatever the alarm volume is.
            Log.w(TAG, "could not raise the alarm volume: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun restoreAlarmVolume() {
        val v = savedAlarmVolume
        if (v < 0) return
        savedAlarmVolume = -1
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0)
            Log.i(TAG, "alarm volume restored to $v")
        } catch (e: Exception) {
            Log.w(TAG, "could not restore the alarm volume: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ vibration

    private fun vibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }

    private fun startVibration() {
        val v = vibrator() ?: return
        try {
            if (!v.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, ALARM_AUDIO)
            }
            Log.i(TAG, "vibrating")
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopVibration() {
        try {
            vibrator()?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel vibration: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ notification

    private fun notificationManager(): NotificationManager? = context.getSystemService(NotificationManager::class.java)

    private fun showNotification() {
        val nm = notificationManager() ?: return
        try {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rings the phone when the watch looks for it"
                    setSound(null, null)          // the ringtone is played by the alerter itself, looped
                    enableVibration(false)        // ditto for the vibration pattern
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val stop = WatchService.findPhoneStopIntent(context)
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_watch)
                .setContentTitle("Find my phone")
                .setContentText("Your Ryze Wave is looking for this phone")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(stop)
                .setDeleteIntent(stop)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_watch), "Stop", stop).build())
                .build()
            nm.notify(NOTIFICATION_ID, n)
            Log.i(TAG, "notification posted")
        } catch (e: Exception) {
            Log.w(TAG, "notification failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val TAG = "FindPhone"
        const val CHANNEL_ID = "find_phone"
        const val NOTIFICATION_ID = 1002
        private val RINGTONE_TYPES = intArrayOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
        /** off, on, off, on, off, on, pause — repeated. */
        private val VIBRATION_PATTERN = longArrayOf(0, 600, 300, 600, 300, 600, 1200)
        private val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
