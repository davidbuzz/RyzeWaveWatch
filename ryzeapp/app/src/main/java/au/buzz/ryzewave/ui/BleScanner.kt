package au.buzz.ryzewave.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A watch found by the BLE scan. */
data class ScannedDevice(val name: String, val mac: String, val rssi: Int)

/**
 * Minimal BLE scanner for the Settings screen: lists advertising devices whose name starts with
 * [UiDefaults.WATCH_NAME_PREFIX]. Scanning stops when the collector is cancelled.
 */
class BleScanner(context: Context) {
    private val app: Context = context.applicationContext

    /** True when the runtime permissions needed to scan (and read names) are granted. */
    fun hasPermissions(): Boolean = missingPermissions(app).isEmpty()

    /** Emits the (growing, RSSI-sorted) list of matching devices; fails with an exception when scanning is impossible. */
    @SuppressLint("MissingPermission")
    fun scan(namePrefix: String = UiDefaults.WATCH_NAME_PREFIX): Flow<List<ScannedDevice>> = callbackFlow {
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            close(IllegalStateException("No Bluetooth adapter"))
            return@callbackFlow
        }
        if (!adapter.isEnabled) {
            close(IllegalStateException("Bluetooth is turned off"))
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("BLE scanning unavailable"))
            return@callbackFlow
        }
        val found = LinkedHashMap<String, ScannedDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handle(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed (error $errorCode)"))
            }

            private fun handle(r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: try {
                    r.device.name
                } catch (e: SecurityException) {
                    null
                } ?: return
                if (!name.startsWith(namePrefix, ignoreCase = true)) return
                found[r.device.address] = ScannedDevice(name, r.device.address, r.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(IllegalStateException("Bluetooth scan permission missing", e))
            return@callbackFlow
        }
        trySend(emptyList())
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                // adapter went away; nothing to stop
            }
        }
    }

    companion object {
        /** Runtime permissions the scan needs on this Android version. */
        fun scanPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun missingPermissions(context: Context): List<String> = scanPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
}
