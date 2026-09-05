package au.buzz.ryzewave.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideModel
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.Workout
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.reflect.KClass

/**
 * In-memory [HealthRepository] with the real `xxxSince` contract: rows carry an `updatedAt` phone-clock stamp
 * and `xxxSince(t)` returns the rows changed at or after `t` (not the rows whose sample time is after `t`).
 * The export ledger is a map, like the `hc_export` table.
 */
class FakeHealthRepo(val zone: ZoneId = ZoneId.of("Australia/Brisbane")) : HealthRepository {
    val steps = ArrayList<Pair<StepsHour, Long>>()
    val hr = ArrayList<Pair<HrSample, Long>>()
    val spo2 = ArrayList<Pair<Spo2Sample, Long>>()
    val sleep = ArrayList<Pair<SleepStage, Long>>()
    val workouts = ArrayList<Pair<Workout, Long>>()
    val tracks = HashMap<Long, List<TrackPoint>>()
    val cursors = HashMap<String, Long>()
    val asked = HashMap<String, Long>()
    val trackReads = ArrayList<Long>()
    val ledger = LinkedHashMap<String, Long>()
    val ledgerTimes = HashMap<String, Long>()
    var ledgerClears = 0
    var writeClock = 0L

    fun steps(h: StepsHour, updatedAt: Long = writeClock) { steps += h to updatedAt }
    fun hr(s: HrSample, updatedAt: Long = writeClock) { hr += s to updatedAt }
    fun spo2(s: Spo2Sample, updatedAt: Long = writeClock) { spo2 += s to updatedAt }
    fun sleep(s: SleepStage, updatedAt: Long = writeClock) { sleep += s to updatedAt }
    fun workout(w: Workout, updatedAt: Long = writeClock) { workouts += w to updatedAt }

    /** Replaces the row with the same key (as a Room upsert would) and stamps it [updatedAt]. */
    fun replaceSteps(h: StepsHour, updatedAt: Long) { steps.removeAll { it.first.hourStart == h.hourStart }; steps += h to updatedAt }
    fun replaceWorkout(w: Workout, updatedAt: Long) { workouts.removeAll { it.first.id == w.id }; workouts += w to updatedAt }

    override suspend fun upsertSteps(hours: List<StepsHour>) { for (h in hours) steps(h) }
    override suspend fun upsertHr(samples: List<HrSample>) { for (s in samples) hr(s) }
    override suspend fun upsertSpo2(samples: List<Spo2Sample>) { for (s in samples) spo2(s) }
    override suspend fun upsertSleep(stages: List<SleepStage>) { for (s in stages) sleep(s) }
    override suspend fun insertWorkout(workout: Workout): Long { workout(workout); return workout.id }
    override suspend fun updateWorkout(workout: Workout) { replaceWorkout(workout, writeClock) }
    override suspend fun insertTrackPoints(points: List<TrackPoint>) {}
    override fun dailySummary(dayStart: Long): Flow<DailySummary> = flowOf()
    override fun stepsForDay(dayStart: Long): Flow<List<StepsHour>> {
        val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(dayStart), zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return flowOf(steps.map { it.first }.filter { it.hourStart >= dayStart && it.hourStart < end }.sortedBy { it.hourStart })
    }
    override fun hrBetween(from: Long, to: Long): Flow<List<HrSample>> =
        flowOf(hr.map { it.first }.filter { it.time >= from && it.time < to }.sortedBy { it.time })
    override fun spo2Between(from: Long, to: Long): Flow<List<Spo2Sample>> = flowOf(emptyList())
    override fun sleepForNight(dayStart: Long): Flow<List<SleepStage>> {
        val day = LocalDateTime.ofInstant(Instant.ofEpochMilli(dayStart), zone).toLocalDate()
        val from = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val to = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return flowOf(sleep.map { it.first }.filter { it.start >= from && it.start < to }.sortedBy { it.start })
    }
    override fun workouts(): Flow<List<Workout>> = flowOf(workouts.map { it.first })
    override fun workout(id: Long): Flow<Workout?> = flowOf(workouts.map { it.first }.firstOrNull { it.id == id })
    override fun trackPoints(workoutId: Long): Flow<List<TrackPoint>> {
        trackReads += workoutId
        return flowOf(tracks[workoutId] ?: emptyList())
    }
    override suspend fun dailySummaries(days: Int): List<DailySummary> = emptyList()
    override suspend fun lastSyncTime(kind: String): Long? = cursors[kind]
    override suspend fun setLastSyncTime(kind: String, time: Long) { cursors[kind] = time }
    override suspend fun hrSince(time: Long): List<HrSample> { asked["hr"] = time; return hr.filter { it.second >= time }.map { it.first }.sortedBy { it.time } }
    override suspend fun spo2Since(time: Long): List<Spo2Sample> { asked["spo2"] = time; return spo2.filter { it.second >= time }.map { it.first }.sortedBy { it.time } }
    override suspend fun stepsSince(time: Long): List<StepsHour> { asked["steps"] = time; return steps.filter { it.second >= time }.map { it.first }.sortedBy { it.hourStart } }
    override suspend fun sleepSince(time: Long): List<SleepStage> { asked["sleep"] = time; return sleep.filter { it.second >= time }.map { it.first }.sortedBy { it.start } }
    override suspend fun workoutsSince(time: Long): List<Workout> { asked["workouts"] = time; return workouts.filter { it.second >= time && it.first.end != null }.map { it.first }.sortedBy { it.start } }

