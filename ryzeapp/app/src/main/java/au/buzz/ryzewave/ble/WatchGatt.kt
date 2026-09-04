package au.buzz.ryzewave.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.protocol.Features
import au.buzz.ryzewave.protocol.Protocol
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [BluetoothGatt] wrapper for the Ryze Wave, ported from android/src/au/buzz/ryzebridge/BleService.java (the
 * proven GATT code) onto coroutines:
 *
 *  - one outstanding GATT operation at a time ([opMutex] + a [CompletableDeferred] completed by the callback);
 *  - `connectGatt(ctx, false, cb, TRANSPORT_LE)` → `discoverServices` → `requestMtu(247)` → CCCD notifications on
 *    33F2 and 34F2 → read 33F1 (feature bitmap, kept in [features]);
 *  - writes use `WRITE_TYPE_NO_RESPONSE` when the characteristic offers it, with the API 33
 *    `writeCharacteristic(c, value, type)` / `writeDescriptor(d, value)` overloads and the pre-33 fallbacks;
 *  - every notification goes through [BaseWatchLink.dispatch] (reply matching for `request` / `collect`, then the
 *    [packets] flow);
 *  - an unexpected disconnect fails the pending operation and every waiter, closes the gatt and, when the link is
 *    wanted, reconnects with exponential backoff ([Backoff]: 2, 4, 8 … 60 s) until it succeeds or [disconnect] is
 *    called. Every successful (re)connect bumps [LinkState.Ready.generation].
 *
 * Bluetooth permissions (BLUETOOTH_CONNECT on 12+) are the caller's responsibility; a missing permission surfaces
 * as a [GattException].
 */
