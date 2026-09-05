package au.buzz.ryzewave.data

import android.content.Context
import androidx.room.withTransaction
import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideModel
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.Workout
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * [HealthRepository] on top of [Db].
 *
 * Writes are idempotent: a row is keyed by its time (+ source), and a batch that re-delivers rows the database
 * already holds with the same values is skipped, so the `updatedAt` stamp of a row only moves when the row is
 * new or its values changed. The watch re-sends its whole history on every sync; without this every sync would
 * look like fresh data to the exporter.
 *
 * `xxxSince(time)` (the export cursor group of the contract) therefore means "rows inserted or changed at or
 * after [time] on the phone clock", ordered by sample time. Take the cursor timestamp *before* querying and
 * store it with [setLastSyncTime] after a successful export; anything written meanwhile is picked up next time.
 * [workoutsSince] only returns finished workouts (end != null).
 *
 * Daily figures are SQL aggregates over the local calendar day; the daily distance is
 * `stride.stepsToMeters(walk, run, profile, strideSettings)` with the profile and stride settings observed live
 * from [settings], so the dashboard updates when either changes.
 *
 * @param zone calendar zone for day boundaries; null = the device zone at the time of each call.
 * @param clock source of `updatedAt` stamps (phone epoch millis); injectable for tests.
 */
