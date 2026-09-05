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
import androidx.health.connect.client.records.Record
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
    /** Candidate records not written because they are identical to their last export (the ledger). */
    val skipped: Int = 0,
) {
    enum class Status { OK, NOTHING_TO_EXPORT, UNAVAILABLE, NO_PERMISSION, ERROR }

    val ok: Boolean get() = status == Status.OK || status == Status.NOTHING_TO_EXPORT
}

/**
 * The two Android entry points of the Health Connect SDK the exporter depends on, behind an interface so the
 * export loop can run against a fake client in unit tests. The production implementation wraps a [Context].
 */
interface HealthConnectBackend {
    /** `HealthConnectClient.getSdkStatus(context)`: SDK_AVAILABLE / SDK_UNAVAILABLE / SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun sdkStatus(): Int

    /** `HealthConnectClient.getOrCreate(context)`; may throw when the provider is missing. */
    fun client(): HealthConnectClient

    /** This app's package name (for the Play Store install intent). */
    val packageName: String
}

private class AndroidHealthConnectBackend(context: Context) : HealthConnectBackend {
    private val appContext: Context = context.applicationContext
    override fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(appContext)
    override fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(appContext)
    override val packageName: String get() = appContext.packageName
}

/**
 * Writes the watch data in [repo] to Health Connect (docs/APP.md "Health Connect mapping"), idempotently via
 * client record ids (see [HealthConnectMapping]). Availability and permissions follow the flow of
 * `~/MyPulseApp/.../MainActivity.kt`: check `getSdkStatus`, ask `permissionController.getGrantedPermissions()`,
 * otherwise launch [permissionContract] from the UI and export once the result contains [writePermissions].
 * The app only writes, so only the WRITE_* permissions are declared and requested. `WRITE_EXERCISE_ROUTE` is
 * requested with the rest but is not required for an export: without it the exercise sessions go out without
 * their GPS route (the permission is a separate, user-revocable grant in Health Connect).
 *
 * What gets written is decided by [HealthConnectExportPlanner]; the exporter keeps its ledger up to date: the
 * fingerprint of every record Health Connect accepted is stored (chunk by chunk, so a run that stops half-way
 * does not re-send what already went out) and the planner's markers once the run has succeeded, so the next
 * run only sends what really changed.
 *
 * Typical use from the UI / watch service (request [writePermissions], gate on [requiredPermissions]):
 * ```
 * val launcher = rememberLauncherForActivityResult(exporter.permissionContract()) { granted ->
 *     if (HealthConnectExporter.granted(granted)) scope.launch { exporter.exportNew() }
 * }
 * if (exporter.canExport()) exporter.exportNew() else launcher.launch(exporter.writePermissions)
 * ```
 * Exports are serialised; the result of the last run is in [lastResult] for the Settings screen. Cancellation
 * of the calling coroutine propagates (it is never reported as a Health Connect failure).
 */