@SuppressLint("MissingPermission")
class WatchGatt(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val autoReconnect: Boolean = true,
) : BaseWatchLink() {

    private val app: Context = context.applicationContext

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected(0))
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    @Volatile
    override var features: Features? = null
        private set

    /** Negotiated MTU (23 until `onMtuChanged`). */
    @Volatile
    var mtu: Int = DEFAULT_MTU
        private set

    /** The address the link is (or should be) connected to. */
    val mac: String? get() = targetMac

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    private val adapter: BluetoothAdapter?
        get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private enum class OpKind { DISCOVER, MTU, WRITE, READ, DESCRIPTOR }
    private class OpResult(val status: Int, val value: ByteArray?)
    private class PendingOp(val kind: OpKind, val result: CompletableDeferred<OpResult>)

    /** Serialises connect / disconnect / reconnect attempts. */
    private val connectMutex = Mutex()

    /** One outstanding GATT operation. */
    private val opMutex = Mutex()

    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var generation = 0

    @Volatile private var connected = false
    @Volatile private var pendingOp: PendingOp? = null
    @Volatile private var connectWaiter: CompletableDeferred<Int>? = null
    @Volatile private var disconnectWaiter: CompletableDeferred<Int>? = null
    @Volatile private var wantConnected = false
    @Volatile private var targetMac: String? = null
    @Volatile private var connecting = false
    @Volatile private var lastStatus = 0
    @Volatile private var cmdWrite: BluetoothGattCharacteristic? = null
    @Volatile private var dataWrite: BluetoothGattCharacteristic? = null

    // ------------------------------------------------------------------ WatchLink

    override suspend fun connect(mac: String) {
        val address = mac.trim().uppercase(Locale.ROOT)
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw GattException("invalid Bluetooth address '$mac'")
        wantConnected = true
        targetMac = address
        stopReconnectLoop()
        connectMutex.withLock {
            val st = _state.value
            if (st is LinkState.Ready && st.mac == address && connected && gatt != null) return
            try {
                connectInternal(address, attempt = 1, reconnected = false)
            } catch (e: GattException) {
                if (autoReconnect && wantConnected) startReconnectLoop(address, e.message)
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        wantConnected = false
        stopReconnectLoop()
        connectMutex.withLock {
            val g = synchronized(lock) { gatt }
            if (g != null && connected) {
                val dw = CompletableDeferred<Int>()
                disconnectWaiter = dw
                try {
                    g.disconnect()
                    withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { dw.await() }
                } catch (e: SecurityException) {
                    Log.w(TAG, "disconnect: ${e.message}")
                } finally {
                    disconnectWaiter = null
                }
            }
            failWaiters(GattException("disconnected"))
            closeGatt()
            _state.value = LinkState.Disconnected(0, "disconnected")
            Log.i(TAG, "DISCONNECTED (requested)")
        }
    }

    @Suppress("DEPRECATION")   // pre-33 setValue / writeCharacteristic(c) fallback
    override suspend fun write(cmd: ByteArray, channel: WatchChannel) {
        val c = (if (channel == WatchChannel.CMD) cmdWrite else dataWrite)
            ?: throw GattException("write ${channel.writeName}: not connected")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        logPacket("TX ${channel.writeName} ${cmd.toHex()}")
        val r = runOp(OpKind.WRITE, "write ${channel.writeName}", OP_TIMEOUT_MS) { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, cmd, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = cmd
                g.writeCharacteristic(c)
            }
        }
        if (r.status != BluetoothGatt.GATT_SUCCESS) throw GattException("write ${channel.writeName} failed (status ${r.status})")
    }

    /**
     * Drops a link the API layer found dead (every write fails "not connected" although the state is Ready) and,
     * when the link is wanted, restarts the backoff reconnect loop. Also used when the adapter is switched off
     * without a disconnect callback.
     */
    override suspend fun resetLink(reason: String) {
        val mac = targetMac
        Log.w(TAG, "RESET link: $reason")
        failWaiters(GattException("link reset: $reason"))
        closeGatt()
        _state.value = LinkState.Disconnected(lastStatus, reason)
        if (wantConnected && autoReconnect && mac != null) startReconnectLoop(mac, reason)
    }

    /** Cancels the internal scope (only when the link was created with the default scope). */
    fun shutdown() {
        wantConnected = false
        synchronized(lock) { reconnectJob?.cancel() }
        closeGatt()
        _state.value = LinkState.Disconnected(0, "shut down")
        scope.cancel()
    }

    // ------------------------------------------------------------------ connect sequence

    private suspend fun connectInternal(mac: String, attempt: Int, reconnected: Boolean) {
        val ad = adapter ?: throw GattException("this device has no Bluetooth adapter")
        if (!ad.isEnabled) throw GattException("Bluetooth is off")
        closeGatt()
        _state.value = LinkState.Connecting(mac, attempt)
        Log.i(TAG, "CONNECTING $mac (attempt $attempt${if (reconnected) ", auto" else ""})")
        val device = try {
            ad.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            throw GattException("invalid Bluetooth address '$mac'", cause = e)
        }
        val waiter = CompletableDeferred<Int>()
        connectWaiter = waiter
        connecting = true
        try {
            val g = try {
                device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                throw GattException("BLUETOOTH_CONNECT permission not granted", cause = e)
            } ?: throw GattException("connectGatt returned null")
            synchronized(lock) { gatt = g }
            val status = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiter.await() }
                ?: throw GattException("connect timeout after ${CONNECT_TIMEOUT_MS / 1000} s", timeout = true)
            if (status != BluetoothGatt.GATT_SUCCESS || !connected) throw GattException("connect failed (status $status)")
            connectWaiter = null

            val disc = runOp(OpKind.DISCOVER, "discoverServices", DISCOVER_TIMEOUT_MS) { it.discoverServices() }
            if (disc.status != BluetoothGatt.GATT_SUCCESS) throw GattException("service discovery failed (status ${disc.status})")
            val chars = resolveCharacteristics(g)

            try {
                val m = runOp(OpKind.MTU, "requestMtu", OP_TIMEOUT_MS) { it.requestMtu(Protocol.MTU) }
                if (m.status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "requestMtu status ${m.status}, mtu stays $mtu")
            } catch (e: GattException) {
                if (!e.timeout) throw e            // a lost link is fatal; only a missing callback is tolerated
                Log.w(TAG, "requestMtu: ${e.message}")
            }

            enableNotify(g, chars.cmdNotify)
            enableNotify(g, chars.dataNotify)
            cmdWrite = chars.cmdWrite
            dataWrite = chars.dataWrite
            readFeatures(chars.cmdWrite)

            // The link can drop between the last callback and here (readFeatures tolerates a timeout only, but the
            // disconnect callback may have raced it): never publish Ready on a gatt that is already gone.
            if (!connected || synchronized(lock) { gatt } == null) {
                throw GattException("link lost during setup (status $lastStatus)")
            }
            val gen = synchronized(lock) { ++generation }
            _state.value = LinkState.Ready(mac, mtu, reconnected, gen)
            Log.i(TAG, "READY $mac mtu=$mtu gen=$gen features=${features?.toString() ?: "?"}")
        } catch (e: Throwable) {
            val msg = if (e is CancellationException) "connect cancelled" else (e.message ?: e.javaClass.simpleName)
            Log.w(TAG, "connect $mac failed: $msg")
            closeGatt()
            _state.value = LinkState.Disconnected(lastStatus, msg)
            if (e is GattException || e is CancellationException) throw e
            throw GattException(msg, cause = e)
        } finally {
            connectWaiter = null
            connecting = false
        }
    }

    private class Characteristics(
        val cmdWrite: BluetoothGattCharacteristic,
        val cmdNotify: BluetoothGattCharacteristic,
        val dataWrite: BluetoothGattCharacteristic,
        val dataNotify: BluetoothGattCharacteristic,
    )

    private fun resolveCharacteristics(g: BluetoothGatt): Characteristics {
        fun need(uuid: UUID, name: String): BluetoothGattCharacteristic =
            find(g, uuid) ?: throw GattException("characteristic $name not found (not a Ryze Wave / UTE watch?)")
        return Characteristics(
            cmdWrite = need(WatchChannel.CMD.writeUuid, WatchChannel.CMD.writeName),
            cmdNotify = need(WatchChannel.CMD.notifyUuid, WatchChannel.CMD.notifyName),
            dataWrite = need(WatchChannel.DATA.writeUuid, WatchChannel.DATA.writeName),
            dataNotify = need(WatchChannel.DATA.notifyUuid, WatchChannel.DATA.notifyName),
        )
    }

    private fun find(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        g.getService(Protocol.SVC_CMD_UUID)?.getCharacteristic(uuid)?.let { return it }
        g.getService(Protocol.SVC_DATA_UUID)?.getCharacteristic(uuid)?.let { return it }
        for (s in g.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    @Suppress("DEPRECATION")   // pre-33 descriptor.setValue / writeDescriptor(d) fallback
    private suspend fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        val name = WatchChannel.forNotifyUuid(c.uuid)?.notifyName ?: c.uuid.toString()
        if (!g.setCharacteristicNotification(c, true)) throw GattException("setCharacteristicNotification $name failed")
        val d = c.getDescriptor(CCCD_UUID) ?: throw GattException("no CCCD on $name")
        val r = runOp(OpKind.DESCRIPTOR, "cccd $name", OP_TIMEOUT_MS) { gg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gg.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gg.writeDescriptor(d)
            }
        }
        if (r.status != BluetoothGatt.GATT_SUCCESS) throw GattException("CCCD write on $name failed (status ${r.status})")
        Log.i(TAG, "NOTIFY $name on")
    }

    private suspend fun readFeatures(c: BluetoothGattCharacteristic) {
        try {
            val r = runOp(OpKind.READ, "read 33F1", OP_TIMEOUT_MS) { it.readCharacteristic(c) }
            val v = r.value
            if (r.status == BluetoothGatt.GATT_SUCCESS && v != null && v.size == 20) {
                features = Features.parse(v)
                Log.i(TAG, "FEATURES ${v.toHex()} $features")
            } else {
                Log.w(TAG, "feature bitmap read: status ${r.status}, ${v?.size ?: 0} bytes")
            }
        } catch (e: GattException) {
            if (!e.timeout) throw e                // link lost / not connected: let connectInternal fail
            Log.w(TAG, "feature bitmap read failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ one outstanding op

    private suspend fun runOp(kind: OpKind, name: String, timeoutMs: Long, start: (BluetoothGatt) -> Boolean): OpResult =
        opMutex.withLock {
            val g = synchronized(lock) { gatt } ?: throw GattException("$name: not connected")
            if (!connected) throw GattException("$name: not connected")
            val op = PendingOp(kind, CompletableDeferred())
            pendingOp = op
            try {
                val started = try {
                    start(g)
                } catch (e: SecurityException) {
                    throw GattException("$name: BLUETOOTH_CONNECT permission not granted", cause = e)
                }
                if (!started) throw GattException("$name: rejected by the Bluetooth stack")
                withTimeoutOrNull(timeoutMs) { op.result.await() }
                    ?: throw GattException("$name: no callback within $timeoutMs ms", timeout = true)
            } finally {
                pendingOp = null
            }
        }

    private fun signal(kind: OpKind, status: Int, value: ByteArray?) {
        val op = pendingOp ?: return
        if (op.kind != kind) {
            Log.d(TAG, "ignoring $kind callback (status $status) while ${op.kind} is pending")
            return
        }
        op.result.complete(OpResult(status, value))
    }

    // ------------------------------------------------------------------ link loss / reconnect

    private fun onLinkLost(status: Int) {
        val e = GattException("link lost (status $status)")
        pendingOp?.result?.completeExceptionally(e)
        failWaiters(e)
        closeGatt()
        _state.value = LinkState.Disconnected(status, "link lost (status $status)")
        val mac = targetMac
        if (wantConnected && autoReconnect && !connecting && mac != null) startReconnectLoop(mac, "link lost (status $status)")
    }

    private fun startReconnectLoop(mac: String, reason: String?) {
        synchronized(lock) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                var attempt = 1
                while (isActive && wantConnected) {
                    val wait = Backoff.delayMs(attempt)
                    _state.value = LinkState.Disconnected(
                        lastStatus, "${reason ?: "disconnected"}; retrying in ${wait / 1000} s (attempt $attempt)",
                    )
                    Log.i(TAG, "reconnect to $mac in ${wait / 1000} s (attempt $attempt)")
                    delay(wait)
                    if (!wantConnected) break
                    val ok = connectMutex.withLock {
                        if (_state.value is LinkState.Ready && connected) {
                            true
                        } else {
                            try {
                                connectInternal(mac, attempt, reconnected = true)
                                true
                            } catch (e: GattException) {
                                false
                            }
                        }
                    }
                    if (ok) break
                    attempt++
                }
            }
        }
    }

    private suspend fun stopReconnectLoop() {
        val job = synchronized(lock) {
            val j = reconnectJob
            reconnectJob = null
            j
        }
        job?.cancelAndJoin()
    }

    private fun closeGatt() {
        val g = synchronized(lock) {
            val x = gatt
            gatt = null
            x
        }
        connected = false
        cmdWrite = null
        dataWrite = null
        if (g != null) {
            try {
                g.close()
            } catch (e: Exception) {
                Log.d(TAG, "close: ${e.message}")
            }
        }
        pendingOp?.result?.completeExceptionally(GattException("link closed"))
    }

    // ------------------------------------------------------------------ callbacks (binder thread)

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val mine = synchronized(lock) { g === gatt }
            if (!mine) {
                Log.d(TAG, "state change on a stale gatt (status $status, state $newState), closing it")
                try {
                    g.close()
                } catch (e: Exception) {
                    Log.d(TAG, "stale close: ${e.message}")
                }
                return
            }
            val nowConnected = newState == BluetoothProfile.STATE_CONNECTED
            connected = nowConnected
            lastStatus = status
            Log.i(TAG, "STATE ${if (nowConnected) "connected" else "disconnected"} status=$status")
            val cw = connectWaiter
            if (cw != null && !cw.isCompleted) {
                cw.complete(status)
                return
            }
            if (!nowConnected) {
                val dw = disconnectWaiter
                if (dw != null && !dw.isCompleted) {
                    dw.complete(status)
                    return
                }
                onLinkLost(status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "SERVICES ${g.services.size} status=$status")
            signal(OpKind.DISCOVER, status, null)
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m
            Log.i(TAG, "MTU $m status=$status")
            signal(OpKind.MTU, status, null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            signal(OpKind.WRITE, status, null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            signal(OpKind.DESCRIPTOR, status, null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            signal(OpKind.READ, status, value.copyOf())
        }

        @Deprecated("pre-API 33 path; the framework calls the 4-argument overload on 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) signal(OpKind.READ, status, c.value?.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onRx(c, value)
        }

        @Deprecated("pre-API 33 path; the framework calls the 3-argument overload on 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onRx(c, c.value ?: ByteArray(0))
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(TAG, "PHY tx=$txPhy rx=$rxPhy status=$status")
        }

        /**
         * `onConnectionUpdated(gatt, interval, latency, timeout, status)` is hidden from the public SDK but the
         * framework still invokes it at runtime (no `override` possible); kept for the log like the bridge does.
         */
        @Suppress("unused", "UNUSED_PARAMETER")
        fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            Log.i(TAG, "CONNPARAMS interval=$interval latency=$latency timeout=$timeout status=$status")
        }
    }

    private fun onRx(c: BluetoothGattCharacteristic, value: ByteArray) {
        val channel = WatchChannel.forNotifyUuid(c.uuid) ?: return   // 35F2 (payments) and friends: ignore
        val data = value.copyOf()
        logPacket("RX ${channel.notifyName} ${data.toHex()}")
        dispatch(channel, data)
    }

    /**
     * Raw protocol trace, one line per packet ("TX 33F1 <hex>" / "RX 34F2 <hex>", like RyzeBridge), read with
     * `adb logcat -s WatchGatt:D`. Written at INFO, not DEBUG: this phone (Moto g05, `log.tag=I`) drops DEBUG
     * lines in liblog, so a `Log.d` trace never reached logcat.
     */
    private fun logPacket(line: String) {
        Log.i(TAG, line)
    }

    companion object {
        const val TAG = "WatchGatt"
        const val DEFAULT_MTU = 23
        const val CONNECT_TIMEOUT_MS = 35_000L
        const val DISCOVER_TIMEOUT_MS = 30_000L
        const val OP_TIMEOUT_MS = 5_000L
        const val DISCONNECT_TIMEOUT_MS = 2_000L
    }
}

/** Android logger for [WatchApiImpl] (which itself has no Android imports so it stays unit-testable). */
val androidWatchLogger: (String, Throwable?) -> Unit = { msg, t ->
    if (t == null) Log.i(WatchGatt.TAG, msg) else Log.w(WatchGatt.TAG, msg, t)
}

/**
 * Convenience for the app graph: a [WatchApiImpl] on top of a [WatchGatt]. The [scope] hosts the reconnect loop,
 * the packet dispatcher and the connect-time setup + sync; it should live as long as the process.
 */
fun createWatchApi(
    context: Context,
    repo: HealthRepository,
    settings: SettingsStore,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): WatchApiImpl = WatchApiImpl(
    link = WatchGatt(context, scope),
    repo = repo,
    settings = settings,
    scope = scope,
    log = androidWatchLogger,
)
