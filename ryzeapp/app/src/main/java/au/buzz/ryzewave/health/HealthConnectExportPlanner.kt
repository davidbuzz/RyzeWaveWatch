package au.buzz.ryzewave.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideModel
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlin.math.max

/** How many records of each kind an export produced (or would produce). */
data class ExportCounts(
    val steps: Int = 0,
    val heartRate: Int = 0,
    val spo2: Int = 0,
    val distance: Int = 0,
    val sleep: Int = 0,
    val workouts: Int = 0,
    /** GPS locations attached to the exercise sessions as routes (inside the workout records, not extra records). */
    val routePoints: Int = 0,
) {
    val total: Int get() = steps + heartRate + spo2 + distance + sleep + workouts
    val isEmpty: Boolean get() = total == 0
}

/**
 * Decides what to write to Health Connect for a given export cursor, without touching Health Connect itself
 * (so it is unit-testable with fake repository / settings). [HealthConnectExporter] runs the plan.
 *
 * Cursor semantics follow the repository contract (`data.RoomHealthRepository`): `xxxSince(t)` returns the rows
 * *inserted or changed on the phone clock* at or after `t`, so the cursor is a phone-clock timestamp taken
 * before the queries and stored after a successful export; the small [lookbackMs] only covers clock jitter.
 *
 * The changed rows are used for change detection only. Every aggregate record — the [HeartRateRecord] per hour,
 * the [DistanceRecord] per day and the [SleepSessionRecord] per night — is rebuilt from a *complete* re-read of
 * its hour / day / night, because a record written under the same client id (with a higher
 * `clientRecordVersion`, see `HealthConnectMapping.metadata`) replaces the one in Health Connect: building it
 * from the changed subset alone would silently drop the samples that had not changed.
 *
 * **Ledger.** The rows in the lookback window are candidates on every export, and so is everything on a full
 * export (cursor 0), so candidates alone would re-send a finished workout several times. Every candidate record
 * therefore gets a content [HealthConnectMapping.fingerprint] and is compared with the ledger of what was last
 * written under its client id ([HealthRepository.exportedFingerprints]); only records whose content differs are
 * planned, and the exporter stores the fingerprints of the records it wrote. Two exports with nothing changed in
 * between plan nothing; a changed workout row plans exactly its session and its distance record.
 *
 * **Stride.** The daily [DistanceRecord] is steps × stride, so it changes when the stride settings (or the profile
 * height they derive from) change although no steps row did. The ledger keeps a marker of the effective strides
 * ([HealthConnectMapping.STRIDE_MARKER_ID]); when it differs from the current one every day with steps is rebuilt
 * and the fingerprints decide which days actually moved. The marker goes into [Plan.markers] for the exporter to
 * store once the run has succeeded, records or not. A *missing* marker on an incremental run (cursor > 0) is
 * treated as a change too: it means exports ran before the ledger existed (the v2 → v3 upgrade) or the marker
 * write failed, and the daily distances in Health Connect may carry a stride that has since changed — the
 * rebuild costs one read of `steps_hour` and the fingerprints still decide what is sent.
 *
 * **Sessions.** An [ExerciseSessionRecord] is the workout row plus (with `WRITE_EXERCISE_ROUTE`) its stored GPS
 * track, and the track of a finished workout never changes. With every session the exporter stores two
 * companion entries ([Plan.companions]): the fingerprint of the session *without* its route
 * ([HealthConnectMapping.sessionBaseMarkerId]) and the route state it was written with
 * ([HealthConnectMapping.sessionRouteMarkerId]: locations in the route, or [HealthConnectMapping.ROUTE_NOT_PERMITTED]).
 * A candidate workout whose route-less fingerprint and route state match those entries is unchanged without its
 * track being read at all ([Plan.sessionsSkipped]); otherwise the track is read and the full fingerprint decides.
 */
