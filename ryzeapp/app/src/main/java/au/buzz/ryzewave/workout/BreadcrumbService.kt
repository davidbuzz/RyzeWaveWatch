package au.buzz.ryzewave.workout

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.buzz.ryzewave.App
import au.buzz.ryzewave.core.Breadcrumb
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The opt-in always-on GPS breadcrumb (Settings; docs/PLAN.md): a location-type foreground service that records where
 * the phone goes while the phone itself reports that you are walking, running or cycling, and stops sampling when
 * you are still or in a vehicle, so a workout whose own tracking failed can be rebuilt from the trail — without
 * depending on Google Fit being set up. The decisions are [BreadcrumbGate]'s (pure, unit-tested); this class feeds
 * it the activity-recognition transitions (a mutable PendingIntent to a receiver registered while the service lives),
 * the significant-motion trigger sensor as a fallback, and a minute ticker, and turns fused location updates on/off
 * at the gate's interval (balanced-power accuracy). While a workout of ours is running the workout records its own
 * track, so crumbs are not stored then. Crumbs older than [RETENTION_MS] are pruned at start and daily.
 */
class BreadcrumbService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = BreadcrumbGate()
    private lateinit var fused: FusedLocationProviderClient
    private var locationOn = false
    private var currentIntervalMs = 0L
    private var ticker: Job? = null
    private var lastActivity: String? = null
    private var stored = 0
    private var transitionReceiver: BroadcastReceiver? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (WorkoutSession.state.value.isActive) return      // a live workout records its own track
            val crumb = Breadcrumb(
                time = loc.time, lat = loc.latitude, lon = loc.longitude, accuracyM = loc.accuracy,
                speedMps = if (loc.hasSpeed()) loc.speed else 0f, altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                activity = lastActivity,
            )
            scope.launch {
                try {
                    App.graph.repo.insertBreadcrumbs(listOf(crumb))
                    stored++
                    if (stored % 20 == 1) Log.i(TAG, "stored $stored crumb(s) this run (acc ${loc.accuracy.toInt()} m, ${lastActivity ?: "?"})")
                } catch (e: Exception) {
                    Log.w(TAG, "store failed: ${e.message}")
                }
            }
        }
    }

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            apply(gate.onSignificantMotion(System.currentTimeMillis()), "significant motion")
            requestMotionTrigger()      // one-shot sensor: re-arm
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        ensureChannel()
        if (!startInForeground()) return         // refused (location off / ineligible): do not wire anything up
        registerTransitions()
        requestMotionTrigger()
        ticker = scope.launch {
            var lastPrune = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                apply(gate.tick(now), "tick")
                if (now - lastPrune > 24 * 3600_000L) {
                    lastPrune = now
                    try {
                        val n = App.graph.repo.pruneBreadcrumbsBefore(now - RETENTION_MS)
                        if (n > 0) Log.i(TAG, "pruned $n crumb(s) older than ${RETENTION_MS / 86_400_000L} days")
                    } catch (e: Exception) {
                        Log.w(TAG, "prune failed: ${e.message}")
                    }
                }
                delay(TICK_MS)
            }
        }
        Log.i(TAG, "breadcrumb service started (gate: still grace 2 min, vehicle off, stale 10 min)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        setLocation(false, 0L)
        transitionReceiver?.let { runCatching { unregisterReceiver(it) } }
        runCatching { ActivityRecognition.getClient(this).removeActivityTransitionUpdates(transitionPendingIntent()) }
        runCatching { (getSystemService(SENSOR_SERVICE) as SensorManager).cancelTriggerSensor(motionListener, motionSensor()) }
        scope.cancel()
        Log.i(TAG, "breadcrumb service stopped ($stored crumb(s) stored this run)")
        super.onDestroy()
    }

    // ---- inputs ------------------------------------------------------------------------------------------

    private fun registerTransitions() {
        if (!granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            Log.w(TAG, "ACTIVITY_RECOGNITION not granted: only the motion sensor can turn the trail on")
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!ActivityTransitionResult.hasResult(intent)) return
                val result = ActivityTransitionResult.extractResult(intent) ?: return
                for (e in result.transitionEvents) {
                    val enter = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    val a = when (e.activityType) {
                        DetectedActivity.WALKING -> BreadcrumbGate.Activity.WALKING
                        DetectedActivity.RUNNING -> BreadcrumbGate.Activity.RUNNING
                        DetectedActivity.ON_FOOT -> BreadcrumbGate.Activity.ON_FOOT
                        DetectedActivity.ON_BICYCLE -> BreadcrumbGate.Activity.ON_BICYCLE
                        DetectedActivity.IN_VEHICLE -> BreadcrumbGate.Activity.IN_VEHICLE
                        DetectedActivity.STILL -> BreadcrumbGate.Activity.STILL
                        else -> BreadcrumbGate.Activity.UNKNOWN
                    }
                    val now = System.currentTimeMillis()
                    // leaving an activity without entering another one: treat as unknown (the next ENTER decides)
                    val effective = if (enter) a else BreadcrumbGate.Activity.UNKNOWN
                    if (enter) lastActivity = a.name
                    apply(gate.onActivity(effective, now), "${if (enter) "enter" else "exit"} ${a.name.lowercase()}")
                }
            }
        }
        val filter = IntentFilter(ACTION_TRANSITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
        transitionReceiver = receiver
        val types = listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE, DetectedActivity.STILL)
        val transitions = types.flatMap { t ->
            listOf(
                ActivityTransition.Builder().setActivityType(t).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(t).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
            )
        }
        try {
            ActivityRecognition.getClient(this).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), transitionPendingIntent())
                .addOnSuccessListener { Log.i(TAG, "activity transitions requested") }
                .addOnFailureListener { Log.w(TAG, "activity transitions unavailable: ${it.message}") }
        } catch (e: SecurityException) {
            Log.w(TAG, "activity transitions: ${e.message}")
        }
    }

    private fun transitionPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(this, 71, Intent(ACTION_TRANSITION).setPackage(packageName), flags)
    }

    private fun motionSensor(): Sensor? = (getSystemService(SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private fun requestMotionTrigger() {
        val sensor = motionSensor() ?: return
        runCatching { (getSystemService(SENSOR_SERVICE) as SensorManager).requestTriggerSensor(motionListener, sensor) }
    }

    // ---- output --------------------------------------------------------------------------------------------

    private fun apply(d: BreadcrumbGate.Decision, why: String) {
        if (d.locationOn != locationOn || (d.locationOn && d.intervalMs != currentIntervalMs)) {
            Log.i(TAG, "$why -> location ${if (d.locationOn) "ON every ${d.intervalMs / 1000} s" else "off"} (${d.reason})")
            setLocation(d.locationOn, d.intervalMs)
        }
    }

    private fun setLocation(on: Boolean, intervalMs: Long) {
        if (!on) {
            if (locationOn) runCatching { fused.removeLocationUpdates(locationCallback) }
            locationOn = false
            currentIntervalMs = 0L
            return
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.w(TAG, "no location permission: cannot record")
            return
        }
        if (locationOn) runCatching { fused.removeLocationUpdates(locationCallback) }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationOn = true
            currentIntervalMs = intervalMs
        } catch (e: SecurityException) {
            Log.w(TAG, "requestLocationUpdates: ${e.message}")
        }
    }

    // ---- foreground plumbing -------------------------------------------------------------------------------

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "GPS breadcrumb", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Records where you go while you move, so a workout can be rebuilt"
            setShowBadge(false)
        })
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("GPS breadcrumb on")
        .setContentText("Recording only while you move; off when still or driving")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    /** Returns false (and stops the service) if the platform refuses the location foreground service. */
    private fun startInForeground(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            true
        } catch (e: Exception) {
            // e.g. SecurityException "Starting FGS with type location" when started while ineligible / location off.
            Log.w(TAG, "cannot start the location foreground service: ${e.message}; stopping")
            stopSelf()
            false
        }
    }

    companion object {
        const val TAG = "Breadcrumb"
        const val CHANNEL_ID = "breadcrumb"
        const val NOTIFICATION_ID = 7301
        const val ACTION_STOP = "au.buzz.ryzewave.breadcrumb.action.STOP"
        const val ACTION_TRANSITION = "au.buzz.ryzewave.breadcrumb.action.TRANSITION"
        const val TICK_MS = 60_000L
        const val RETENTION_MS = 14L * 24 * 3600_000L

        /** Starts the trail (a foreground service; from the app in the foreground or an exempt context). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BreadcrumbService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start now: ${e.message}")
            }
        }

        fun stop(context: Context) {
            // stopService never creates the service (a start-with-ACTION would run onCreate -> startForeground and
            // crash when it is not meant to be running); it is a no-op if the service is not up.
            context.stopService(Intent(context, BreadcrumbService::class.java))
        }
    }
}
