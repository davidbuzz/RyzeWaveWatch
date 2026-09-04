package au.buzz.ryzewave.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
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
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectExportPlannerTest {
    private val zone: ZoneId = ZoneId.of("Australia/Brisbane")
    private val hour = HealthConnectMapping.HOUR_MS
    private val minute = HealthConnectMapping.MINUTE_MS

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /**
     * In-memory repository with the real `xxxSince` contract: rows carry an `updatedAt` phone-clock stamp and
     * `xxxSince(t)` returns the rows changed at or after `t` (not the rows whose sample time is after `t`).
     */
    private class FakeRepo : HealthRepository {
        val steps = ArrayList<Pair<StepsHour, Long>>()
        val hr = ArrayList<Pair<HrSample, Long>>()
        val spo2 = ArrayList<Pair<Spo2Sample, Long>>()
        val sleep = ArrayList<Pair<SleepStage, Long>>()
        val workouts = ArrayList<Pair<Workout, Long>>()
        val cursors = HashMap<String, Long>()
        val asked = HashMap<String, Long>()
        var writeClock = 0L

        fun steps(h: StepsHour, updatedAt: Long = writeClock) { steps += h to updatedAt }
        fun hr(s: HrSample, updatedAt: Long = writeClock) { hr += s to updatedAt }
        fun spo2(s: Spo2Sample, updatedAt: Long = writeClock) { spo2 += s to updatedAt }
        fun sleep(s: SleepStage, updatedAt: Long = writeClock) { sleep += s to updatedAt }
        fun workout(w: Workout, updatedAt: Long = writeClock) { workouts += w to updatedAt }

        override suspend fun upsertSteps(hours: List<StepsHour>) { for (h in hours) steps(h) }
        override suspend fun upsertHr(samples: List<HrSample>) { for (s in samples) hr(s) }
        override suspend fun upsertSpo2(samples: List<Spo2Sample>) { for (s in samples) spo2(s) }
        override suspend fun upsertSleep(stages: List<SleepStage>) { for (s in stages) sleep(s) }
        override suspend fun insertWorkout(workout: Workout): Long { workout(workout); return workout.id }
        override suspend fun updateWorkout(workout: Workout) {}
        override suspend fun insertTrackPoints(points: List<TrackPoint>) {}
        override fun dailySummary(dayStart: Long): Flow<DailySummary> = flowOf()
        override fun stepsForDay(dayStart: Long): Flow<List<StepsHour>> {
            val end = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(dayStart), zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return flowOf(steps.map { it.first }.filter { it.hourStart >= dayStart && it.hourStart < end }.sortedBy { it.hourStart })
        }
        override fun hrBetween(from: Long, to: Long): Flow<List<HrSample>> =
            flowOf(hr.map { it.first }.filter { it.time >= from && it.time < to }.sortedBy { it.time })
        override fun spo2Between(from: Long, to: Long): Flow<List<Spo2Sample>> = flowOf(emptyList())
        override fun sleepForNight(dayStart: Long): Flow<List<SleepStage>> {
            val day = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(dayStart), zone).toLocalDate()
            val from = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val to = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            return flowOf(sleep.map { it.first }.filter { it.start >= from && it.start < to }.sortedBy { it.start })
        }
        override fun workouts(): Flow<List<Workout>> = flowOf(workouts.map { it.first })
        override fun workout(id: Long): Flow<Workout?> = flowOf(workouts.map { it.first }.firstOrNull { it.id == id })
        override fun trackPoints(workoutId: Long): Flow<List<TrackPoint>> = flowOf(emptyList())
        override suspend fun dailySummaries(days: Int): List<DailySummary> = emptyList()
        override suspend fun lastSyncTime(kind: String): Long? = cursors[kind]
        override suspend fun setLastSyncTime(kind: String, time: Long) { cursors[kind] = time }
        override suspend fun hrSince(time: Long): List<HrSample> { asked["hr"] = time; return hr.filter { it.second >= time }.map { it.first }.sortedBy { it.time } }
        override suspend fun spo2Since(time: Long): List<Spo2Sample> { asked["spo2"] = time; return spo2.filter { it.second >= time }.map { it.first }.sortedBy { it.time } }
        override suspend fun stepsSince(time: Long): List<StepsHour> { asked["steps"] = time; return steps.filter { it.second >= time }.map { it.first }.sortedBy { it.hourStart } }
        override suspend fun sleepSince(time: Long): List<SleepStage> { asked["sleep"] = time; return sleep.filter { it.second >= time }.map { it.first }.sortedBy { it.start } }
        override suspend fun workoutsSince(time: Long): List<Workout> { asked["workouts"] = time; return workouts.filter { it.second >= time && it.first.end != null }.map { it.first }.sortedBy { it.start } }

        companion object {
            val zone: ZoneId = ZoneId.of("Australia/Brisbane")
        }
    }

    private class FakeSettings(
        val profileValue: UserProfile = UserProfile(),
        val strideValue: StrideSettings = StrideSettings(),
    ) : SettingsStore {
        override val watchMac: Flow<String?> = flowOf(null)
        override val profile: Flow<UserProfile> = flowOf(profileValue)
        override val sampling: Flow<SamplingSettings> = flowOf(SamplingSettings())
        override val stride: Flow<StrideSettings> = flowOf(strideValue)
        override val healthConnectEnabled: Flow<Boolean> = flowOf(true)
        override suspend fun setWatchMac(mac: String?) {}
        override suspend fun setProfile(p: UserProfile) {}
        override suspend fun setSampling(s: SamplingSettings) {}
        override suspend fun setStride(s: StrideSettings) {}
        override suspend fun setHealthConnectEnabled(on: Boolean) {}
    }

    private val strideModel = object : StrideModel {
        override fun walkStrideM(profile: UserProfile, stride: StrideSettings) = stride.walkStrideM ?: 0.7
        override fun runStrideM(profile: UserProfile, stride: StrideSettings) = stride.runStrideM ?: 1.0
    }

    private fun planner(repo: FakeRepo, now: Long, lookback: Long = HealthConnectExportPlanner.DEFAULT_LOOKBACK_MS) =
        HealthConnectExportPlanner(repo, FakeSettings(), strideModel, lookback, { zone }, { now })

    /** Everything written at phone time 09:30 on 2026-09-04. */
    private fun sampleRepo(): FakeRepo = FakeRepo().apply {
        writeClock = at(2026, 9, 4, 9, 30)
        steps(StepsHour(at(2026, 9, 3, 8), 500, 450, 50))
        steps(StepsHour(at(2026, 9, 4, 9), 300, 300, 0))
        hr(HrSample(at(2026, 9, 3, 8, 10), 70))
        hr(HrSample(at(2026, 9, 4, 9, 10), 75))
        spo2(Spo2Sample(at(2026, 9, 4, 9, 20), 97))
        sleep(SleepStage(at(2026, 9, 3, 23), 2, 60))
        sleep(SleepStage(at(2026, 9, 4, 0), 1, 60))
        workout(Workout(id = 1, start = at(2026, 9, 4, 7), end = at(2026, 9, 4, 7, 30), sportType = 1, distanceMeters = 2500.0, durationSeconds = 1800, avgHr = 120, maxHr = 150, calories = 200))
    }

    @Test
    fun fullExportCoversEverythingFromZero() = runBlocking {
        val repo = sampleRepo()
        val now = at(2026, 9, 4, 10)
        val plan = planner(repo, now).plan(0L)

        assertEquals(0L, repo.asked["steps"])
        assertEquals(0L, repo.asked["hr"])
        assertEquals(0L, repo.asked["sleep"])
        assertEquals(0L, repo.asked["workouts"])
        assertEquals(ExportCounts(steps = 2, heartRate = 2, spo2 = 1, distance = 3, sleep = 1, workouts = 1), plan.counts)
        assertEquals(plan.counts.total, plan.records.size)
        assertEquals(2, plan.records.count { it is StepsRecord })
        assertEquals(2, plan.records.count { it is HeartRateRecord })
        assertEquals(1, plan.records.count { it is OxygenSaturationRecord })
        assertEquals(3, plan.records.count { it is DistanceRecord })
        assertEquals(1, plan.records.count { it is SleepSessionRecord })
        assertEquals(1, plan.records.count { it is ExerciseSessionRecord })
        // the cursor is the phone clock at the start of planning
        assertEquals(now, plan.newCursor)
        // every record has a client id
        assertTrue(plan.records.all { !it.metadata.clientRecordId.isNullOrEmpty() })
        assertEquals(plan.records.size, plan.records.map { it.metadata.clientRecordId }.toSet().size)
    }

    @Test
    fun incrementalExportUsesChangeStampsAndLooksBackALittle() = runBlocking {
        val repo = sampleRepo()
        val now = at(2026, 9, 4, 10)
        val cursor = at(2026, 9, 4, 9, 40)                 // after the 09:30 writes: nothing changed since
        val plan = planner(repo, now, lookback = 5 * minute).plan(cursor)
        val from = cursor - 5 * minute                       // 09:35
        assertEquals(from, plan.from)
        assertEquals(from, repo.asked["hr"])
        assertEquals(from, repo.asked["spo2"])
        assertEquals(from, repo.asked["steps"])
        assertEquals(from, repo.asked["sleep"])
        assertEquals(from, repo.asked["workouts"])
        assertTrue(plan.records.isEmpty())
        assertNull(plan.newCursor)
    }

    @Test
    fun changedHourIsRebuiltFromEverySampleOfThatHour() = runBlocking {
        // 14:00 hour: three samples synced at 14:30, three more synced at 15:00
        val repo = FakeRepo()
        for (m in listOf(0, 10, 20)) repo.hr(HrSample(at(2026, 9, 4, 14, m), 70 + m), updatedAt = at(2026, 9, 4, 14, 30))
        for (m in listOf(30, 40, 50)) repo.hr(HrSample(at(2026, 9, 4, 14, m), 70 + m), updatedAt = at(2026, 9, 4, 15, 0))
        val now = at(2026, 9, 4, 15, 5)
        val plan = planner(repo, now, lookback = 0L).plan(at(2026, 9, 4, 14, 45))   // only the 15:00 batch changed
        val rec = plan.records.filterIsInstance<HeartRateRecord>().single()
        assertEquals(6, rec.samples.size)                                          // not just the three changed ones
        assertEquals(HealthConnectMapping.heartRateId(at(2026, 9, 4, 14)), rec.metadata.clientRecordId)
        assertEquals(now, plan.newCursor)
    }

    @Test
    fun changedHourRebuildsTheWholeDayDistance() = runBlocking {
        val repo = FakeRepo()
        for (h in 8..22) repo.steps(StepsHour(at(2026, 9, 3, h), 100, 100, 0), updatedAt = at(2026, 9, 3, h, 30))
        // the 23:00 row of day D-1 was finalised at 00:05 on D
        repo.steps(StepsHour(at(2026, 9, 3, 23), 50, 50, 0), updatedAt = at(2026, 9, 4, 0, 5))
        val now = at(2026, 9, 4, 1)
        val plan = planner(repo, now, lookback = 0L).plan(at(2026, 9, 4, 0, 0))
        val dist = plan.records.filterIsInstance<DistanceRecord>().single()
        assertEquals((15 * 100 + 50) * 0.7, dist.distance.inMeters, 1e-6)          // every hour of the day, not one
        assertEquals(1, plan.records.count { it is StepsRecord })                   // but only the changed hour's steps
    }

    @Test
    fun changedStageRebuildsTheWholeNight() = runBlocking {
        val repo = FakeRepo()
        repo.sleep(SleepStage(at(2026, 9, 3, 23), 2, 60), updatedAt = at(2026, 9, 4, 6))
        repo.sleep(SleepStage(at(2026, 9, 4, 0), 1, 60), updatedAt = at(2026, 9, 4, 6))
        repo.sleep(SleepStage(at(2026, 9, 4, 1), 3, 30), updatedAt = at(2026, 9, 4, 7))
        val now = at(2026, 9, 4, 8)
        val plan = planner(repo, now, lookback = 0L).plan(at(2026, 9, 4, 6, 30))    // only the 01:00 stage changed
        val night = plan.records.filterIsInstance<SleepSessionRecord>().single()
        assertEquals(3, night.stages.size)
        assertEquals(at(2026, 9, 3, 23), night.startTime.toEpochMilli())
    }

    @Test
    fun cursorNeverMovesBackwards() = runBlocking {
        val repo = sampleRepo()
        val now = at(2026, 9, 4, 10)
        val cursor = at(2026, 9, 4, 9, 25)                    // before the writes: everything is new
        val plan = planner(repo, now, lookback = 0L).plan(cursor)
        assertTrue(plan.records.isNotEmpty())
        assertTrue(plan.newCursor!! >= cursor)
        assertEquals(now, plan.newCursor)
    }

    @Test
    fun nothingToExportGivesNoCursor() = runBlocking {
        val repo = FakeRepo()
        val plan = planner(repo, at(2026, 9, 4, 10)).plan(0L)
        assertTrue(plan.records.isEmpty())
        assertTrue(plan.counts.isEmpty)
        assertNull(plan.newCursor)
    }

    @Test
    fun futureDataIsIgnored() = runBlocking {
        val now = at(2026, 9, 4, 10)
        val repo = FakeRepo().apply {
            steps(StepsHour(at(2026, 9, 4, 9), 100, 100, 0))
            hr(HrSample(now + 5 * minute, 80))        // watch clock ahead
        }
        val plan = planner(repo, now).plan(0L)
        assertEquals(ExportCounts(steps = 1, distance = 1), plan.counts)
        assertEquals(now, plan.newCursor)
    }

    @Test
    fun unfinishedWorkoutIsNotExportedYet() = runBlocking {
        val now = at(2026, 9, 4, 10)
        val repo = FakeRepo().apply {
            workout(Workout(id = 3, start = at(2026, 9, 4, 9, 40), end = null, sportType = 1, distanceMeters = 100.0, durationSeconds = 60, avgHr = null, maxHr = null, calories = 5))
        }
        val plan = planner(repo, now).plan(0L)
        assertTrue(plan.records.isEmpty())
        assertNull(plan.newCursor)
    }

    @Test
    fun cursorKindMatchesRepositoryContract() {
        assertEquals("hc-export", HealthConnectMapping.CURSOR_KIND)
    }
}
