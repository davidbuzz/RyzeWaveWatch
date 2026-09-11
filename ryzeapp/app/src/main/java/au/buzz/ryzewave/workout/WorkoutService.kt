package au.buzz.ryzewave.workout

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.buzz.ryzewave.App
import au.buzz.ryzewave.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service (type `location`) that keeps a workout alive while the screen is off: it takes fused
 * location fixes at 1 Hz with high accuracy and feeds them to the process-wide [WorkoutController]
 * ([WorkoutSession]), holds a partial wake lock so the one-second ticker and the BLE pushes keep running,
 * and shows an ongoing notification with the live numbers plus pause/resume/stop actions.
 *
 * Commands are intents ([ACTION_START] with [EXTRA_SPORT_TYPE], [ACTION_PAUSE], [ACTION_RESUME],
 * [ACTION_STOP]); use the companion helpers or [WorkoutSession]. The service stops itself as soon as the
 * controller reports STOPPED. Registered in the manifest as `.workout.WorkoutService`,
 * `foregroundServiceType="location"`.
 */
class WorkoutService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: WorkoutController
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var notifications: NotificationManager
    private var locationOn = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateJob: Job? = null
    private var sessionJob: Job? = null

    // Spoken workout cues (pause/resume/start/stop), driven by the controller's state so they cover the app
    // buttons and the watch's buttons alike, each exactly once (see StateAnnouncer). Spoken through the
    // process-wide engine (App.graph.speaker), which the stuck-workout detector shares.
    private val announcer = StateAnnouncer()

    // Phone step counter (TYPE_STEP_COUNTER): its tally, paused-time excluded, becomes Workout.phoneSteps.
    private val phoneSteps = PhoneStepCounter()
    private var sensorManager: SensorManager? = null
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
            val cumulative = event.values.firstOrNull()?.toLong() ?: return
            phoneSteps.onReading(cumulative)
            controller.onPhoneSteps(phoneSteps.steps)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) onFix(location)
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            controller.onGpsAvailability(availability.isLocationAvailable)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = WorkoutSession.controller()
        fused = LocationServices.getFusedLocationProviderClient(this)
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
        startSessionObserver()
        startStepCounter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start (including notification actions via getForegroundService) must promote to foreground
        // promptly, or Android kills the process.
        if (!goForeground()) {
            finish()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                val sportType = intent.getIntExtra(EXTRA_SPORT_TYPE, DEFAULT_SPORT_TYPE)
                val fromWatch = intent.getBooleanExtra(EXTRA_FROM_WATCH, false)
                ensureTracking()   // GPS warms up while the watch is being told to start
                command("start") { if (!controller.state.value.isActive) controller.start(sportType, fromWatch) }
            }
            // GPS is deliberately NOT stopped on pause (see ensureTracking): fixes keep coming so the track
            // stays continuous; the controller stores them tagged paused and adds no distance.
            ACTION_PAUSE -> command("pause") { controller.pause() }
            ACTION_RESUME -> command("resume") { controller.resume() }
            ACTION_STOP -> command("stop") { controller.stop() }
            else -> command("noop") { }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        sessionJob?.cancel()
        stopStepCounter()
        stopLocationUpdates()
        releaseWakeLock()
        if (controller.state.value.isActive) {
            // The system took the service away mid-workout: close the stored row on the session scope.
            Log.w(TAG, "destroyed while a workout was active; stopping it")
            WorkoutSession.scope.launch {
                try {
                    controller.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "stop after destroy failed", e)
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---- commands ------------------------------------------------------------------------------------

    /** Runs a controller command, then either keeps tracking (workout active) or shuts the service down. */
    private fun command(name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$name failed", e)
                controller.reportError("$name: ${e.message ?: e.javaClass.simpleName}")
            }
            if (controller.state.value.isActive) ensureTracking() else finish()
        }
    }

    /** GPS, wake lock and the notification updater; idempotent. */
    private fun ensureTracking() {
        acquireWakeLock()
        startLocationUpdates()
        observeState()
    }

    private fun observeState() {
        if (stateJob?.isActive == true) return
        stateJob = scope.launch {
            var wasActive = false
            // StateFlow is conflated: while we sleep, intermediate updates collapse into the latest one.
            controller.state.collect { st ->
                if (st.isActive) {
                    wasActive = true
                } else if (wasActive) {
                    updateNotification(st)
                    finish()
                    return@collect
                }
                updateNotification(st)
                delay(NOTIFY_MIN_INTERVAL_MS)
            }
        }
    }

    /** Tear everything down; safe to call more than once. */
    private fun finish() {
        stateJob?.cancel()
        stateJob = null
        stopLocationUpdates()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- foreground / notification -------------------------------------------------------------------

    private fun goForeground(): Boolean {
        val notification = buildNotification(controller.state.value)
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (e: Exception) {
            // Android 14+: SecurityException without a location permission,
            // ForegroundServiceStartNotAllowedException when started from the background.
            Log.e(TAG, "startForeground failed", e)
            controller.reportError("foreground service: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Live numbers of the current workout"
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun updateNotification(st: WorkoutState) {
        try {
            notifications.notify(NOTIFICATION_ID, buildNotification(st))
        } catch (e: Exception) {
            Log.d(TAG, "notify: ${e.message}")
        }
    }

    private fun buildNotification(st: WorkoutState): Notification {
        val title = when (st.state) {
            WorkoutPhase.RUNNING -> "Workout running"
            WorkoutPhase.PAUSED -> "Workout paused"
            WorkoutPhase.STOPPED -> "Workout"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle(title)
            .setContentText(WorkoutFormat.summary(st))
            .setStyle(NotificationCompat.BigTextStyle().bigText(WorkoutFormat.detail(st)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        launchPendingIntent()?.let { builder.setContentIntent(it) }
        when (st.state) {
            WorkoutPhase.RUNNING -> builder.addAction(0, "Pause", servicePendingIntent(ACTION_PAUSE, 1))
            WorkoutPhase.PAUSED -> builder.addAction(0, "Resume", servicePendingIntent(ACTION_RESUME, 2))
            WorkoutPhase.STOPPED -> Unit
        }
        if (st.isActive) builder.addAction(0, "Stop", servicePendingIntent(ACTION_STOP, 3))
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, requestCode, intent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Opens the app (its launcher activity) when the notification is tapped. */
    private fun launchPendingIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---- location ------------------------------------------------------------------------------------

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Precise location is required: "approximate" fixes are coarsened to ~2 km and the tracker rejects every one. */
    private fun hasFineLocationPermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission")   // checked in hasFineLocationPermission()
    private fun startLocationUpdates() {
        if (locationOn) return
        if (!hasFineLocationPermission()) {
            controller.onGpsAvailability(false)
            controller.reportError(
                if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) "Precise location required for GPS distance (only approximate location is allowed)"
                else "Location permission missing: no GPS distance",
            )
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationOn = true
            controller.clearReportedError()
        } catch (e: SecurityException) {
            controller.onGpsAvailability(false)
            controller.reportError("Location permission missing: no GPS distance")
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates failed", e)
            controller.onGpsAvailability(false)
            controller.reportError("GPS unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopLocationUpdates() {
        if (!locationOn) return
        locationOn = false
        try {
            fused.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.d(TAG, "removeLocationUpdates: ${e.message}")
        }
    }

    private fun onFix(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else NO_ACCURACY_M
        val speed = if (location.hasSpeed()) location.speed else 0f
        val time = if (location.time > 0L) location.time else System.currentTimeMillis()
        val altitude = if (location.hasAltitude()) location.altitude else null
        controller.onLocation(time, location.latitude, location.longitude, accuracy, speed, altitude)
    }

    // ---- wake lock -----------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock unavailable: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.d(TAG, "wake lock release: ${e.message}")
        }
    }

    // ---- spoken cues + phone steps -------------------------------------------------------------------

    private fun speak(text: String) {
        App.graph.speaker.speak(text)              // logs every utterance at INFO, even when TTS is not ready
    }

    /** One collector of the controller's state: speaks the phase transitions and pauses the phone step counter. */
    private fun startSessionObserver() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch {
            var lastPhase: WorkoutPhase? = null
            controller.state.collect { st ->
                if (st.state != lastPhase) {
                    lastPhase = st.state
                    announcer.onPhase(st.state, st.stopReason)?.let { speak(it) }
                    phoneSteps.setPaused(st.state == WorkoutPhase.PAUSED)
                }
            }
        }
    }

    private fun startStepCounter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            Log.i(TAG, "phone step counter skipped: ACTIVITY_RECOGNITION not granted")
            return
        }
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            Log.i(TAG, "phone step counter skipped: no TYPE_STEP_COUNTER sensor")
            return
        }
        phoneSteps.reset()
        sensorManager = sm
        try {
            sm.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: Exception) {
            Log.w(TAG, "step counter register failed: ${e.message}")
        }
    }

    private fun stopStepCounter() {
        try {
            sensorManager?.unregisterListener(stepListener)
        } catch (e: Exception) {
            Log.d(TAG, "step counter unregister: ${e.message}")
        }
        sensorManager = null
    }

    companion object {
        private const val TAG = "WorkoutService"
        const val ACTION_START = "au.buzz.ryzewave.workout.action.START"
        const val ACTION_PAUSE = "au.buzz.ryzewave.workout.action.PAUSE"
        const val ACTION_RESUME = "au.buzz.ryzewave.workout.action.RESUME"
        const val ACTION_STOP = "au.buzz.ryzewave.workout.action.STOP"
        const val EXTRA_SPORT_TYPE = "sportType"
        /** Set on [ACTION_START] when the watch itself began the workout: the controller must not echo `FD 11`. */
        const val EXTRA_FROM_WATCH = "fromWatch"
        const val DEFAULT_SPORT_TYPE = 1
        const val CHANNEL_ID = "workout"
        const val CHANNEL_NAME = "Workout"
        /** The BLE link service is expected to use 1; keep the two notifications apart. */
        const val NOTIFICATION_ID = 2
        const val LOCATION_INTERVAL_MS = 1_000L
        const val NOTIFY_MIN_INTERVAL_MS = 1_000L
        /** Substituted when a fix carries no accuracy at all, so the tracker rejects it. */
        const val NO_ACCURACY_M = 9_999f
        private const val WAKE_LOCK_TAG = "ryzewave:workout"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L

        fun intent(context: Context, action: String, sportType: Int? = null): Intent =
            Intent(context, WorkoutService::class.java).setAction(action).apply {
                if (sportType != null) putExtra(EXTRA_SPORT_TYPE, sportType)
            }

        /**
         * Starts the foreground service and the workout; the caller must hold the location permission.
         * [fromWatch]: the watch already began the workout itself — track it without echoing `FD 11`. On
         * Android 12+ this can throw `ForegroundServiceStartNotAllowedException` when the app is in the
         * background; the watch-start path catches it and announces the watch-only fallback.
         */
        fun start(context: Context, sportType: Int = DEFAULT_SPORT_TYPE, fromWatch: Boolean = false) {
            val intent = intent(context, ACTION_START, sportType).putExtra(EXTRA_FROM_WATCH, fromWatch)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_PAUSE))
        }

        fun resume(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_RESUME))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_STOP))
        }
    }
}
