package au.buzz.ryzewave.core

import kotlinx.coroutines.flow.Flow

/**
 * Local store for everything we get from the watch and the phone. Implemented by `data.RoomHealthRepository`
 * and exposed through `App.graph.repo`. Inserts are idempotent (same time + source = same row).
 */
interface HealthRepository {
    // ---- writes from the watch / workout tracker
    suspend fun upsertSteps(hours: List<StepsHour>)
    suspend fun upsertHr(samples: List<HrSample>)
    suspend fun upsertSpo2(samples: List<Spo2Sample>)
    suspend fun upsertSleep(stages: List<SleepStage>)
    suspend fun insertWorkout(workout: Workout): Long
    suspend fun updateWorkout(workout: Workout)
    suspend fun insertTrackPoints(points: List<TrackPoint>)

    // ---- reads for the UI (Flows update live)
    fun dailySummary(dayStart: Long): Flow<DailySummary>
    fun stepsForDay(dayStart: Long): Flow<List<StepsHour>>
    fun hrBetween(from: Long, to: Long): Flow<List<HrSample>>
    fun spo2Between(from: Long, to: Long): Flow<List<Spo2Sample>>
    fun sleepForNight(dayStart: Long): Flow<List<SleepStage>>
    fun workouts(): Flow<List<Workout>>
    fun workout(id: Long): Flow<Workout?>
    fun trackPoints(workoutId: Long): Flow<List<TrackPoint>>
    suspend fun dailySummaries(days: Int): List<DailySummary>

    // ---- sync bookkeeping / export cursor
    suspend fun lastSyncTime(kind: String): Long?
    suspend fun setLastSyncTime(kind: String, time: Long)
    suspend fun hrSince(time: Long): List<HrSample>
    suspend fun spo2Since(time: Long): List<Spo2Sample>
    suspend fun stepsSince(time: Long): List<StepsHour>
    suspend fun sleepSince(time: Long): List<SleepStage>
    suspend fun workoutsSince(time: Long): List<Workout>
}

/** Small typed settings store (DataStore). Implemented in `data.SettingsStore`, exposed as `App.graph.settings`. */
interface SettingsStore {
    val watchMac: Flow<String?>
    val profile: Flow<UserProfile>
    val sampling: Flow<SamplingSettings>
    val stride: Flow<StrideSettings>
    val healthConnectEnabled: Flow<Boolean>
    suspend fun setWatchMac(mac: String?)
    suspend fun setProfile(p: UserProfile)
    suspend fun setSampling(s: SamplingSettings)
    suspend fun setStride(s: StrideSettings)
    suspend fun setHealthConnectEnabled(on: Boolean)
}
