package au.buzz.ryzewave.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideModel
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.workout.DefaultStrideModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectMappingTest {
    /** Fixed-offset zone (no DST) so wall-clock arithmetic in the tests is exact. */
    private val zone: ZoneId = ZoneId.of("Australia/Brisbane")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private val hour = HealthConnectMapping.HOUR_MS
    private val minute = HealthConnectMapping.MINUTE_MS

    /** Fixed strides: 0.7 m walking, 1.0 m running. */
    private val stride = object : StrideModel {
        override fun walkStrideM(profile: UserProfile, stride: StrideSettings) = stride.walkStrideM ?: 0.7
        override fun runStrideM(profile: UserProfile, stride: StrideSettings) = stride.runStrideM ?: 1.0
    }

    // ---- stage / type mappings

    @Test
    fun sleepStageCodesMapToHealthConnectTypes() {
        assertEquals(SleepSessionRecord.STAGE_TYPE_DEEP, HealthConnectMapping.sleepStageType(1))
        assertEquals(SleepSessionRecord.STAGE_TYPE_LIGHT, HealthConnectMapping.sleepStageType(2))
        assertEquals(SleepSessionRecord.STAGE_TYPE_REM, HealthConnectMapping.sleepStageType(3))
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE, HealthConnectMapping.sleepStageType(4))
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, HealthConnectMapping.sleepStageType(0))
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, HealthConnectMapping.sleepStageType(9))
    }

    @Test
    fun exerciseTypeFollowsAverageSpeed() {
        val walk = Workout(id = 1, start = 0, end = 1800_000, sportType = 1, distanceMeters = 2000.0, durationSeconds = 1800, avgHr = null, maxHr = null, calories = 0)
        val run = walk.copy(id = 2, distanceMeters = 5000.0)
        val unknown = walk.copy(id = 3, durationSeconds = 0, distanceMeters = 0.0)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, HealthConnectMapping.exerciseType(walk))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, HealthConnectMapping.exerciseType(run))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, HealthConnectMapping.exerciseType(unknown))
        assertEquals("Ryze Wave run", HealthConnectMapping.exerciseTitle(run))
        assertEquals("Ryze Wave walk", HealthConnectMapping.exerciseTitle(walk))
    }

    @Test
    fun metadataCarriesClientIdAndVersion() {
        val m = HealthConnectMapping.metadata("hr-1", 1234L)
        assertEquals("hr-1", m.clientRecordId)
        assertEquals(1234L, m.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, m.recordingMethod)
        assertEquals(HealthConnectMapping.WATCH, m.device)
    }

    /**
     * Health Connect ignores an upsert under an existing client id unless its clientRecordVersion is higher
     * than the stored one, so a re-export of a growing hour / day must carry a higher version: the export's
     * `now`. Two exports 20 minutes apart of the same (growing) hour and day give the same ids with a larger
     * version and the larger count / distance.
     */
    @Test
    fun reExportOfAGrowingHourAndDayCarriesAHigherVersion() {
        val profile = UserProfile(heightCm = 180, weightKg = 80, age = 40, male = true)
        val strideSettings = StrideSettings()
        val stride = DefaultStrideModel()
        val first = at(2026, 9, 4, 7, 5)
        val second = at(2026, 9, 4, 7, 25)
        val early = listOf(StepsHour(at(2026, 9, 4, 7), 40, 40, 0))
        val later = listOf(StepsHour(at(2026, 9, 4, 7), 1900, 1700, 200))

        val s1 = HealthConnectMapping.stepsRecords(early, first, zone).single()
        val s2 = HealthConnectMapping.stepsRecords(later, second, zone).single()
        assertEquals(s1.metadata.clientRecordId, s2.metadata.clientRecordId)
        assertEquals(first, s1.metadata.clientRecordVersion)
        assertEquals(second, s2.metadata.clientRecordVersion)
        assertTrue(s2.metadata.clientRecordVersion > s1.metadata.clientRecordVersion)
        assertEquals(40L, s1.count)
        assertEquals(1900L, s2.count)
        assertEquals(second, s2.endTime.toEpochMilli())

        val d1 = HealthConnectMapping.dailyDistanceRecords(early, profile, strideSettings, stride, first, zone).single()
        val d2 = HealthConnectMapping.dailyDistanceRecords(later, profile, strideSettings, stride, second, zone).single()
        assertEquals(d1.metadata.clientRecordId, d2.metadata.clientRecordId)
        assertTrue(d2.metadata.clientRecordVersion > d1.metadata.clientRecordVersion)
        assertTrue(d2.distance.inMeters > d1.distance.inMeters)

        val hr1 = HealthConnectMapping.heartRateRecords(listOf(HrSample(at(2026, 9, 4, 7, 2), 70)), first, zone).single()
        val hr2 = HealthConnectMapping.heartRateRecords(listOf(HrSample(at(2026, 9, 4, 7, 2), 70), HrSample(at(2026, 9, 4, 7, 20), 90)), second, zone).single()
        assertEquals(hr1.metadata.clientRecordId, hr2.metadata.clientRecordId)
        assertTrue(hr2.metadata.clientRecordVersion > hr1.metadata.clientRecordVersion)
        assertEquals(2, hr2.samples.size)
    }

    // ---- calendar helpers

    @Test
    fun sleepMorningUsesNoonBoundary() {
        assertEquals(LocalDate.of(2026, 9, 4), HealthConnectMapping.sleepMorning(at(2026, 9, 3, 22, 30), zone))
        assertEquals(LocalDate.of(2026, 9, 4), HealthConnectMapping.sleepMorning(at(2026, 9, 4, 7, 15), zone))
        assertEquals(LocalDate.of(2026, 9, 4), HealthConnectMapping.sleepMorning(at(2026, 9, 4, 11, 59), zone))
        assertEquals(LocalDate.of(2026, 9, 5), HealthConnectMapping.sleepMorning(at(2026, 9, 4, 12, 0), zone))
        assertEquals(at(2026, 9, 3, 12), HealthConnectMapping.previousNoon(at(2026, 9, 4, 7), zone))
        assertEquals(at(2026, 9, 4, 12), HealthConnectMapping.previousNoon(at(2026, 9, 4, 12), zone))
        assertEquals(at(2026, 9, 4, 12), HealthConnectMapping.previousNoon(at(2026, 9, 4, 23), zone))
    }

    @Test
    fun dayHelpers() {
        val t = at(2026, 9, 4, 15, 20)
        assertEquals(at(2026, 9, 4, 0), HealthConnectMapping.startOfDay(t, zone))
        assertEquals(at(2026, 9, 5, 0), HealthConnectMapping.endOfDay(t, zone))
        assertEquals(t - 20 * minute, HealthConnectMapping.hourBin(t))
        assertEquals(10 * 3600, HealthConnectMapping.offsetAt(t, zone).totalSeconds)
    }

    // ---- steps

    @Test
    fun stepsRecordPerHourSkipsEmptyAndFutureHoursAndCapsCurrentHour() {
        val now = at(2026, 9, 4, 10, 25)
        val hours = listOf(
            StepsHour(at(2026, 9, 4, 8), 500, 450, 50),
            StepsHour(at(2026, 9, 4, 9), 0, 0, 0),           // empty -> skipped
            StepsHour(at(2026, 9, 4, 10), 120, 120, 0),      // current hour -> ends now
            StepsHour(at(2026, 9, 4, 11), 30, 30, 0),        // future -> skipped
        )
        val records = HealthConnectMapping.stepsRecords(hours, now, zone)
        assertEquals(2, records.size)
        val eight = records[0]
        assertEquals(500L, eight.count)
        assertEquals(at(2026, 9, 4, 8), eight.startTime.toEpochMilli())
        assertEquals(at(2026, 9, 4, 9), eight.endTime.toEpochMilli())
        assertEquals("steps-${at(2026, 9, 4, 8)}", eight.metadata.clientRecordId)
        assertEquals(now, eight.metadata.clientRecordVersion)
        val ten = records[1]
        assertEquals(120L, ten.count)
        assertEquals(now, ten.endTime.toEpochMilli())
    }

    @Test
    fun stepsHourStartingExactlyNowIsSkipped() {
        val now = at(2026, 9, 4, 10)
        val records = HealthConnectMapping.stepsRecords(listOf(StepsHour(now, 10, 10, 0)), now, zone)
        assertTrue(records.isEmpty())
    }

    // ---- heart rate

    @Test
    fun heartRateSamplesGroupedPerHourSortedAndDeduplicated() {
        val now = at(2026, 9, 4, 12)
        val h9 = at(2026, 9, 4, 9)
        val h10 = at(2026, 9, 4, 10)
        val samples = listOf(
            HrSample(h10 + 5 * minute, 80, SampleSource.HISTORY),
            HrSample(h9 + 50 * minute, 70, SampleSource.HISTORY),
            HrSample(h9 + 10 * minute, 65, SampleSource.HISTORY),
            HrSample(h9 + 10 * minute, 66, SampleSource.LIVE),     // same instant -> one sample
            HrSample(h9 + 20 * minute, 0, SampleSource.AUTO),      // invalid -> dropped
            HrSample(h9 + 30 * minute, 400, SampleSource.AUTO),    // invalid -> dropped
            HrSample(now + minute, 90, SampleSource.LIVE),         // future -> dropped
        )
        val records = HealthConnectMapping.heartRateRecords(samples, now, zone)
        assertEquals(2, records.size)
        val nine = records[0]
        assertEquals("hr-$h9", nine.metadata.clientRecordId)
        assertEquals(2, nine.samples.size)
        assertEquals(h9 + 10 * minute, nine.startTime.toEpochMilli())
        assertEquals(h9 + 50 * minute, nine.endTime.toEpochMilli())
        assertEquals(listOf(65L, 70L), nine.samples.map { it.beatsPerMinute })
        val ten = records[1]
        assertEquals("hr-$h10", ten.metadata.clientRecordId)
        assertEquals(1, ten.samples.size)
        assertEquals(ten.startTime, ten.endTime)
    }

    // ---- SpO2

    @Test
    fun spo2RecordPerSampleDropsInvalid() {
        val now = at(2026, 9, 4, 12)
        val t = at(2026, 9, 4, 8, 10)
        val samples = listOf(
            Spo2Sample(t, 97),
            Spo2Sample(t + minute, 0),      // no result
            Spo2Sample(t + 2 * minute, 255), // sentinel
            Spo2Sample(t + 3 * minute, 100),
        )
        val records = HealthConnectMapping.spo2Records(samples, now, zone)
        assertEquals(2, records.size)
        assertEquals(97.0, records[0].percentage.value, 1e-9)
        assertEquals("spo2-$t", records[0].metadata.clientRecordId)
        assertEquals(100.0, records[1].percentage.value, 1e-9)
    }

    // ---- distance

    @Test
    fun dailyDistanceFromStrideModelPerDay() {
        val now = at(2026, 9, 4, 10, 30)
        val hours = listOf(
            StepsHour(at(2026, 9, 3, 8), 1100, 1000, 100),   // 700 + 100 = 800 m
            StepsHour(at(2026, 9, 3, 18), 200, 200, 0),      // + 140 m
            StepsHour(at(2026, 9, 4, 9), 300, 300, 0),       // today: 210 m, ends now
            StepsHour(at(2026, 9, 4, 12), 300, 300, 0),      // future -> ignored
        )
        val records = HealthConnectMapping.dailyDistanceRecords(hours, UserProfile(), StrideSettings(), stride, now, zone)
        assertEquals(2, records.size)
        val yesterday = records[0]
        assertEquals(940.0, yesterday.distance.inMeters, 1e-6)
        assertEquals(at(2026, 9, 3, 0), yesterday.startTime.toEpochMilli())
        assertEquals(at(2026, 9, 4, 0), yesterday.endTime.toEpochMilli())
        assertEquals("dist-${at(2026, 9, 3, 0)}", yesterday.metadata.clientRecordId)
        val today = records[1]
        assertEquals(210.0, today.distance.inMeters, 1e-6)
        assertEquals(now, today.endTime.toEpochMilli())
    }

    @Test
    fun dailyDistanceUsesCalibratedStride() {
        val now = at(2026, 9, 4, 23)
        val hours = listOf(StepsHour(at(2026, 9, 4, 8), 1000, 1000, 0))
        val records = HealthConnectMapping.dailyDistanceRecords(hours, UserProfile(), StrideSettings(walkStrideM = 0.8), stride, now, zone)
        assertEquals(800.0, records.single().distance.inMeters, 1e-6)
    }

    @Test
    fun workoutDistanceAndSessionOnlyForFinishedWorkouts() {
        val now = at(2026, 9, 4, 12)
        val start = at(2026, 9, 4, 7)
        val done = Workout(id = 5, start = start, end = start + 1800_000, sportType = 1, distanceMeters = 4500.0, durationSeconds = 1800, avgHr = 140, maxHr = 165, calories = 320)
        val running = done.copy(id = 6, start = start + hour, end = null)
        val noDistance = done.copy(id = 7, start = start + 2 * hour, end = start + 2 * hour + 600_000, distanceMeters = 0.0, durationSeconds = 600)
        val workouts = listOf(running, noDistance, done)

        val distance = HealthConnectMapping.workoutDistanceRecords(workouts, now, zone)
        assertEquals(1, distance.size)
        assertEquals(4500.0, distance[0].distance.inMeters, 1e-6)
        assertEquals("wdist-5", distance[0].metadata.clientRecordId)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, distance[0].metadata.recordingMethod)

        val sessions = HealthConnectMapping.exerciseSessionRecords(workouts, now, zone)
        assertEquals(listOf("workout-5", "workout-7"), sessions.map { it.metadata.clientRecordId })
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, sessions[0].exerciseType)  // 2.5 m/s
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, sessions[1].exerciseType)
        assertEquals(start, sessions[0].startTime.toEpochMilli())
        assertEquals(start + 1800_000, sessions[0].endTime.toEpochMilli())
        assertEquals("Ryze Wave run", sessions[0].title)
        assertTrue(sessions[0].notes!!.contains("4.50 km"))
        assertTrue(sessions[0].notes!!.contains("avg HR 140"))
    }

    // ---- sleep

    @Test
    fun sleepSessionPerNightAcrossMidnightWithStages() {
        val now = at(2026, 9, 4, 12)
        val stages = listOf(
            SleepStage(at(2026, 9, 3, 22, 30), 2, 30),   // light 22:30-23:00
            SleepStage(at(2026, 9, 3, 23, 0), 1, 60),    // deep 23:00-00:00
            SleepStage(at(2026, 9, 4, 0, 0), 3, 20),     // REM 00:00-00:20
            SleepStage(at(2026, 9, 4, 0, 20), 4, 5),     // awake 00:20-00:25
            SleepStage(at(2026, 9, 4, 0, 25), 2, 395),   // light until 07:00
        )
        val records = HealthConnectMapping.sleepSessionRecords(stages, now, zone)
        assertEquals(1, records.size)
        val night = records[0]
        assertEquals("sleep-2026-09-04", night.metadata.clientRecordId)
        assertEquals(at(2026, 9, 3, 22, 30), night.startTime.toEpochMilli())
        assertEquals(at(2026, 9, 4, 7, 0), night.endTime.toEpochMilli())
        assertEquals(5, night.stages.size)
        assertEquals(
            listOf(
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM,
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_LIGHT,
            ),
            night.stages.map { it.stage },
        )
        assertEquals(at(2026, 9, 4, 0, 0), night.stages[1].endTime.toEpochMilli())
    }

    @Test
    fun sleepStagesAreClampedToNextStageAndNow() {
        val now = at(2026, 9, 4, 6, 30)
        val stages = listOf(
            SleepStage(at(2026, 9, 3, 23, 0), 2, 90),    // claims until 00:30 but next starts 00:00
            SleepStage(at(2026, 9, 4, 0, 0), 1, 0),      // zero minutes -> dropped
            SleepStage(at(2026, 9, 4, 0, 0), 1, 60),     // same start (dedup keeps the first non-empty)
            SleepStage(at(2026, 9, 4, 6, 0), 2, 120),    // runs past now -> cut at now
            SleepStage(at(2026, 9, 4, 7, 0), 2, 30),     // future -> dropped
        )
        val nights = HealthConnectMapping.nights(stages, now, zone)
        assertEquals(1, nights.size)
        val n = nights[0]
        assertEquals(3, n.stages.size)
        assertEquals(at(2026, 9, 4, 0, 0), n.stages[0].end)
        assertEquals(at(2026, 9, 4, 1, 0), n.stages[1].end)
        assertEquals(now, n.stages[2].end)
        assertEquals(now, n.end)
        for (i in 1 until n.stages.size) assertTrue(n.stages[i - 1].end <= n.stages[i].start)
        // and the record constructor accepts it
        assertEquals(3, HealthConnectMapping.sleepSessionRecords(stages, now, zone).single().stages.size)
    }

    @Test
    fun twoNightsBecomeTwoSessions() {
        val now = at(2026, 9, 5, 12)
        val stages = listOf(
            SleepStage(at(2026, 9, 3, 23, 0), 2, 420),
            SleepStage(at(2026, 9, 4, 23, 30), 2, 400),
        )
        val records = HealthConnectMapping.sleepSessionRecords(stages, now, zone)
        assertEquals(listOf("sleep-2026-09-04", "sleep-2026-09-05"), records.map { it.metadata.clientRecordId })
    }

    @Test
    fun emptyInputsGiveNoRecords() {
        val now = at(2026, 9, 4, 12)
        assertTrue(HealthConnectMapping.stepsRecords(emptyList(), now, zone).isEmpty())
        assertTrue(HealthConnectMapping.heartRateRecords(emptyList(), now, zone).isEmpty())
        assertTrue(HealthConnectMapping.spo2Records(emptyList(), now, zone).isEmpty())
        assertTrue(HealthConnectMapping.dailyDistanceRecords(emptyList(), UserProfile(), StrideSettings(), stride, now, zone).isEmpty())
        assertTrue(HealthConnectMapping.sleepSessionRecords(emptyList(), now, zone).isEmpty())
        assertTrue(HealthConnectMapping.exerciseSessionRecords(emptyList(), now, zone).isEmpty())
        assertNull(HealthConnectMapping.nights(emptyList(), now, zone).firstOrNull())
    }
}