class HealthConnectExportPlanner(
    private val repo: HealthRepository,
    private val settings: SettingsStore,
    private val stride: StrideModel,
    private val lookbackMs: Long = DEFAULT_LOOKBACK_MS,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class Plan(
        /** The records to write: candidates whose content differs from what the ledger says was last written. */
        val records: List<Record>,
        val counts: ExportCounts,
        /** Cursor to store after a successful export (phone clock at the start of planning); null when nothing to export. */
        val newCursor: Long?,
        /** Change-detection timestamp the plan started from (after the lookback), for logging. */
        val from: Long,
        /** Client id of every exercise session that carries a route -> number of locations in it. */
        val routes: Map<String, Int> = emptyMap(),
        /** Content fingerprint of every planned record by client id; the exporter stores them once written. */
        val fingerprints: Map<String, Long> = emptyMap(),
        /** Planner markers (the stride marker) to store with the ledger once the run has succeeded, records or not. */
        val markers: Map<String, Long> = emptyMap(),
        /** Candidate records left out because they are identical to their last export. */
        val unchanged: Int = 0,
        /** The phone clock the plan was made at (the record versions and the ledger's `exportedAt`). */
        val now: Long = 0L,
        /**
         * Ledger entries to store together with a planned record once Health Connect accepted it, by the record's
         * client id (a session's route-less fingerprint and route state; see the class comment).
         */
        val companions: Map<String, Map<String, Long>> = emptyMap(),
        /** Exercise sessions found unchanged from their companion entries alone, without reading their tracks. */
        val sessionsSkipped: Int = 0,
    )

    /**
     * Everything changed since `cursor - lookback` that differs from its last export; `cursor <= 0` considers the
     * whole history. With [withRoutes] the stored GPS fixes of each exported workout are read and attached to its
     * session as an `ExerciseRoute` (needs `WRITE_EXERCISE_ROUTE`; the exporter passes false when that permission
     * is not granted).
     */
    suspend fun plan(cursor: Long, withRoutes: Boolean = true): Plan {
        val now = clock()                                  // taken before the queries: the next cursor
        val zone = zone()
        val from = if (cursor <= 0L) 0L else max(0L, cursor - lookbackMs)

        val changedHours = repo.stepsSince(from)
        val changedHr = repo.hrSince(from)
        val spo2 = repo.spo2Since(from)
        val changedSleep = repo.sleepSince(from)
        val workouts = repo.workoutsSince(from)
        val profile = settings.profile.first()
        val strideSettings = settings.stride.first()

        // The stride the daily distances were last exported with; a change re-evaluates every day. On an incremental
        // run a missing marker counts as a change as well (exports ran before the ledger existed, or the marker
        // write failed): a full run (from == 0) already rebuilds every day, and the marker is stored after this run.
        val strideFp = HealthConnectMapping.strideFingerprint(profile, strideSettings, stride)
        val strideChanged = from > 0L &&
            repo.exportedFingerprints(listOf(HealthConnectMapping.STRIDE_MARKER_ID))[HealthConnectMapping.STRIDE_MARKER_ID] != strideFp

        // Re-read the affected aggregates completely (a full export already holds everything).
        val hours = when {
            from == 0L -> changedHours
            strideChanged -> mergeByKey(repo.stepsSince(0L) + changedHours) { it.hourStart }
            else -> completeDays(changedHours, zone)
        }
        val hr = if (from == 0L) changedHr else completeHours(changedHr)
        val sleep = if (from == 0L) changedSleep else completeNights(changedSleep, zone)

        val stepsRecords = HealthConnectMapping.stepsRecords(changedHours, now, zone)   // per hour: only what changed
        val hrRecords = HealthConnectMapping.heartRateRecords(hr, now, zone)
        val spo2Records = HealthConnectMapping.spo2Records(spo2, now, zone)
        val dayDistance = HealthConnectMapping.dailyDistanceRecords(hours, profile, strideSettings, stride, now, zone)
        val workoutDistance = HealthConnectMapping.workoutDistanceRecords(workouts, now, zone)
        val sleepRecords = HealthConnectMapping.sleepSessionRecords(sleep, now, zone)

        // Sessions: compare the route-less content and the route state with the companion entries first; only a
        // workout that fails that test gets its track read and its full record built (see the class comment).
        val exportable = HealthConnectMapping.exportableWorkouts(workouts, now)
        val baseFps = HealthConnectMapping.exerciseSessionRecords(exportable, now, zone).associate {
            it.metadata.clientRecordId!! to HealthConnectMapping.fingerprint(it, now)
        }
        val sessionLedger = repo.exportedFingerprints(
            exportable.flatMap { listOf(HealthConnectMapping.sessionBaseMarkerId(it.id), HealthConnectMapping.sessionRouteMarkerId(it.id)) },
        )
        val toBuild = ArrayList<Workout>(exportable.size)
        var sessionsSkipped = 0
        for (w in exportable) {
            val baseFp = baseFps[HealthConnectMapping.workoutId(w.id)]
            val routeState = sessionLedger[HealthConnectMapping.sessionRouteMarkerId(w.id)]
            val writtenWithRoute = routeState != null && routeState != HealthConnectMapping.ROUTE_NOT_PERMITTED
            val writtenWithout = routeState == HealthConnectMapping.ROUTE_NOT_PERMITTED
            val routeSettled = if (withRoutes) writtenWithRoute else writtenWithout
            if (baseFp != null && sessionLedger[HealthConnectMapping.sessionBaseMarkerId(w.id)] == baseFp && routeSettled) sessionsSkipped++
            else toBuild += w
        }
        val tracks: Map<Long, List<TrackPoint>> =
            if (withRoutes) toBuild.associate { it.id to repo.trackPointsOnce(it.id) } else emptyMap()
        val exerciseRecords = HealthConnectMapping.exerciseSessionRecords(toBuild, now, zone, tracks)
        val companions = LinkedHashMap<String, Map<String, Long>>(exerciseRecords.size * 2)
        for (r in exerciseRecords) {
            val id = r.metadata.clientRecordId ?: continue
            val workoutId = toBuild.first { HealthConnectMapping.workoutId(it.id) == id }.id
            companions[id] = mapOf(
                HealthConnectMapping.sessionBaseMarkerId(workoutId) to (baseFps[id] ?: continue),
                HealthConnectMapping.sessionRouteMarkerId(workoutId) to
                    (if (withRoutes) HealthConnectMapping.routePointCount(r).toLong() else HealthConnectMapping.ROUTE_NOT_PERMITTED),
            )
        }

        val candidates = ArrayList<Record>(
            stepsRecords.size + hrRecords.size + spo2Records.size + dayDistance.size +
                workoutDistance.size + sleepRecords.size + exerciseRecords.size,
        )
        candidates += stepsRecords
        candidates += hrRecords
        candidates += spo2Records
        candidates += dayDistance
        candidates += workoutDistance
        candidates += sleepRecords
        candidates += exerciseRecords

        // Ledger: drop every candidate whose content is what Health Connect already holds under its id.
        val candidateFps = LinkedHashMap<String, Long>(candidates.size * 2)
        for (r in candidates) candidateFps[r.metadata.clientRecordId ?: continue] = HealthConnectMapping.fingerprint(r, now)
        val ledger = repo.exportedFingerprints(candidateFps.keys)
        val records = ArrayList<Record>(candidates.size)
        val fingerprints = LinkedHashMap<String, Long>()
        val markers = LinkedHashMap<String, Long>()
        markers[HealthConnectMapping.STRIDE_MARKER_ID] = strideFp
        for (r in candidates) {
            val id = r.metadata.clientRecordId ?: continue
            val fp = candidateFps[id] ?: continue
            if (ledger[id] == fp) {
                // A session whose full record is unchanged but whose companions are missing (written before they
                // existed): store them with the markers so the next run can skip the track read.
                companions[id]?.let { markers += it }
                continue
            }
            records += r
            fingerprints[id] = fp
        }

        val routes = LinkedHashMap<String, Int>()
        for (r in records) {
            if (r !is ExerciseSessionRecord) continue
            val n = HealthConnectMapping.routePointCount(r)
            if (n > 0) routes[r.metadata.clientRecordId ?: continue] = n
        }

        val counts = ExportCounts(
            steps = records.count { it is StepsRecord },
            heartRate = records.count { it is HeartRateRecord },
            spo2 = records.count { it is OxygenSaturationRecord },
            distance = records.count { it is DistanceRecord },
            sleep = records.count { it is SleepSessionRecord },
            workouts = records.count { it is ExerciseSessionRecord },
            routePoints = routes.values.sum(),
        )

        val newCursor = if (records.isEmpty()) null else max(cursor, now)
        return Plan(
            records = records,
            counts = counts,
            newCursor = newCursor,
            from = from,
            routes = routes,
            fingerprints = fingerprints,
            markers = markers,
            unchanged = candidates.size - records.size + sessionsSkipped,
            now = now,
            companions = companions.filterKeys { it in fingerprints },
            sessionsSkipped = sessionsSkipped,
        )
    }

    /** Every hour row of each local day that has a changed hour, so the day's distance record is complete. */
    private suspend fun completeDays(changed: List<StepsHour>, zone: ZoneId): List<StepsHour> {
        val days = changed.map { HealthConnectMapping.startOfDay(it.hourStart, zone) }.toSortedSet()
        val out = ArrayList<StepsHour>()
        for (dayStart in days) out += repo.stepsForDay(dayStart).first()
        return mergeByKey(out + changed) { it.hourStart }
    }

    /** Every sample of each (epoch) hour bin that has a changed sample, so the hour's HR record is complete. */
    private suspend fun completeHours(changed: List<HrSample>): List<HrSample> {
        val bins = changed.map { HealthConnectMapping.hourBin(it.time) }.toSortedSet()
        val out = ArrayList<HrSample>()
        for (bin in bins) out += repo.hrBetween(bin, bin + HealthConnectMapping.HOUR_MS).first()
        return mergeByKey(out + changed) { it.time to it.source }
    }

    /** Every stage of each night that has a changed stage (noon-to-noon windows), so the night is not split. */
    private suspend fun completeNights(changed: List<SleepStage>, zone: ZoneId): List<SleepStage> {
        val mornings: Set<LocalDate> = changed.map { HealthConnectMapping.sleepMorning(it.start, zone) }.toSortedSet()
        val out = ArrayList<SleepStage>()
        for (morning in mornings) out += repo.sleepForNight(HealthConnectMapping.startOfDay(morning, zone)).first()
        return mergeByKey(out + changed) { it.start }
    }

    /** Last row per key wins (the changed rows are appended last, so they override a stale flow snapshot). */
    private fun <T, K> mergeByKey(rows: List<T>, key: (T) -> K): List<T> {
        val map = LinkedHashMap<K, T>()
        for (r in rows) map[key(r)] = r
        return map.values.toList()
    }

    companion object {
        /** Re-check window on top of the stored phone-clock cursor: covers clock jitter between sync and export. */
        const val DEFAULT_LOOKBACK_MS = 5L * 60L * 1000L
    }
}