class RoomHealthRepository(
    private val db: Db,
    private val stride: StrideModel,
    private val settings: SettingsStore,
    private val zone: ZoneId? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : HealthRepository {

    constructor(context: Context, stride: StrideModel, settings: SettingsStore) :
        this(Db.get(context), stride, settings)

    private fun zone(): ZoneId = zone ?: ZoneId.systemDefault()

    // ---- writes

    override suspend fun upsertSteps(hours: List<StepsHour>) {
        if (hours.isEmpty()) return
        val now = clock()
        db.withTransaction {
            val existing = db.steps()
                .rangeOnce(hours.minOf { it.hourStart }, hours.maxOf { it.hourStart })
                .map(StepsHourEntity::toModel)
            val changed = newOrChanged(hours, existing) { it.hourStart }
            if (changed.isNotEmpty()) db.steps().upsert(changed.map { it.toEntity(now) })
        }
    }

    override suspend fun upsertHr(samples: List<HrSample>) {
        if (samples.isEmpty()) return
        val now = clock()
        db.withTransaction {
            val existing = db.hr()
                .rangeOnce(samples.minOf { it.time }, samples.maxOf { it.time })
                .map(HrSampleEntity::toModel)
            val changed = newOrChanged(samples, existing) { it.time to it.source }
            if (changed.isNotEmpty()) db.hr().upsert(changed.map { it.toEntity(now) })
        }
    }

    override suspend fun upsertSpo2(samples: List<Spo2Sample>) {
        if (samples.isEmpty()) return
        val now = clock()
        db.withTransaction {
            val existing = db.spo2()
                .rangeOnce(samples.minOf { it.time }, samples.maxOf { it.time })
                .map(Spo2SampleEntity::toModel)
            val changed = newOrChanged(samples, existing) { it.time to it.source }
            if (changed.isNotEmpty()) db.spo2().upsert(changed.map { it.toEntity(now) })
        }
    }

    /**
     * Incremental watch-sync upsert, keyed by [SleepStage.start] via [newOrChanged].
     *
     * Re-sync double-count caveat: rows written by [replaceSleepForNight]'s gap-fill are [SleepStage.GENERIC_ASLEEP]
     * blocks whose `start` is the start of a gap. If the watch *later* re-stages minutes inside that same gap and
     * they arrive here, the new real stages have different `start` keys, so they are inserted *alongside* the
     * generic fill rather than replacing it — the two overlap in time and the summary counts those minutes twice.
     * Nothing is silently wiped, but on such a re-sync of a previously reconstructed night prefer
     * [replaceSleepForNight] (or de-overlap first) instead of this incremental path.
     */
    override suspend fun upsertSleep(stages: List<SleepStage>) {
        if (stages.isEmpty()) return
        val now = clock()
        db.withTransaction {
            val existing = db.sleep()
                .rangeOnce(stages.minOf { it.start }, stages.maxOf { it.start })
                .map(SleepStageEntity::toModel)
            val changed = newOrChanged(stages, existing) { it.start }
            if (changed.isNotEmpty()) db.sleep().upsert(changed.map { it.toEntity(now) })
        }
    }

    /**
     * Replace the night of [dayStart]: delete every sleep row in `Days.nightWindow(dayStart)` and insert
     * [stages] with `updatedAt = now`. Every inserted row is stamped fresh (the idempotent `newOrChanged`
     * filter is deliberately bypassed) so the Health Connect export cursor re-exports the rewritten night.
     */
    override suspend fun replaceSleepForNight(dayStart: Long, stages: List<SleepStage>) {
        val now = clock()
        val (from, to) = Days.nightWindow(dayStart, zone())
        db.withTransaction {
            db.sleep().deleteRange(from, to)
            if (stages.isNotEmpty()) db.sleep().upsert(stages.map { it.toEntity(now) })
        }
    }

    /** The id is always generated; whatever [Workout.id] the caller passed is ignored. */
    override suspend fun insertWorkout(workout: Workout): Long =
        db.workouts().insert(workout.copy(id = 0).toEntity(clock()))

    override suspend fun updateWorkout(workout: Workout) {
        db.workouts().update(workout.toEntity(clock()))
    }

    override suspend fun insertTrackPoints(points: List<TrackPoint>) {
        if (points.isEmpty()) return
        db.trackPoints().upsert(points.map(TrackPoint::toEntity))
    }

    // ---- reads

    override fun dailySummary(dayStart: Long): Flow<DailySummary> {
        val dayEnd = Days.dayEnd(dayStart, zone())
        val strideInputs = combine(settings.profile, settings.stride) { profile, strideSettings ->
            profile to strideSettings
        }
        return combine(
            db.steps().totals(dayStart, dayEnd),
            db.hr().stats(dayStart, dayEnd),
            db.hr().last(dayStart, dayEnd),
            db.spo2().last(dayStart, dayEnd),
            strideInputs,
        ) { totals, stats, lastHr, lastSpo2, inputs ->
            buildDailySummary(
                dayStart = dayStart,
                totals = totals,
                stats = stats,
                lastHr = lastHr?.toModel(),
                lastSpo2 = lastSpo2?.toModel(),
                profile = inputs.first,
                strideSettings = inputs.second,
                stride = stride,
            )
        }.distinctUntilChanged()
    }

    override fun stepsForDay(dayStart: Long): Flow<List<StepsHour>> =
        db.steps().between(dayStart, Days.dayEnd(dayStart, zone()))
            .map { rows -> rows.map(StepsHourEntity::toModel) }

    override fun hrBetween(from: Long, to: Long): Flow<List<HrSample>> =
        db.hr().between(from, to).map { rows -> rows.map(HrSampleEntity::toModel) }

    override fun spo2Between(from: Long, to: Long): Flow<List<Spo2Sample>> =
        db.spo2().between(from, to).map { rows -> rows.map(Spo2SampleEntity::toModel) }

    /** Stages of the night that ended on the morning of [dayStart] (previous noon .. this noon). */
    override fun sleepForNight(dayStart: Long): Flow<List<SleepStage>> {
        val (from, to) = Days.nightWindow(dayStart, zone())
        return db.sleep().between(from, to).map { rows -> rows.map(SleepStageEntity::toModel) }
    }

    /** Most recent first. */
    override fun workouts(): Flow<List<Workout>> =
        db.workouts().all().map { rows -> rows.map(WorkoutEntity::toModel) }

    override fun workout(id: Long): Flow<Workout?> =
        db.workouts().byId(id).map { row -> row?.toModel() }

    override fun trackPoints(workoutId: Long): Flow<List<TrackPoint>> =
        db.trackPoints().forWorkout(workoutId).map { rows -> rows.map(TrackPointEntity::toModel) }

    override suspend fun trackPointsOnce(workoutId: Long): List<TrackPoint> =
        db.trackPoints().forWorkoutOnce(workoutId).map(TrackPointEntity::toModel)

    /** The last [days] calendar days, oldest first and today last. */
    override suspend fun dailySummaries(days: Int): List<DailySummary> {
        if (days <= 0) return emptyList()
        val zone = zone()
        val profile = settings.profile.first()
        val strideSettings = settings.stride.first()
        return Days.recentDayStarts(days, clock(), zone).map { dayStart ->
            val dayEnd = Days.dayEnd(dayStart, zone)
            buildDailySummary(
                dayStart = dayStart,
                totals = db.steps().totalsOnce(dayStart, dayEnd),
                stats = db.hr().statsOnce(dayStart, dayEnd),
                lastHr = db.hr().lastOnce(dayStart, dayEnd)?.toModel(),
                lastSpo2 = db.spo2().lastOnce(dayStart, dayEnd)?.toModel(),
                profile = profile,
                strideSettings = strideSettings,
                stride = stride,
            )
        }
    }

    // ---- sync bookkeeping / export cursor (see the class KDoc for the `since` semantics)

    override suspend fun lastSyncTime(kind: String): Long? = db.syncCursors().get(kind)

    override suspend fun setLastSyncTime(kind: String, time: Long) {
        db.syncCursors().upsert(SyncCursorEntity(kind, time))
    }

    override suspend fun hrSince(time: Long): List<HrSample> =
        db.hr().changedSince(time).map(HrSampleEntity::toModel)

    override suspend fun spo2Since(time: Long): List<Spo2Sample> =
        db.spo2().changedSince(time).map(Spo2SampleEntity::toModel)

    override suspend fun stepsSince(time: Long): List<StepsHour> =
        db.steps().changedSince(time).map(StepsHourEntity::toModel)

    override suspend fun sleepSince(time: Long): List<SleepStage> =
        db.sleep().changedSince(time).map(SleepStageEntity::toModel)

    override suspend fun workoutsSince(time: Long): List<Workout> =
        db.workouts().finishedChangedSince(time).map(WorkoutEntity::toModel)

    // ---- Health Connect export ledger

    override suspend fun exportedFingerprints(ids: Collection<String>): Map<String, Long> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>(ids.size)
        for (chunk in ids.distinct().chunked(LEDGER_QUERY_CHUNK)) {
            for (row in db.hcExport().byIds(chunk)) out[row.clientRecordId] = row.fingerprint
        }
        return out
    }

    override suspend fun markExported(fingerprints: Map<String, Long>, time: Long) {
        if (fingerprints.isEmpty()) return
        db.hcExport().upsert(fingerprints.map { (id, fp) -> HcExportEntity(id, fp, time) })
    }

    override suspend fun clearExported() {
        db.hcExport().clear()
    }

    private companion object {
        /** Well under SQLite's 999 bound variables per statement. */
        const val LEDGER_QUERY_CHUNK = 500
    }
}

