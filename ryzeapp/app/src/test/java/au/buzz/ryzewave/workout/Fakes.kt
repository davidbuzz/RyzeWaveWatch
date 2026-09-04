package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.SyncResult
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WatchStatus
import au.buzz.ryzewave.core.Workout
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.fail

/** Polls [condition] every 10 ms until it holds or [timeoutMs] passes (then fails the test). */
suspend fun awaitUntil(what: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
        delay(10L)
    }
}

/** In-memory [HealthRepository]; only the workout-related members do anything. */
class FakeRepo : HealthRepository {
    private val lock = Any()
    private val inserted = ArrayList<Workout>()
    private val updated = ArrayList<Workout>()
    private val trackPoints = ArrayList<TrackPoint>()
    private val hrSamples = ArrayList<HrSample>()
    private var nextId = 1L

    @Volatile var failInsertPoints = false

    fun inserted(): List<Workout> = synchronized(lock) { ArrayList(inserted) }
    fun updates(): List<Workout> = synchronized(lock) { ArrayList(updated) }
    fun points(): List<TrackPoint> = synchronized(lock) { ArrayList(trackPoints) }
    fun hr(): List<HrSample> = synchronized(lock) { ArrayList(hrSamples) }

    /** The row as it stands now: the last update, or the insert when never updated. */
    private fun rows(): List<Workout> = synchronized(lock) {
        inserted.map { row -> updated.lastOrNull { it.id == row.id } ?: row }
    }

    override suspend fun upsertSteps(hours: List<StepsHour>) {}
    override suspend fun upsertHr(samples: List<HrSample>) {
        synchronized(lock) { hrSamples += samples }
    }
    override suspend fun upsertSpo2(samples: List<Spo2Sample>) {}
    override suspend fun upsertSleep(stages: List<SleepStage>) {}

    override suspend fun insertWorkout(workout: Workout): Long = synchronized(lock) {
        val id = nextId++
        inserted += workout.copy(id = id)
        id
    }

    override suspend fun updateWorkout(workout: Workout) {
        synchronized(lock) { updated += workout }
    }

    override suspend fun insertTrackPoints(points: List<TrackPoint>) {
        if (failInsertPoints) throw IllegalStateException("database closed")
        synchronized(lock) { trackPoints += points }
    }

    override fun dailySummary(dayStart: Long): Flow<DailySummary> = emptyFlow()
    override fun stepsForDay(dayStart: Long): Flow<List<StepsHour>> = flowOf(emptyList())
    override fun hrBetween(from: Long, to: Long): Flow<List<HrSample>> =
        flowOf(hr().filter { it.time in from..to }.sortedBy { it.time })
    override fun spo2Between(from: Long, to: Long): Flow<List<Spo2Sample>> = flowOf(emptyList())
    override fun sleepForNight(dayStart: Long): Flow<List<SleepStage>> = flowOf(emptyList())
    override fun workouts(): Flow<List<Workout>> = flowOf(rows())
    override fun workout(id: Long): Flow<Workout?> = flowOf(rows().firstOrNull { it.id == id })
    override fun trackPoints(workoutId: Long): Flow<List<TrackPoint>> =
        flowOf(points().filter { it.workoutId == workoutId }.sortedBy { it.time })
    override suspend fun dailySummaries(days: Int): List<DailySummary> = emptyList()

    override suspend fun lastSyncTime(kind: String): Long? = null
    override suspend fun setLastSyncTime(kind: String, time: Long) {}
    override suspend fun hrSince(time: Long): List<HrSample> = hr().filter { it.time >= time }
    override suspend fun spo2Since(time: Long): List<Spo2Sample> = emptyList()
    override suspend fun stepsSince(time: Long): List<StepsHour> = emptyList()
    override suspend fun sleepSince(time: Long): List<SleepStage> = emptyList()
    override suspend fun workoutsSince(time: Long): List<Workout> = rows().filter { it.start >= time }
}

/** Records the workout commands; [hr] lets a test push live samples. */
class FakeWatch : WatchApi {
    data class Update(val duration: Int, val distance: Double, val pace: Double, val calories: Int)

    val hr = MutableSharedFlow<HrSample>()
    val calls = CopyOnWriteArrayList<String>()
    val updates = CopyOnWriteArrayList<Update>()

    @Volatile var failStart = false

    override val status: StateFlow<WatchStatus> = MutableStateFlow(WatchStatus())
    override val liveHr: SharedFlow<HrSample> = hr
    override val events: SharedFlow<WatchEvent> = MutableSharedFlow()

    override suspend fun connect(mac: String) {}
    override suspend fun disconnect() {}
    override suspend fun applySettings(profile: UserProfile, sampling: SamplingSettings) {}
    override suspend fun syncAll(): SyncResult = SyncResult(0, 0, 0, 0)
    override suspend fun startLiveHr() {}
    override suspend fun stopLiveHr() {}
    override suspend fun spo2SpotTest(): Int? = null

    override suspend fun startWorkout(sportType: Int) {
        calls += "start:$sportType"
        if (failStart) throw IllegalStateException("watch offline")
    }

    override suspend fun updateWorkout(durationSeconds: Int, distanceMeters: Double, paceSecPerKm: Double, calories: Int) {
        updates += Update(durationSeconds, distanceMeters, paceSecPerKm, calories)
    }

    override suspend fun pauseWorkout() {
        calls += "pause"
    }

    override suspend fun resumeWorkout() {
        calls += "resume"
    }

    override suspend fun stopWorkout() {
        calls += "stop"
    }

    override suspend fun findWatch() {}
    override suspend fun readBattery(): Int? = null
}

class FakeSettings(profileValue: UserProfile = UserProfile()) : SettingsStore {
    override val watchMac: Flow<String?> = flowOf(null)
    override val profile: Flow<UserProfile> = flowOf(profileValue)
    override val sampling: Flow<SamplingSettings> = flowOf(SamplingSettings())
    override val stride: Flow<StrideSettings> = flowOf(StrideSettings())
    override val healthConnectEnabled: Flow<Boolean> = flowOf(false)
    override suspend fun setWatchMac(mac: String?) {}
    override suspend fun setProfile(p: UserProfile) {}
    override suspend fun setSampling(s: SamplingSettings) {}
    override suspend fun setStride(s: StrideSettings) {}
    override suspend fun setHealthConnectEnabled(on: Boolean) {}
}
