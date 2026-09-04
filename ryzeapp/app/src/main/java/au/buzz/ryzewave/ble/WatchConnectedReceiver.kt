package au.buzz.ryzewave.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import au.buzz.ryzewave.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background re-entry point for the watch link: when the phone's Bluetooth stack reports an ACL connection to
 * the configured watch (`BluetoothDevice.ACTION_ACL_CONNECTED`, which Android 12+ lists as an exemption for
 * starting a foreground service from the background), start [WatchService]. Together with `START_STICKY` this
 * means a process killed under memory pressure comes back the next time the watch is seen, without the user
 * having to open the app. Registered in the manifest.
 */
class WatchConnectedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "ACL connected $address but BLUETOOTH_CONNECT is not granted; ignoring")
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val wanted = withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { App.graph.settings.watchMac.first() }
                if (wanted != null && wanted.equals(address, ignoreCase = true)) {
                    Log.i(TAG, "watch $address connected at the ACL level; starting WatchService")
                    WatchService.start(app)
                } else {
                    Log.d(TAG, "ACL connected $address is not the configured watch ($wanted)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not react to ACL_CONNECTED: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WatchConnectedReceiver"
        private const val SETTINGS_TIMEOUT_MS = 5_000L
    }
}