    override suspend fun exportedFingerprints(ids: Collection<String>): Map<String, Long> =
        ids.mapNotNull { id -> ledger[id]?.let { id to it } }.toMap()
    override suspend fun markExported(fingerprints: Map<String, Long>, time: Long) {
        ledger += fingerprints
        for (id in fingerprints.keys) ledgerTimes[id] = time
    }
    override suspend fun clearExported() { ledger.clear(); ledgerTimes.clear(); ledgerClears++ }
}

/** Settings whose profile / stride can be changed between exports. */
class FakeSettings(
    profileValue: UserProfile = UserProfile(),
    strideValue: StrideSettings = StrideSettings(),
) : SettingsStore {
    val profileFlow = MutableStateFlow(profileValue)
    val strideFlow = MutableStateFlow(strideValue)
    override val watchMac: Flow<String?> = flowOf(null)
    override val profile: Flow<UserProfile> get() = profileFlow
    override val sampling: Flow<SamplingSettings> = flowOf(SamplingSettings())
    override val stride: Flow<StrideSettings> get() = strideFlow
    override val healthConnectEnabled: Flow<Boolean> = flowOf(true)
    override suspend fun setWatchMac(mac: String?) {}
    override suspend fun setProfile(p: UserProfile) { profileFlow.value = p }
    override suspend fun setSampling(s: SamplingSettings) {}
    override suspend fun setStride(s: StrideSettings) { strideFlow.value = s }
    override suspend fun setHealthConnectEnabled(on: Boolean) {}
}

/** Fixed strides unless calibrated: 0.7 m walking, 1.0 m running. */
val fixedStrideModel: StrideModel = object : StrideModel {
    override fun walkStrideM(profile: UserProfile, stride: StrideSettings) = stride.walkStrideM ?: 0.7
    override fun runStrideM(profile: UserProfile, stride: StrideSettings) = stride.runStrideM ?: 1.0
}

/**
 * A Health Connect client that behaves like the real one for what the exporter does: `insertRecords` upserts by
 * `clientRecordId` and only replaces a stored record when the new `clientRecordVersion` is higher (an equal or
 * lower version is silently ignored, as Health Connect does); records without a client id would duplicate.
 * [reject] names client ids that fail with IllegalArgumentException; [failWith] makes every insert throw.
 */
class FakeHealthConnectClient(granted: Set<String> = HealthConnectExporter.WRITE_PERMISSIONS) : HealthConnectClient {
    val records = LinkedHashMap<String, Record>()
    val versions = LinkedHashMap<String, Long>()
    val inserts = ArrayList<List<Record>>()
    var duplicates = 0
    var ignoredStale = 0
    val reject = HashSet<String>()
    var failWith: Exception? = null
    var grantedPermissions: Set<String> = granted

    override val permissionController: PermissionController = object : PermissionController {
        override suspend fun getGrantedPermissions(): Set<String> = grantedPermissions
        override suspend fun revokeAllPermissions() { grantedPermissions = emptySet() }
    }

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
        failWith?.let { throw it }
        inserts += records
        if (records.any { it.metadata.clientRecordId in reject }) throw IllegalArgumentException("rejected: ${records.filter { it.metadata.clientRecordId in reject }.map { it.metadata.clientRecordId }}")
        val ids = ArrayList<String>(records.size)
        for (r in records) {
            val id = r.metadata.clientRecordId
            if (id == null) { duplicates++; ids += "uuid-${this.records.size + duplicates}"; continue }
            val stored = versions[id]
            if (stored != null && r.metadata.clientRecordVersion <= stored) { ignoredStale++; ids += "uuid-$id"; continue }
            this.records[id] = r
            versions[id] = r.metadata.clientRecordVersion
            ids += "uuid-$id"
        }
        return InsertRecordsResponse(ids)
    }

    override suspend fun updateRecords(records: List<Record>) = throw UnsupportedOperationException()
    override suspend fun deleteRecords(recordType: KClass<out Record>, recordIdsList: List<String>, clientRecordIdsList: List<String>) =
        throw UnsupportedOperationException()
    override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) = throw UnsupportedOperationException()
    override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> = throw UnsupportedOperationException()
    override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> = throw UnsupportedOperationException()
    override suspend fun aggregate(request: AggregateRequest): AggregationResult = throw UnsupportedOperationException()
    override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> =
        throw UnsupportedOperationException()
    override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> =
        throw UnsupportedOperationException()
    override suspend fun getChangesToken(request: ChangesTokenRequest): String = throw UnsupportedOperationException()
    override suspend fun getChanges(changesToken: String): ChangesResponse = throw UnsupportedOperationException()
}

/** A backend whose SDK status and client are what the test says. */
class FakeBackend(
    val client: FakeHealthConnectClient = FakeHealthConnectClient(),
    var status: Int = HealthConnectClient.SDK_AVAILABLE,
) : HealthConnectBackend {
    override fun sdkStatus(): Int = status
    override fun client(): HealthConnectClient = client
    override val packageName: String = "au.buzz.ryzewave"
}
