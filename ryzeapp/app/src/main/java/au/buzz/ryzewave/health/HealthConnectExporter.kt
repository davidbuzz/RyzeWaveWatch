package au.buzz.ryzewave.health

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import android.os.RemoteException
import au.buzz.ryzewave.core.HealthRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.StrideModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether Health Connect can be used on this phone (`HealthConnectClient.getSdkStatus`). */
enum class HealthConnectAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

/** Outcome of one export run. */
data class ExportResult(
    val status: Status,
    val counts: ExportCounts = ExportCounts(),
    /** Records actually accepted by Health Connect. */
    val inserted: Int = 0,
    /** Records Health Connect rejected (logged; the export still advances). */
    val failed: Int = 0,
    /** Export cursor after this run (unchanged when nothing was written). */
    val cursor: Long? = null,
    val message: String? = null,
    val time: Long = System.currentTimeMillis(),
) {
    enum class Status { OK, NOTHING_TO_EXPORT, UNAVAILABLE, NO_PERMISSION, ERROR }

    val ok: Boolean get() = status == Status.OK || status == Status.NOTHING_TO_EXPORT
}

/**
 * Writes the watch data in [repo] to Health Connect (docs/APP.md "Health Connect mapping"), idempotently via
 * client record ids (see [HealthConnectMapping]). Availability and permissions follow the flow of
 * `~/MyPulseApp/.../MainActivity.kt`: check `getSdkStatus`, ask `permissionController.getGrantedPermissions()`,
 * otherwise launch [permissionContract] from the UI and export once the result contains [writePermissions].
 * The app only writes, so only the WRITE_* permissions are declared and requested.
 *
 * Typical use from the UI / watch service:
 * ```
 * val launcher = rememberLauncherForActivityResult(exporter.permissionContract()) { granted ->
 *     if (granted.containsAll(exporter.writePermissions)) scope.launch { exporter.exportNew() }
 * }
 * if (exporter.hasPermissions()) exporter.exportNew() else launcher.launch(exporter.writePermissions)
 * ```
 * Exports are serialised; the result of the last run is in [lastResult] for the Settings screen. Cancellation
 * of the calling coroutine propagates (it is never reported as a Health Connect failure).
 */
