package au.buzz.ryzewave.health

import androidx.health.connect.client.records.ExerciseRouteResult
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
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.workout.DefaultStrideModel
import au.buzz.ryzewave.workout.RealTrack
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // code 5 = app-generated generic-asleep -> Health Connect's generic "sleeping" (never awake/unknown)
        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, HealthConnectMapping.sleepStageType(5))
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, HealthConnectMapping.sleepStageType(0))
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, HealthConnectMapping.sleepStageType(9))
    }

    /** Every workout recorded before the sport picker used type 1 while walking, so type 1 alone is split by speed. */
    @Test
    fun typeOneIsSplitByAverageSpeed() {
        val walk = Workout(id = 1, start = 0, end = 1800_000, sportType = 1, distanceMeters = 2000.0, durationSeconds = 1800, avgHr = null, maxHr = null, calories = 0)
        val run = walk.copy(id = 2, distanceMeters = 5000.0)
        val unknown = walk.copy(id = 3, durationSeconds = 0, distanceMeters = 0.0)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, HealthConnectMapping.exerciseType(walk))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, HealthConnectMapping.exerciseType(run))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, HealthConnectMapping.exerciseType(unknown))
        assertEquals("Outdoor Running", HealthConnectMapping.exerciseTitle(run))
        assertEquals("Outdoor Walking", HealthConnectMapping.exerciseTitle(walk))
        assertEquals(0x23, HealthConnectMapping.effectiveSportType(walk))
        assertEquals(1, HealthConnectMapping.effectiveSportType(run))
        // the speed heuristic is a tie-breaker for type 1 only: a slow Trail Run stays running, a fast Outdoor Walk stays walking
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, HealthConnectMapping.exerciseType(walk.copy(sportType = 0x24)))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, HealthConnectMapping.exerciseType(run.copy(sportType = 0x23)))
        assertEquals("Outdoor Walking", HealthConnectMapping.exerciseTitle(run.copy(sportType = 0x23)))
    }

    @Test
    fun exerciseTypeOverrideWinsOverTheHeuristic() {
        // a slow type-1 workout the heuristic files as WALKING, but the user chose Running
        val slowRun = Workout(
            id = 7, start = 0, end = 1800_000, sportType = 1, distanceMeters = 2000.0,
            durationSeconds = 1800, avgHr = null, maxHr = null, calories = 0,
        )
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, HealthConnectMapping.exerciseType(slowRun))
        val overridden = slowRun.copy(exerciseTypeOverride = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, HealthConnectMapping.exerciseType(overridden))
        assertEquals("Running", HealthConnectMapping.exerciseTitle(overridden))
        // and it can force a non-speed type too (a ride filed as hiking, say)
        val hiked = slowRun.copy(exerciseTypeOverride = ExerciseSessionRecord.EXERCISE_TYPE_HIKING)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_HIKING, HealthConnectMapping.exerciseType(hiked))
        assertEquals("Hiking", HealthConnectMapping.exerciseTitle(hiked))
    }

    @Test
    fun exerciseTypeFollowsTheSportId() {
        val expected = mapOf(
            0x01 to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            0x24 to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            0x73 to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            0x1B to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            0x15 to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            0x09 to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            0x23 to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            0x02 to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            0x12 to ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            0x08 to ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
            0x04 to ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            0x13 to ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            0x1C to ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            0x61 to ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
            0x1F to ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
            0x29 to ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
            0x05 to ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON,                // every sport now has its closest type
            0x0B to ExerciseSessionRecord.EXERCISE_TYPE_SOCCER,
            0x22 to ExerciseSessionRecord.EXERCISE_TYPE_BOXING,
            0x4B to ExerciseSessionRecord.EXERCISE_TYPE_GOLF,
            0x6A to ExerciseSessionRecord.EXERCISE_TYPE_SURFING,
            0x63 to ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS,
            0x71 to ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,            // Bungee: no Health Connect type
            0x19 to ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS,  // Free Training
            0x7F to ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,   // not in the watch's list
        )
        for ((id, type) in expected) assertEquals("sport 0x%02X".format(id), type, HealthConnectMapping.exerciseTypeFor(id))
        // through a stored workout: a fast type-2 ride is biking (the speed rule never applies), and the title is the sport name
        val ride = Workout(id = 9, start = 0, end = 3600_000, sportType = 2, distanceMeters = 30_000.0, durationSeconds = 3600, avgHr = 130, maxHr = 160, calories = 700)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, HealthConnectMapping.exerciseType(ride))
        assertEquals("Cycling", HealthConnectMapping.exerciseTitle(ride))
        assertTrue(HealthConnectMapping.exerciseNotes(ride).endsWith("700 kcal, Cycling (sport type 2)"))
        val swim = ride.copy(sportType = 4, distanceMeters = 0.0)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, HealthConnectMapping.exerciseType(swim))
        assertEquals("Swimming", HealthConnectMapping.exerciseTitle(swim))
        assertEquals("Sport 127", HealthConnectMapping.exerciseTitle(ride.copy(sportType = 0x7F)))
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
        assertEquals("Outdoor Running", sessions[0].title)
        assertEquals("Outdoor Walking", sessions[1].title)
        assertTrue(sessions[0].notes!!.contains("4.50 km"))
        assertTrue(sessions[0].notes!!.contains("avg HR 140"))
        assertTrue(sessions[0].notes!!.endsWith("320 kcal, Outdoor Running (sport type 1)"))
    }

    // ---- exercise routes

    private fun fix(id: Long, time: Long, lat: Double, lon: Double, accepted: Boolean = true, acc: Float = 6f, alt: Double? = 57.4) =
        TrackPoint(id, time, lat, lon, acc, 1f, alt, accepted, null)

    @Test
    fun exerciseSessionCarriesARouteOfTheAcceptedFixesInsideTheSession() {
        val now = at(2026, 9, 5, 12)
        val start = at(2026, 9, 5, 8, 35)
        val end = start + 158_000
        val w = Workout(id = 1, start = start, end = end, sportType = 0x23, distanceMeters = 155.0, durationSeconds = 158, avgHr = 99, maxHr = 105, calories = 14)
        val points = listOf(
            fix(1, start - 1000, -27.4956, 152.9841),                       // before the session: dropped
            fix(1, start + 30_000, -27.4954, 152.9842, acc = 17.4f),        // later than the next row, sorted into place
            fix(1, start + 2000, -27.49565, 152.98411, acc = 52.4f, alt = null),
            fix(1, start + 15_000, -27.49545, 152.98422, accepted = false), // rejected: not part of the route
            fix(1, start + 30_000, -27.5, 153.0),                           // same instant as an earlier row: dropped
            fix(1, start + 60_000, -27.4953, 152.9845, acc = -1f),          // negative accuracy: carried without one
            fix(1, end - 1, -27.49536, 152.98451),                          // just before the end: kept
            fix(1, end, -27.5, 153.0),                                      // at the end: Health Connect wants route < end
            fix(1, end + 5000, -27.5, 153.0),                               // after the end: dropped
        )
        val locations = HealthConnectMapping.routeLocations(w, points)
        assertEquals(listOf(start + 2000, start + 30_000, start + 60_000, end - 1), locations.map { it.time.toEpochMilli() })
        assertEquals(-27.49565, locations[0].latitude, 1e-9)
        assertEquals(152.98411, locations[0].longitude, 1e-9)
        assertEquals(52.4, locations[0].horizontalAccuracy!!.inMeters, 1e-4)
        assertNull(locations[0].altitude)
        assertEquals(57.4, locations[1].altitude!!.inMeters, 1e-9)
        assertEquals(-27.4954, locations[1].latitude, 1e-9)
        assertNull(locations[2].horizontalAccuracy)
        assertNull(locations[0].verticalAccuracy)

        val session = HealthConnectMapping.exerciseSessionRecords(listOf(w), now, zone, mapOf(1L to points)).single()
        val result = session.exerciseRouteResult
        assertTrue(result is ExerciseRouteResult.Data)
        assertEquals(4, (result as ExerciseRouteResult.Data).exerciseRoute.route.size)
        assertEquals(4, HealthConnectMapping.routePointCount(session))
        assertEquals("workout-1", session.metadata.clientRecordId)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, session.exerciseType)

        // no track handed in: the session goes out without a route, exactly as before
        val bare = HealthConnectMapping.exerciseSessionRecords(listOf(w), now, zone).single()
        assertTrue(bare.exerciseRouteResult is ExerciseRouteResult.NoData)
        assertEquals(0, HealthConnectMapping.routePointCount(bare))
    }

    @Test
    fun routeIsSkippedWithFewerThanTwoUsableFixes() {
        val now = at(2026, 9, 5, 12)
        val start = at(2026, 9, 5, 8, 35)
        val w = Workout(id = 2, start = start, end = start + 60_000, sportType = 1, distanceMeters = 0.0, durationSeconds = 60, avgHr = null, maxHr = null, calories = 0)
        val one = listOf(fix(2, start + 1000, -27.5, 153.0))
        val oneAcceptedManyRejected = one + (2..10).map { fix(2, start + it * 1000L, -27.5, 153.0, accepted = false) }
        assertNull(HealthConnectMapping.exerciseRoute(w, emptyList()))
        assertNull(HealthConnectMapping.exerciseRoute(w, one))
        assertNull(HealthConnectMapping.exerciseRoute(w, oneAcceptedManyRejected))
        assertNull(HealthConnectMapping.exerciseRoute(w.copy(end = null), one + fix(2, start + 2000, -27.5, 153.0)))
        val session = HealthConnectMapping.exerciseSessionRecords(listOf(w), now, zone, mapOf(2L to oneAcceptedManyRejected)).single()
        assertTrue(session.exerciseRouteResult is ExerciseRouteResult.NoData)
        // two accepted fixes are enough
        val two = one + fix(2, start + 2000, -27.5001, 153.0001)
        assertEquals(2, HealthConnectMapping.exerciseRoute(w, two)!!.route.size)
        assertEquals(HealthConnectMapping.MIN_ROUTE_POINTS, 2)
    }

    /** The real outdoor walk: all 86 accepted fixes lie inside the session and become the route. */
    @Test
    fun realOutdoorWalkBecomesAnEightySixPointRoute() {
        val now = RealTrack.END + 3600_000
        val points = RealTrack.pixelOutdoorWalk()
        val session = HealthConnectMapping.exerciseSessionRecords(listOf(RealTrack.workout), now, zone, mapOf(RealTrack.WORKOUT_ID to points)).single()
        assertEquals(86, HealthConnectMapping.routePointCount(session))
        val route = (session.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route
        assertEquals(RealTrack.START, session.startTime.toEpochMilli())
        assertEquals(RealTrack.END, session.endTime.toEpochMilli())
        assertTrue(route.all { it.time >= session.startTime && it.time < session.endTime })
        assertTrue(route.zipWithNext().all { (a, b) -> a.time < b.time })
        assertEquals(52.39, route.first().horizontalAccuracy!!.inMeters, 0.01)
        assertEquals(57.4, route.first().altitude!!.inMeters, 0.01)
        assertEquals(-27.495656, route.first().latitude, 1e-9)
        assertEquals(152.9841077, route.first().longitude, 1e-9)
        assertEquals("Outdoor Walking", session.title)           // 155 m in 158 s: the type-1 tie-breaker says walking
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

    // ---- content fingerprints (the export ledger)

    /**
     * The fingerprint is the record's data: not its version (the export clock) and not the `now` cap of an hour /
     * day in progress, so a later export of unchanged data fingerprints the same; any change of the data does not.
     */
    @Test
    fun fingerprintIgnoresVersionAndTheNowCapButFollowsTheData() {
        val h = listOf(StepsHour(at(2026, 9, 4, 7), 40, 40, 0))
        val first = at(2026, 9, 4, 7, 5)
        val second = at(2026, 9, 4, 7, 25)
        val a = HealthConnectMapping.stepsRecords(h, first, zone).single()
        val b = HealthConnectMapping.stepsRecords(h, second, zone).single()
        assertTrue(a.metadata.clientRecordVersion != b.metadata.clientRecordVersion)
        assertTrue(a.endTime != b.endTime)
        assertEquals(HealthConnectMapping.fingerprint(a, first), HealthConnectMapping.fingerprint(b, second))
        // one more step: different
        val c = HealthConnectMapping.stepsRecords(listOf(StepsHour(at(2026, 9, 4, 7), 41, 41, 0)), second, zone).single()
        assertNotEquals(HealthConnectMapping.fingerprint(b, second), HealthConnectMapping.fingerprint(c, second))
        // the hour closed (end = 08:00, not the cap): different from the open hour, stable afterwards
        val d = HealthConnectMapping.stepsRecords(h, at(2026, 9, 4, 8, 30), zone).single()
        val e = HealthConnectMapping.stepsRecords(h, at(2026, 9, 4, 9, 30), zone).single()
        assertNotEquals(HealthConnectMapping.fingerprint(b, second), HealthConnectMapping.fingerprint(d, at(2026, 9, 4, 8, 30)))
        assertEquals(HealthConnectMapping.fingerprint(d, at(2026, 9, 4, 8, 30)), HealthConnectMapping.fingerprint(e, at(2026, 9, 4, 9, 30)))

        // the same holds for the day's distance
        val profile = UserProfile(heightCm = 180)
        val d1 = HealthConnectMapping.dailyDistanceRecords(h, profile, StrideSettings(), stride, first, zone).single()
        val d2 = HealthConnectMapping.dailyDistanceRecords(h, profile, StrideSettings(), stride, second, zone).single()
        assertEquals(HealthConnectMapping.fingerprint(d1, first), HealthConnectMapping.fingerprint(d2, second))
        val d3 = HealthConnectMapping.dailyDistanceRecords(h, profile, StrideSettings(walkStrideM = 0.8), stride, second, zone).single()
        assertNotEquals(HealthConnectMapping.fingerprint(d2, second), HealthConnectMapping.fingerprint(d3, second))

        // HR: an extra sample changes the hour's fingerprint
        val hr1 = HealthConnectMapping.heartRateRecords(listOf(HrSample(at(2026, 9, 4, 7, 2), 70)), first, zone).single()
        val hr2 = HealthConnectMapping.heartRateRecords(listOf(HrSample(at(2026, 9, 4, 7, 2), 70)), second, zone).single()
        val hr3 = HealthConnectMapping.heartRateRecords(listOf(HrSample(at(2026, 9, 4, 7, 2), 70), HrSample(at(2026, 9, 4, 7, 20), 90)), second, zone).single()
        assertEquals(HealthConnectMapping.fingerprint(hr1, first), HealthConnectMapping.fingerprint(hr2, second))
        assertNotEquals(HealthConnectMapping.fingerprint(hr2, second), HealthConnectMapping.fingerprint(hr3, second))

        // different record kinds never share a fingerprint by accident of equal numbers
        assertNotEquals(HealthConnectMapping.fingerprint(a, first), HealthConnectMapping.fingerprint(d1, first))
    }

    @Test
    fun workoutFingerprintCoversTheRouteAndTheSummary() {
        val w = Workout(id = 1, start = at(2026, 9, 4, 7), end = at(2026, 9, 4, 7, 30), sportType = 1, distanceMeters = 2500.0, durationSeconds = 1800, avgHr = 120, maxHr = 150, calories = 200)
        val now = at(2026, 9, 4, 9)
        val track = listOf(fix(1, w.start + 1000, -27.50, 153.00), fix(1, w.start + 2000, -27.51, 153.00), fix(1, w.start + 3000, -27.51, 153.01))
        val plain = HealthConnectMapping.exerciseSessionRecords(listOf(w), now, zone).single()
        val plainLater = HealthConnectMapping.exerciseSessionRecords(listOf(w), now + hour, zone).single()
        val routed = HealthConnectMapping.exerciseSessionRecords(listOf(w), now, zone, mapOf(1L to track)).single()
        val routedShorter = HealthConnectMapping.exerciseSessionRecords(listOf(w), now, zone, mapOf(1L to track.take(2))).single()
        val edited = HealthConnectMapping.exerciseSessionRecords(listOf(w.copy(sportType = 0x23)), now, zone, mapOf(1L to track)).single()
        assertEquals(HealthConnectMapping.fingerprint(plain, now), HealthConnectMapping.fingerprint(plainLater, now + hour))
        assertNotEquals(HealthConnectMapping.fingerprint(plain, now), HealthConnectMapping.fingerprint(routed, now))
        assertNotEquals(HealthConnectMapping.fingerprint(routed, now), HealthConnectMapping.fingerprint(routedShorter, now))
        assertNotEquals(HealthConnectMapping.fingerprint(routed, now), HealthConnectMapping.fingerprint(edited, now))

        val dist = HealthConnectMapping.workoutDistanceRecords(listOf(w), now, zone).single()
        val distLater = HealthConnectMapping.workoutDistanceRecords(listOf(w), now + hour, zone).single()
        val distMore = HealthConnectMapping.workoutDistanceRecords(listOf(w.copy(distanceMeters = 2600.0)), now, zone).single()
        assertEquals(HealthConnectMapping.fingerprint(dist, now), HealthConnectMapping.fingerprint(distLater, now + hour))
        assertNotEquals(HealthConnectMapping.fingerprint(dist, now), HealthConnectMapping.fingerprint(distMore, now))
    }

    @Test
    fun sleepFingerprintFollowsTheStages() {
        val stages = listOf(SleepStage(at(2026, 9, 3, 23), 2, 60), SleepStage(at(2026, 9, 4, 0), 1, 60))
        val now = at(2026, 9, 4, 8)
        val a = HealthConnectMapping.sleepSessionRecords(stages, now, zone).single()
        val b = HealthConnectMapping.sleepSessionRecords(stages, now + hour, zone).single()
        val c = HealthConnectMapping.sleepSessionRecords(stages + SleepStage(at(2026, 9, 4, 1), 3, 30), now, zone).single()
        val d = HealthConnectMapping.sleepSessionRecords(listOf(stages[0], stages[1].copy(stage = 3)), now, zone).single()
        assertEquals(HealthConnectMapping.fingerprint(a, now), HealthConnectMapping.fingerprint(b, now + hour))
        assertNotEquals(HealthConnectMapping.fingerprint(a, now), HealthConnectMapping.fingerprint(c, now))
        assertNotEquals(HealthConnectMapping.fingerprint(a, now), HealthConnectMapping.fingerprint(d, now))
    }
}
