package au.buzz.ryzewave.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Health Connect availability and permission state as seen by the UI. The activity owns the permission
 * launcher (it must be registered before the activity starts), so it implements this and hands it to
 * [RyzeApp]; screens reach it through [LocalHealthPermissionHost].
 */
interface HealthPermissionHost {
    /** One of HealthConnectClient.SDK_AVAILABLE / SDK_UNAVAILABLE / SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    val sdkStatus: StateFlow<Int>

    /** True when every permission the exporter needs has been granted. */
    val granted: StateFlow<Boolean>

    /** Opens the Health Connect permission dialog for the missing permissions. */
    fun request()

    /** Re-reads availability and the granted set (call on resume and after a request). */
    fun refresh()
}

/** Used in previews / when no activity provides a host: Health Connect unavailable, nothing granted. */
object NoHealthPermissionHost : HealthPermissionHost {
    override val sdkStatus: StateFlow<Int> = MutableStateFlow(HealthConnectClient.SDK_UNAVAILABLE)
    override val granted: StateFlow<Boolean> = MutableStateFlow(false)
    override fun request() {}
    override fun refresh() {}
}

val LocalHealthPermissionHost = staticCompositionLocalOf<HealthPermissionHost> { NoHealthPermissionHost }

/** Human-readable availability. */
fun healthSdkLabel(status: Int): String = when (status) {
    HealthConnectClient.SDK_AVAILABLE -> "Health Connect available"
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect needs an update"
    else -> "Health Connect not available on this phone"
}

/**
 * The activity-side implementation: checks `HealthConnectClient.getSdkStatus`, reads the granted set through
 * the permission controller and launches the exporter's permission contract via [launch].
 */
class HealthConnectPermissionHost(
    context: Context,
    private val scope: CoroutineScope,
    private val required: Set<String>,
    private val launch: (Set<String>) -> Unit,
) : HealthPermissionHost {
    private val app: Context = context.applicationContext
    private val _sdkStatus = MutableStateFlow(HealthConnectClient.SDK_UNAVAILABLE)
    private val _granted = MutableStateFlow(false)
    override val sdkStatus: StateFlow<Int> = _sdkStatus.asStateFlow()
    override val granted: StateFlow<Boolean> = _granted.asStateFlow()

    override fun request() {
        refreshSdkStatus()
        if (_sdkStatus.value != HealthConnectClient.SDK_AVAILABLE) return
        try {
            launch(required)
        } catch (e: Exception) {
            Log.w(TAG, "permission request failed", e)
        }
    }

    override fun refresh() {
        refreshSdkStatus()
        if (_sdkStatus.value != HealthConnectClient.SDK_AVAILABLE) {
            _granted.value = false
            return
        }
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(app)
                val have = client.permissionController.getGrantedPermissions()
                _granted.value = required.isNotEmpty() && have.containsAll(required)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "getGrantedPermissions failed", e)
                _granted.value = false
            }
        }
    }

    /** From the activity result of the permission contract. */
    fun onResult(grantedNow: Set<String>) {
        _granted.value = required.isNotEmpty() && grantedNow.containsAll(required)
        refresh()
    }

    private fun refreshSdkStatus() {
        _sdkStatus.value = try {
            HealthConnectClient.getSdkStatus(app)
        } catch (e: Exception) {
            Log.w(TAG, "getSdkStatus failed", e)
            HealthConnectClient.SDK_UNAVAILABLE
        }
    }

    companion object {
        private const val TAG = "HealthPermissions"
    }
}