class HealthConnectExporter internal constructor(
    private val backend: HealthConnectBackend,
    private val repo: HealthRepository,
    private val planner: HealthConnectExportPlanner,
) {
    constructor(
        context: Context,
        repo: HealthRepository,
        settings: SettingsStore,
        stride: StrideModel,
        planner: HealthConnectExportPlanner = HealthConnectExportPlanner(repo, settings, stride),
    ) : this(AndroidHealthConnectBackend(context), repo, planner)

    private val mutex = Mutex()
    private val _lastResult = MutableStateFlow<ExportResult?>(null)
    private val _exporting = MutableStateFlow(false)

    /** Result of the most recent export run, null before the first one. */
    val lastResult: StateFlow<ExportResult?> = _lastResult.asStateFlow()

    /** True while an export is running. */
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /**
     * The permissions an export cannot run without ([REQUIRED_PERMISSIONS]: one write permission per record type);
     * what the Settings gate ("Permissions granted", "Export now") must be computed against. Nothing is read.
     */
    val requiredPermissions: Set<String> = REQUIRED_PERMISSIONS

    /** Everything the UI *requests* ([WRITE_PERMISSIONS]): [requiredPermissions] plus the optional exercise route. */
    val writePermissions: Set<String> = WRITE_PERMISSIONS

    /** Requested but not needed for an export ([ROUTE_PERMISSION]): without it sessions go out without their GPS route. */
    val optionalPermissions: Set<String> = OPTIONAL_PERMISSIONS

    // ---- availability

    /** Raw `HealthConnectClient.getSdkStatus(context)`: SDK_AVAILABLE / SDK_UNAVAILABLE / SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun sdkStatus(): Int = try {
        backend.sdkStatus()
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
            putExtra("callerId", backend.packageName)
        }

    // ---- permissions

    /** The activity-result contract for `registerForActivityResult` / `rememberLauncherForActivityResult`. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** Permissions Health Connect currently grants this app (empty when unavailable). */
    suspend fun grantedPermissions(): Set<String> {
        if (!isAvailable()) return emptySet()
        return try {
            backend.client().permissionController.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getGrantedPermissions failed: $e")
            emptySet()
        }
    }

    /** True when every write permission is granted, the exercise route included. */
    suspend fun hasPermissions(): Boolean = grantedPermissions().containsAll(WRITE_PERMISSIONS)

    /** True when the record write permissions are granted, i.e. an export can run (routes need [ROUTE_PERMISSION] too). */
    suspend fun canExport(): Boolean = grantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    // ---- export

    /** The stored export cursor (`hc-export`), null before the first successful export. */
    suspend fun lastExportCursor(): Long? = repo.lastSyncTime(HealthConnectMapping.CURSOR_KIND)

    /**
     * Everything in the repository, regardless of the cursor — still only what differs from its last export.
     * With [force] the ledger is cleared first, so every record is written again (after the app's data was
     * deleted in Health Connect, for instance).
     */
    suspend fun exportAll(force: Boolean = false): ExportResult = exportSince(0L, force)

    /** What is new since the stored cursor (the normal call after a watch sync or from "export now"). */
    suspend fun exportNew(): ExportResult = exportSince(lastExportCursor() ?: 0L)

    /**
     * Writes records for data at or after `cursor - lookback` (see [HealthConnectExportPlanner]) and stores the
     * new cursor with `repo.setLastSyncTime("hc-export", …)`. Never throws: problems come back in the result.
     */
    suspend fun exportSince(cursor: Long, force: Boolean = false): ExportResult = mutex.withLock {
        _exporting.value = true
        try {
            val result = runExport(cursor, force)
            _lastResult.value = result
            result
        } finally {
            _exporting.value = false
        }
    }

    private suspend fun runExport(cursor: Long, force: Boolean): ExportResult {
        when (availability()) {
            HealthConnectAvailability.NOT_INSTALLED ->
                return ExportResult(ExportResult.Status.UNAVAILABLE, message = "Health Connect is not available on this phone")
            HealthConnectAvailability.UPDATE_REQUIRED ->
                return ExportResult(ExportResult.Status.UNAVAILABLE, message = "Health Connect needs to be updated")
            HealthConnectAvailability.AVAILABLE -> Unit
        }

        val client = try {
            backend.client()
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
        if (!granted.containsAll(REQUIRED_PERMISSIONS)) {
            val missing = REQUIRED_PERMISSIONS - granted
            return ExportResult(ExportResult.Status.NO_PERMISSION, message = "Missing Health Connect permissions: ${missing.joinToString()}")
        }
        val withRoutes = ROUTE_PERMISSION in granted

        if (force) {
            try {
                repo.clearExported()
                Log.i(TAG, "forced export: ledger cleared, every record will be written again")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "clearing the export ledger failed", e)
                return ExportResult(ExportResult.Status.ERROR, message = "Could not reset the export ledger: ${e.message}")
            }
        }

        val plan = try {
            planner.plan(cursor, withRoutes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "planning export failed", e)
            return ExportResult(ExportResult.Status.ERROR, message = "Could not read local data: ${e.message}")
        }
        if (plan.records.isEmpty()) {
            remember(plan.markers, plan.now)
            Log.i(
                TAG,
                "nothing to export: ${plan.unchanged} candidate records unchanged since their last export (from ${plan.from}" +
                    ", ${plan.sessionsSkipped} sessions unchanged without reading their tracks)",
            )
            return ExportResult(
                ExportResult.Status.NOTHING_TO_EXPORT, counts = plan.counts, cursor = cursor.takeIf { it > 0 }, skipped = plan.unchanged,
            )
        }

        var inserted = 0
        var failed = 0
        var lastError: Exception? = null
        for (chunk in plan.records.chunked(CHUNK_SIZE)) {
            coroutineContext.ensureActive()
            try {
                client.insertRecords(chunk)
                inserted += chunk.size
                remember(plan, chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.w(TAG, "Health Connect permission lost during export", e)
                return ExportResult(
                    ExportResult.Status.NO_PERMISSION, counts = plan.counts, inserted = inserted, failed = failed,
                    message = "Health Connect permission revoked: ${e.message}", skipped = plan.unchanged,
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
                        remember(plan, listOf(record))
                    } catch (single: CancellationException) {
                        throw single
                    } catch (single: SecurityException) {
                        return ExportResult(
                            ExportResult.Status.NO_PERMISSION, counts = plan.counts, inserted = inserted, failed = failed,
                            message = "Health Connect permission revoked: ${single.message}", skipped = plan.unchanged,
                        )
                    } catch (single: IllegalArgumentException) {
                        failed++
                        lastError = single
                        Log.w(TAG, "rejected ${record.javaClass.simpleName} ${record.metadata.clientRecordId}: $single")
                    } catch (single: Exception) {
                        // Rate limit / sync in progress / IPC trouble: stop here, keep the cursor for a later retry.
                        Log.w(TAG, "Health Connect stopped accepting records (${single.javaClass.simpleName}): ${single.message}")
                        return transientFailure(plan, inserted, failed, single)
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
                return transientFailure(plan, inserted, failed, e)
            }
        }

        if (inserted == 0) {
            return ExportResult(
                ExportResult.Status.ERROR, counts = plan.counts, inserted = 0, failed = failed,
                message = "Health Connect rejected the export: ${lastError?.message}", skipped = plan.unchanged,
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
        remember(plan.markers, plan.now)
        for ((id, n) in plan.routes) Log.i(TAG, "exercise route attached to $id: $n locations")
        if (!withRoutes && plan.counts.workouts > 0) {
            Log.i(TAG, "WRITE_EXERCISE_ROUTE not granted: ${plan.counts.workouts} exercise sessions exported without a route")
        }
        Log.i(
            TAG,
            "exported $inserted records (${plan.counts}) from ${plan.from}, ${plan.unchanged} unchanged skipped " +
                "(${plan.sessionsSkipped} sessions without reading their tracks), cursor -> $newCursor, failed $failed",
        )
        return ExportResult(
            ExportResult.Status.OK, counts = plan.counts, inserted = inserted, failed = failed, cursor = newCursor,
            message = if (failed > 0) "$failed records rejected: ${lastError?.message}" else null,
            skipped = plan.unchanged,
        )
    }

    /**
     * Ledger: the fingerprints of [records] (all accepted by Health Connect) under their client ids, plus the
     * planner's companion entries of those records (a session's route-less fingerprint and route state, which
     * let the next run skip the track read — see [HealthConnectExportPlanner.Plan.companions]).
     */
    private suspend fun remember(plan: HealthConnectExportPlanner.Plan, records: List<Record>) {
        val entries = LinkedHashMap<String, Long>(records.size * 4)
        for (r in records) {
            val id = r.metadata.clientRecordId ?: continue
            entries[id] = plan.fingerprints[id] ?: continue
            plan.companions[id]?.let { entries += it }
        }
        remember(entries, plan.now)
    }

    /** A ledger write must never fail an export that Health Connect accepted: the worst case is one extra re-send. */
    private suspend fun remember(entries: Map<String, Long>, time: Long) {
        if (entries.isEmpty()) return
        try {
            repo.markExported(entries, time)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "storing the export ledger failed (${entries.size} entries)", e)
        }
    }

    /** A run that stopped before the plan was written: the cursor stays, the next run re-plans from the old one. */
    private fun transientFailure(plan: HealthConnectExportPlanner.Plan, inserted: Int, failed: Int, e: Exception): ExportResult =
        ExportResult(
            ExportResult.Status.ERROR, counts = plan.counts, inserted = inserted, failed = failed,
            message = "Health Connect did not accept the export (${e.message ?: e.javaClass.simpleName}); will retry after the next sync",
            skipped = plan.unchanged,
        )

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

        /** Lets an [ExerciseSessionRecord] carry its GPS track as an `ExerciseRoute`; optional for the export. */
        const val ROUTE_PERMISSION: String = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE

        /** The permissions an export cannot run without: one write permission per record type. */
        val REQUIRED_PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getWritePermission(it) }.toSet()

        /** Everything the UI requests; must match the `android.permission.health.WRITE_*` entries in AndroidManifest.xml. */
        val WRITE_PERMISSIONS: Set<String> = REQUIRED_PERMISSIONS + ROUTE_PERMISSION

        /** Requested but not required: [WRITE_PERMISSIONS] minus [REQUIRED_PERMISSIONS] (the exercise route). */
        val OPTIONAL_PERMISSIONS: Set<String> = WRITE_PERMISSIONS - REQUIRED_PERMISSIONS

        /** Convenience for callers holding a permission result (or a granted set): enough was granted to export. */
        fun granted(result: Set<String>): Boolean = result.containsAll(REQUIRED_PERMISSIONS)

        /** The exercise-route permission is in [result]: exported sessions carry their GPS track. */
        fun routeGranted(result: Set<String>): Boolean = ROUTE_PERMISSION in result
    }
}
