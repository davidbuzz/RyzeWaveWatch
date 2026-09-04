package au.buzz.ryzewave

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import au.buzz.ryzewave.ble.WatchService
import au.buzz.ryzewave.ui.HealthConnectPermissionHost
import au.buzz.ryzewave.ui.RyzeApp
import au.buzz.ryzewave.ui.UiDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single activity: asks for the runtime permissions (Bluetooth connect/scan, fine location, notifications),
 * starts the watch foreground service once Bluetooth is allowed, owns the Health Connect permission launcher
 * and shows [RyzeApp].
 */
class MainActivity : ComponentActivity() {

    private val healthLauncher = registerForActivityResult(App.graph.health.permissionContract()) { granted ->
        healthHost.onResult(granted)
    }

    private val healthHost: HealthConnectPermissionHost by lazy {
        HealthConnectPermissionHost(this, lifecycleScope, App.graph.health.writePermissions) { healthLauncher.launch(it) }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onRuntimePermissions(result)
    }

    private var showHealthRationale by mutableStateOf(false)
    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showHealthRationale = isHealthRationale(intent)
        setContent {
            RyzeApp(
                healthHost = healthHost,
                showHealthRationale = showHealthRationale,
                onRationaleDismissed = { showHealthRationale = false },
            )
        }
        ensureDefaultMac()
        requestRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isHealthRationale(intent)) showHealthRationale = true
    }

    override fun onResume() {
        super.onResume()
        healthHost.refresh()
    }

    /** Health Connect opens us with these actions to explain why we want the permissions. */
    private fun isHealthRationale(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == ACTION_SHOW_PERMISSIONS_RATIONALE || action == Intent.ACTION_VIEW_PERMISSION_USAGE
    }

    /** First run: store the known watch address so the service has something to connect to. */
    private fun ensureDefaultMac() {
        lifecycleScope.launch {
            try {
                if (App.graph.settings.watchMac.first() == null) App.graph.settings.setWatchMac(UiDefaults.WATCH_MAC)
            } catch (e: Exception) {
                Log.w(TAG, "could not store the default watch MAC", e)
            }
        }
    }

    private fun runtimePermissions(): List<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
        }
        list += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        return list
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePermissions() {
        val missing = runtimePermissions().filterNot { granted(it) }
        if (missing.isEmpty()) startWatchService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onRuntimePermissions(result: Map<String, Boolean>) {
        val bluetoothOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)
        if (bluetoothOk) startWatchService() else Log.w(TAG, "Bluetooth permission denied: $result")
    }

    private fun startWatchService() {
        if (serviceStarted) return
        try {
            WatchService.start(this)
            serviceStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "WatchService.start failed", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        /** The action Health Connect resolves on Android 13 and lower (no constant in connect-client 1.1.0-alpha11). */
        const val ACTION_SHOW_PERMISSIONS_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
    }
}