/**
 * The rows of [incoming] that are new or differ from the row [existing] holds under the same [key]. Value
 * equality is the data-class equality of the core model (which has no `updatedAt`), so an unchanged re-delivery
 * is dropped. Duplicated keys inside [incoming] are all kept; the upsert lets the last one win.
 */
internal fun <M, K> newOrChanged(incoming: List<M>, existing: Collection<M>, key: (M) -> K): List<M> {
    if (existing.isEmpty()) return incoming
    val current = existing.associateBy(key)
    return incoming.filter { current[key(it)] != it }
}

/**
 * Pure assembly of a [DailySummary] from the SQL aggregates. Separate from the repository so it can be unit
 * tested without a database. Missing aggregates (empty day) yield zero steps and null HR figures.
 */
internal fun buildDailySummary(
    dayStart: Long,
    totals: StepTotals?,
    stats: HrStats?,
    lastHr: HrSample?,
    lastSpo2: Spo2Sample?,
    profile: UserProfile,
    strideSettings: StrideSettings,
    stride: StrideModel,
): DailySummary {
    val steps = totals?.total ?: 0
    val walk = totals?.walk ?: 0
    val run = totals?.run ?: 0
    return DailySummary(
        dayStart = dayStart,
        steps = steps,
        walkSteps = walk,
        runSteps = run,
        distanceMeters = stride.stepsToMeters(walk, run, profile, strideSettings),
        lastHr = lastHr,
        lastSpo2 = lastSpo2,
        minHr = stats?.minBpm,
        maxHr = stats?.maxBpm,
        avgHr = stats?.avgBpm?.roundToInt(),
    )
}
