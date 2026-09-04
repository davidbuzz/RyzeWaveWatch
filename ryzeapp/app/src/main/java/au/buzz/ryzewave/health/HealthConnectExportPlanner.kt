package au.buzz.ryzewave.health

import androidx.health.connect.client.records.Record
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideModel
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
 * The changed rows are used for change detection only. Every aggregate record — the [androidx.health.connect.client.records.HeartRateRecord]
 * per hour, the [androidx.health.connect.client.records.DistanceRecord] per day and the
 * [androidx.health.connect.client.records.SleepSessionRecord] per night — is rebuilt from a *complete* re-read of
 * its hour / day / night, because a record written under the same client id (with a higher
 * `clientRecordVersion`, see `HealthConnectMapping.metadata`) replaces the one in Health Connect: building it
 * from the changed subset alone would silently drop the samples that had not changed.
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
        val records: List<Record>,
        val counts: ExportCounts,
        /** Cursor to store after a successful export (phone clock at the start of planning); null when nothing to export. */
        val newCursor: Long?,
        /** Change-detection timestamp the plan started from (after the lookback), for logging. */
        val from: Long,
    )

    /** Everything changed since `cursor - lookback`; `cursor <= 0` means the whole history. */
    suspend fun plan(cursor: Long): Plan {
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

        // Re-read the affected aggregates completely (a full export already holds everything).
        val hours = if (from == 0L) changedHours else completeDays(changedHours, zone)
        val hr = if (from == 0L) changedHr else completeHours(changedHr)
        val sleep = if (from == 0L) changedSleep else completeNights(changedSleep, zone)

        val stepsRecords = HealthConnectMapping.stepsRecords(changedHours, now, zone)   // per hour: only what changed
        val hrRecords = HealthConnectMapping.heartRateRecords(hr, now, zone)
        val spo2Records = HealthConnectMapping.spo2Records(spo2, now, zone)
        val dayDistance = HealthConnectMapping.dailyDistanceRecords(hours, profile, strideSettings, stride, now, zone)
        val workoutDistance = HealthConnectMapping.workoutDistanceRecords(workouts, now, zone)
        val sleepRecords = HealthConnectMapping.sleepSessionRecords(sleep, now, zone)
        val exerciseRecords = HealthConnectMapping.exerciseSessionRecords(workouts, now, zone)

        val records = ArrayList<Record>(
            stepsRecords.size + hrRecords.size + spo2Records.size + dayDistance.size +
                workoutDistance.size + sleepRecords.size + exerciseRecords.size,
        )
        records += stepsRecords
        records += hrRecords
        records += spo2Records
        records += dayDistance
        records += workoutDistance
        records += sleepRecords
        records += exerciseRecords

        val counts = ExportCounts(
            steps = stepsRecords.size,
            heartRate = hrRecords.size,
            spo2 = spo2Records.size,
            distance = dayDistance.size + workoutDistance.size,
            sleep = sleepRecords.size,
            workouts = exerciseRecords.size,
        )

        val newCursor = if (records.isEmpty()) null else max(cursor, now)
        return Plan(records, counts, newCursor, from)
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