class HealthConnectExporter(
    context: Context,
    private val repo: HealthRepository,
    settings: SettingsStore,
    stride: StrideModel,
    private val planner: HealthConnectExportPlanner = HealthConnectExportPlanner(repo, settings, stride),
) {
    private val appContext: Context = context.applicationContext
    private val mutex = Mutex()
    private val _lastResult = MutableStateFlow<ExportResult?>(null)
    private val _exporting = MutableStateFlow(false)

    /** Result of the most recent export run, null before the first one. */
    val lastResult: StateFlow<ExportResult?> = _lastResult.asStateFlow()

    /** True while an export is running. */
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** The permissions the UI asks for: the write permission of every record type we export (nothing is read). */
    val requiredPermissions: Set<String> = WRITE_PERMISSIONS

    /** The write permissions, enough to export; the same set as [requiredPermissions]. */
    val writePermissions: Set<String> = WRITE_PERMISSIONS

    // ---- availability

    /** Raw `HealthConnectClient.getSdkStatus(context)`: SDK_AVAILABLE / SDK_UNAVAILABLE / SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun sdkStatus(): Int = try {
        HealthConnectClient.getSdkStatus(appContext)
    } catch (e: Exception) {
        Log.w(TAG, "getSdkStatus failed: $e")
        HealthConnectClient.SDK_UNAVAILABLE
    }

    fun availability(): HealthConnectAvailability = when (sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UPDATE_REQUIRED
        else -> HealthConnectAvailability.NOT_INSTALLED
    }

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    /** Intent that opens the Health Connect settings (permissions, data management); null when unavailable. */
    fun settingsIntent(): Intent? =
        if (isAvailable()) Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS) else null

    /** Play Store page of the Health Connect app, for the "install / update" case. */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(
                "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding",
            )
            setPackage("com.android.vending")
            putExtra("overlay", true)
            putExtra("callerId", appContext.packageName)
        }

    // ---- permissions

    /** The activity-result contract for `registerForActivityResult` / `rememberLauncherForActivityResult`. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** Permissions Health Connect currently grants this app (empty when unavailable). */
    suspend fun grantedPermissions(): Set<String> {
        if (!isAvailable()) return emptySet()
        return try {
            client().permissionController.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getGrantedPermissions failed: $e")
            emptySet()
        }
    }

    /** True when every write permission is granted (the only permissions we need). */
    suspend fun hasPermissions(): Boolean = grantedPermissions().containsAll(WRITE_PERMISSIONS)

    /** True when at least the write permissions are granted, i.e. an export can run. */
    suspend fun canExport(): Boolean = grantedPermissions().containsAll(WRITE_PERMISSIONS)

    // ---- export

    /** The stored export cursor (`hc-export`), null before the first successful export. */
    suspend fun lastExportCursor(): Long? = repo.lastSyncTime(HealthConnectMapping.CURSOR_KIND)

    /** Everything in the repository, regardless of the cursor. */
    suspend fun exportAll(): ExportResult = exportSince(0L)

    /** What is new since the stored cursor (the normal call after a watch sync or from "export now"). */
    suspend fun exportNew(): ExportResult = exportSince(lastExportCursor() ?: 0L)

    /**
     * Writes records for data at or after `cursor - lookback` (see [HealthConnectExportPlanner]) and stores the
     * new cursor with `repo.setLastSyncTime("hc-export", …)`. Never throws: problems come back in the result.
     */
    suspend fun exportSince(cursor: Long): ExportResult = mutex.withLock {
        _exporting.value = true
        try {
            val result = runExport(cursor)
            _lastResult.value = result
            result
        } finally {
            _exporting.value = false
        }
    }

    private suspend fun runExport(cursor: Long): ExportResult {
        when (availability()) {
            HealthConnectAvailability.NOT_INSTALLED ->
                return ExportResult(ExportResult.Status.UNAVAILABLE, message = "Health Connect is not available on this phone")
            HealthConnectAvailability.UPDATE_REQUIRED ->
                return ExportResult(ExportResult.Status.UNAVAILABLE, message = "Health Connect needs to be updated")
            HealthConnectAvailability.AVAILABLE -> Unit
        }

        val client = try {
            client()
        } catch (e: Exception) {
            Log.w(TAG, "HealthConnectClient.getOrCreate failed", e)
            return ExportResult(ExportResult.Status.UNAVAILABLE, message = "Health Connect client error: ${e.message}")
        }

        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getGrantedPermissions failed", e)
            return ExportResult(ExportResult.Status.ERROR, message = "Could not read Health Connect permissions: ${e.message}")
        }
        if (!granted.containsAll(WRITE_PERMISSIONS)) {
            val missing = WRITE_PERMISSIONS - granted
            return ExportResult(ExportResult.Status.NO_PERMISSION, message = "Missing Health Connect permissions: ${missing.joinToString()}")
        }

        val plan = try {
            planner.plan(cursor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "planning export failed", e)
            return ExportResult(ExportResult.Status.ERROR, message = "Could not read local data: ${e.message}")
        }
        if (plan.records.isEmpty()) {
            return ExportResult(ExportResult.Status.NOTHING_TO_EXPORT, counts = plan.counts, cursor = cursor.takeIf { it > 0 })
        }

        var inserted = 0
        var failed = 0
        var lastError: Exception? = null
        for (chunk in plan.records.chunked(CHUNK_SIZE)) {
            coroutineContext.ensureActive()
            try {
                client.insertRecords(chunk)
                inserted += chunk.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.w(TAG, "Health Connect permission lost during export", e)
                return ExportResult(
                    ExportResult.Status.NO_PERMISSION, counts = plan.counts, inserted = inserted, failed = failed,
                    message = "Health Connect permission revoked: ${e.message}",
                )
            } catch (e: IllegalArgumentException) {
                // One or more records in the chunk are invalid: find them one by one, keep the rest.
                Log.w(TAG, "insertRecords(${chunk.size}) rejected, retrying one by one", e)
                lastError = e
                for (record in chunk) {
                    coroutineContext.ensureActive()
                    try {
                        client.insertRecords(listOf(record))
                        inserted++
                    } catch (single: CancellationException) {
                        throw single
                    } catch (single: SecurityException) {
                        return ExportResult(
                            ExportResult.Status.NO_PERMISSION, counts = plan.counts, inserted = inserted, failed = failed,
                            message = "Health Connect permission revoked: ${single.message}",
                        )
                    } catch (single: IllegalArgumentException) {
                        failed++
                        lastError = single
                        Log.w(TAG, "rejected ${record.javaClass.simpleName} ${record.metadata.clientRecordId}: $single")
                    } catch (single: Exception) {
                        // Rate limit / sync in progress / IPC trouble: stop here, keep the cursor for a later retry.
                        Log.w(TAG, "Health Connect stopped accepting records (${single.javaClass.simpleName}): ${single.message}")
                        return transientFailure(plan.counts, inserted, failed, single)
                    }
                }
            } catch (e: Exception) {
                // IllegalStateException (ERROR_RATE_LIMIT_EXCEEDED / DATA_SYNC_IN_PROGRESS), RemoteException, IOException:
                // retrying record by record would only burn more quota. Stop, do not advance the cursor.
                val kind = when (e) {
                    is IllegalStateException -> "busy or rate-limited"
                    is RemoteException, is IOException -> "unreachable"
                    else -> e.javaClass.simpleName
                }
                Log.w(TAG, "insertRecords(${chunk.size}) failed: Health Connect $kind", e)
                return transientFailure(plan.counts, inserted, failed, e)
            }
        }

        if (inserted == 0) {
            return ExportResult(
                ExportResult.Status.ERROR, counts = plan.counts, inserted = 0, failed = failed,
                message = "Health Connect rejected the export: ${lastError?.message}",
            )
        }

        val newCursor = plan.newCursor
        if (newCursor != null) {
            try {
                repo.setLastSyncTime(HealthConnectMapping.CURSOR_KIND, newCursor)
            } catch (e: Exception) {
                Log.w(TAG, "storing export cursor failed", e)
            }
        }
        Log.i(TAG, "exported $inserted records (${plan.counts}) from ${plan.from}, cursor -> $newCursor, failed $failed")
        return ExportResult(
            ExportResult.Status.OK, counts = plan.counts, inserted = inserted, failed = failed, cursor = newCursor,
            message = if (failed > 0) "$failed records rejected: ${lastError?.message}" else null,
        )
    }

    /** A run that stopped before the plan was written: nothing is stored, the next run re-exports from the old cursor. */
    private fun transientFailure(counts: ExportCounts, inserted: Int, failed: Int, e: Exception): ExportResult =
        ExportResult(
            ExportResult.Status.ERROR, counts = counts, inserted = inserted, failed = failed,
            message = "Health Connect did not accept the export (${e.message ?: e.javaClass.simpleName}); will retry after the next sync",
        )

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(appContext)

    companion object {
        private const val TAG = "HealthConnectExporter"

        /** The Health Connect provider app (same package the manifest `<queries>` names). */
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

        /** Records per `insertRecords` call; keeps each IPC parcel small even with hour-long HR series. */
        private const val CHUNK_SIZE = 200

        private val RECORD_TYPES = listOf(
            StepsRecord::class,
            HeartRateRecord::class,
            OxygenSaturationRecord::class,
            DistanceRecord::class,
            SleepSessionRecord::class,
            ExerciseSessionRecord::class,
        )

        /** Must match the `android.permission.health.WRITE_*` entries in AndroidManifest.xml. */
        val WRITE_PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getWritePermission(it) }.toSet()

        /** Convenience for callers holding a permission result: enough was granted to export. */
        fun granted(result: Set<String>): Boolean = result.containsAll(WRITE_PERMISSIONS)
    }
}
