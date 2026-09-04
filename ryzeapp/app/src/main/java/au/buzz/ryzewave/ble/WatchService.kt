package au.buzz.ryzewave.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import au.buzz.ryzewave.App
import au.buzz.ryzewave.R
import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.core.WatchStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (type `connectedDevice`) that owns the watch link for the life of the process:
 *
 *  - connects to the MAC from [SettingsStore.watchMac] whenever Bluetooth is on and the link is not paused
 *    (re-connects when the address changes — dropping the old watch first — or the adapter is switched back on;
 *    the link's own backoff loop handles drops in between, and the service retries every 5 min as a safety net);
 *  - when the adapter is switched off the link is torn down explicitly (Android does not reliably deliver the
 *    disconnect callback in that case), so the reconnect happens cleanly once Bluetooth is back;
 *  - [pause] (the Dashboard's Disconnect) drops the link and keeps it down until [connect] / [start] is called
 *    again or the service is recreated, so a user disconnect is not undone by the retry loop;
 *  - the connect-time `applySettings` + `syncAll` run inside [WatchApiImpl] on every [LinkState.Ready]; the
 *    service repeats them every 30 min while connected;
 *  - the notification shows the connection state, battery and last sync time;
 *  - `START_STICKY`, so Android restarts it after a kill.
 *
 * Start with [start] (from the foreground, e.g. `MainActivity` once BLUETOOTH_CONNECT is granted, or from
 * [WatchConnectedReceiver] when the watch's ACL link comes up) and stop with [stop]; [requestSync] triggers an
 * immediate sync.
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var watch: WatchApi
    private lateinit var settings: SettingsStore
    private val bluetoothOn = MutableStateFlow(false)
    /** True after [pause]: the user dropped the link on purpose; cleared by [connect] / a MAC change. */
    private val paused = MutableStateFlow(false)
    private var receiver: BroadcastReceiver? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val graph = App.graph
        watch = graph.watch
        settings = graph.settings
        createChannel()
        foreground = goForeground(watch.status.value)
        if (!foreground) {
            stopSelf()
            return
        }
        registerBluetoothReceiver()
        bluetoothOn.value = adapter?.isEnabled == true   // after registering, so a toggle in between is not missed
        scope.launch { connectionLoop() }
        scope.launch { periodicSync() }
        scope.launch {
            watch.status
                .map { NotificationKey(it.state, it.batteryPercent, it.charging, it.lastSyncTime, it.message) to it }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, status) -> updateNotification(status) }
        }
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC -> scope.launch { syncNow("requested") }
            ACTION_CONNECT -> {
                paused.value = false
                scope.launch { connectNow() }
            }
            ACTION_PAUSE -> {
                paused.value = true
                scope.launch { safely("pause") { watch.disconnect() } }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "receiver already unregistered")
            }
        }
        receiver = null
        scope.cancel()
        if (::watch.isInitialized) {
            val w = watch
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    w.disconnect()
                } catch (e: Exception) {
                    Log.d(TAG, "disconnect on destroy: ${e.message}")
                }
            }
        }
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ link management

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun registerBluetoothReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth on")
                        bluetoothOn.value = true
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        if (bluetoothOn.value) Log.i(TAG, "Bluetooth off; dropping the link")
                        bluetoothOn.value = false
                        // The stack is torn down before any GATT callback: force the link down ourselves.
                        scope.launch { safely("disconnect (adapter off)") { watch.disconnect() } }
                    }
                }
            }
        }
        // ACTION_STATE_CHANGED is a protected system broadcast; EXPORTED is harmless and avoids the API 34 flag error.
        ContextCompat.registerReceiver(this, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    private suspend fun connectionLoop() {
        var lastMac: String? = null
        combine(settings.watchMac, bluetoothOn, paused) { mac, on, p -> Triple(mac, on, p) }
            .distinctUntilChanged()
            .collectLatest { (mac, on, isPaused) ->
                if (mac == null) {
                    Log.i(TAG, "no watch address configured")
                    lastMac = null
                    safely("disconnect") { watch.disconnect() }
                    return@collectLatest
                }
                if (lastMac != null && lastMac != mac) {
                    // A different watch was chosen: drop the old one so the loop below dials the new address.
                    Log.i(TAG, "watch address changed $lastMac -> $mac; disconnecting")
                    paused.value = false
                    safely("disconnect (address changed)") { watch.disconnect() }
                }
                lastMac = mac
                if (!on) {
                    Log.i(TAG, "Bluetooth is off; waiting")
                    return@collectLatest
                }
                if (isPaused) {
                    Log.i(TAG, "link paused by the user; not reconnecting")
                    return@collectLatest
                }
                while (currentCoroutineContext().isActive) {
                    val status = watch.status.value
                    val st = status.state
                    val wrongWatch = status.mac != null && !status.mac.equals(mac, ignoreCase = true) &&
                        (st == ConnectionState.CONNECTED || st == ConnectionState.SYNCING)
                    if (wrongWatch) safely("disconnect (wrong watch)") { watch.disconnect() }
                    if (st == ConnectionState.DISCONNECTED || st == ConnectionState.ERROR || wrongWatch) {
                        Log.i(TAG, "connecting to $mac")
                        safely("connect $mac") { watch.connect(mac) }
                    }
                    delay(RETRY_MS)
                }
            }
    }

    private suspend fun connectNow() {
        val mac = try {
            settings.watchMac.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        if (!bluetoothOn.value) return
        val status = watch.status.value
        if ((status.state == ConnectionState.CONNECTED || status.state == ConnectionState.SYNCING) &&
            status.mac != null && !status.mac.equals(mac, ignoreCase = true)
        ) {
            safely("disconnect (address changed)") { watch.disconnect() }
        }
        safely("connect $mac") { watch.connect(mac) }
    }

    private suspend fun periodicSync() {
        while (currentCoroutineContext().isActive) {
            delay(SYNC_INTERVAL_MS)
            syncNow("periodic")
        }
    }

    private suspend fun syncNow(reason: String) {
        if (watch.status.value.state != ConnectionState.CONNECTED) {
            Log.i(TAG, "sync ($reason) skipped: ${watch.status.value.state}")
            return
        }
        safely("sync ($reason)") {
            watch.applySettings(settings.profile.first(), settings.sampling.first())
            val r = watch.syncAll()
            Log.i(TAG, "sync ($reason): steps=${r.steps} hr=${r.hr} spo2=${r.spo2} sleep=${r.sleep}${r.error?.let { " error=$it" } ?: ""}")
        }
    }

    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ notification

    private data class NotificationKey(
        val state: ConnectionState,
        val battery: Int?,
        val charging: Boolean,
        val lastSync: Long?,
        val message: String?,
    )

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Watch connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the Bluetooth link to the Ryze Wave alive"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun goForeground(status: WatchStatus): Boolean = try {
        val n = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        true
    } catch (e: Exception) {
        // Android 12+: ForegroundServiceStartNotAllowedException when a sticky restart happens in the background;
        // Android 14+: SecurityException when BLUETOOTH_CONNECT is not granted. Either way we cannot run as a
        // foreground service now; WatchConnectedReceiver (ACL_CONNECTED) and MainActivity are the re-entry points.
        val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
            "not allowed from the background"
        } else {
            e.javaClass.simpleName
        }
        Log.e(TAG, "startForeground failed ($kind): ${e.message}", e)
        false
    }

    private fun updateNotification(status: WatchStatus) {
        if (!foreground) return
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(status))
        } catch (e: Exception) {
            Log.d(TAG, "notify: ${e.message}")
        }
    }

    private fun buildNotification(status: WatchStatus): Notification {
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        launchIntent()?.let { b.setContentIntent(it) }
        return b.build()
    }

    private fun launchIntent(): PendingIntent? {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun statusText(s: WatchStatus): String {
        val parts = ArrayList<String>(4)
        parts += when (s.state) {
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.CONNECTING -> "Connecting…"
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.SYNCING -> "Syncing…"
            ConnectionState.ERROR -> "Link lost"
        }
        s.batteryPercent?.let { parts += "$it %${if (s.charging) " charging" else ""}" }
        s.lastSyncTime?.let { parts += "synced ${TIME_FMT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" }
        if (s.state != ConnectionState.CONNECTED && s.state != ConnectionState.SYNCING) s.message?.let { parts += it }
        return parts.joinToString(" · ")
    }

    companion object {
        const val TAG = "WatchService"
        const val CHANNEL_ID = "watch_link"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "au.buzz.ryzewave.ble.action.STOP"
        const val ACTION_SYNC = "au.buzz.ryzewave.ble.action.SYNC"
        const val ACTION_CONNECT = "au.buzz.ryzewave.ble.action.CONNECT"
        const val ACTION_PAUSE = "au.buzz.ryzewave.ble.action.PAUSE"
        const val SYNC_INTERVAL_MS = 30 * 60_000L
        const val RETRY_MS = 5 * 60_000L
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Starts (or pokes) the foreground service. Call from the foreground with BLUETOOTH_CONNECT granted. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WatchService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot stop: ${e.message}")
            }
        }

        /** Asks the running service to connect now (e.g. right after the address was saved); also clears [pause]. */
        fun connect(context: Context) = send(context, ACTION_CONNECT)

        /** Drops the link and keeps it down (the Dashboard's Disconnect) until [connect] is called again. */
        fun pause(context: Context) = send(context, ACTION_PAUSE)

        /** Asks the running service to apply settings and sync now. */
        fun requestSync(context: Context) = send(context, ACTION_SYNC)

        private fun send(context: Context, action: String) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w(TAG, "cannot send $action: ${e.message}")
            }
        }
    }
}
