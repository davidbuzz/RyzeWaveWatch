package au.buzz.ryzewave.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.health.connect.client.HealthConnectClient
import au.buzz.ryzewave.health.HealthConnectExporter
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

    /**
     * True when every permission the *exporter* needs has been granted (`HealthConnectExporter.REQUIRED_PERMISSIONS`):
     * the same test `HealthConnectExporter.canExport()` makes, so the Settings gate and the background exports agree.
     * The optional exercise-route permission does not count here (see [optionalMissing]).
     */
    val granted: StateFlow<Boolean>

    /** Requested-but-optional permissions (the exercise route) that are not granted; empty when all were granted. */
    val optionalMissing: StateFlow<Set<String>>

    /** Opens the Health Connect permission dialog for the missing permissions. */
    fun request()

    /** Re-reads availability and the granted set (call on resume and after a request). */
    fun refresh()
}

/** Used in previews / when no activity provides a host: Health Connect unavailable, nothing granted. */
object NoHealthPermissionHost : HealthPermissionHost {
    override val sdkStatus: StateFlow<Int> = MutableStateFlow(HealthConnectClient.SDK_UNAVAILABLE)
    override val granted: StateFlow<Boolean> = MutableStateFlow(false)
    override val optionalMissing: StateFlow<Set<String>> = MutableStateFlow(HealthConnectExporter.OPTIONAL_PERMISSIONS)
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

/** What a granted permission set means for the Settings screen (see [HealthPermissionGate.evaluate]). */
data class HealthGrants(
    /** Every required permission is granted: exports run and "Export now" is enabled. */
    val exportAllowed: Boolean,
    /** Required permissions still missing (empty when [exportAllowed]). */
    val missingRequired: Set<String>,
    /** Optional permissions still missing (the exercise route): exports run but sessions carry no GPS route. */
    val missingOptional: Set<String>,
)

/**
 * The pure decision behind the Settings gate, kept Android-free so it is unit-tested: the gate is computed
 * against the exporter's *required* set, never against the (larger) set the UI *requests*. Declining only the
 * optional exercise-route permission in the Health Connect dialog therefore leaves "Export now" enabled and the
 * status green — which is what the background exports do anyway — with a hint that routes are off.
 */
object HealthPermissionGate {
    fun evaluate(
        have: Set<String>,
        required: Set<String> = HealthConnectExporter.REQUIRED_PERMISSIONS,
        optional: Set<String> = HealthConnectExporter.OPTIONAL_PERMISSIONS,
    ): HealthGrants {
        val missingRequired = required - have
        return HealthGrants(
            exportAllowed = required.isNotEmpty() && missingRequired.isEmpty(),
            missingRequired = missingRequired,
            missingOptional = optional - have,
        )
    }
}

/**
 * The activity-side implementation: checks `HealthConnectClient.getSdkStatus`, reads the granted set through
 * the permission controller and launches the exporter's permission contract via [launch] for [request] (all
 * write permissions, the optional route included); [granted] is evaluated against [required] only.
 */
class HealthConnectPermissionHost(
    context: Context,
    private val scope: CoroutineScope,
    private val request: Set<String>,
    private val required: Set<String> = HealthConnectExporter.REQUIRED_PERMISSIONS,
    private val launch: (Set<String>) -> Unit,
) : HealthPermissionHost {
    private val app: Context = context.applicationContext
    private val optional: Set<String> = request - required
    private val _sdkStatus = MutableStateFlow(HealthConnectClient.SDK_UNAVAILABLE)
    private val _granted = MutableStateFlow(false)
    private val _optionalMissing = MutableStateFlow(optional)
    override val sdkStatus: StateFlow<Int> = _sdkStatus.asStateFlow()
    override val granted: StateFlow<Boolean> = _granted.asStateFlow()
    override val optionalMissing: StateFlow<Set<String>> = _optionalMissing.asStateFlow()

    override fun request() {
        refreshSdkStatus()
        if (_sdkStatus.value != HealthConnectClient.SDK_AVAILABLE) return
        try {
            launch(request)
        } catch (e: Exception) {
            Log.w(TAG, "permission request failed", e)
        }
    }

    override fun refresh() {
        refreshSdkStatus()
        if (_sdkStatus.value != HealthConnectClient.SDK_AVAILABLE) {
            apply(emptySet())
            return
        }
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(app)
                apply(client.permissionController.getGrantedPermissions())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "getGrantedPermissions failed", e)
                apply(emptySet())
            }
        }
    }

    /** From the activity result of the permission contract. */
    fun onResult(grantedNow: Set<String>) {
        apply(grantedNow)
        refresh()
    }

    private fun apply(have: Set<String>) {
        val grants = HealthPermissionGate.evaluate(have, required, optional)
        _granted.value = grants.exportAllowed
        _optionalMissing.value = grants.missingOptional
        Log.i(TAG, "Health Connect grants: export ${if (grants.exportAllowed) "allowed" else "blocked, missing ${grants.missingRequired}"}" +
            (if (grants.missingOptional.isEmpty()) "" else ", optional missing ${grants.missingOptional}"))
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
