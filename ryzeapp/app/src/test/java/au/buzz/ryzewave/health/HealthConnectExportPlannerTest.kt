package au.buzz.ryzewave.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.Workout
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectExportPlannerTest {
    private val zone: ZoneId = ZoneId.of("Australia/Brisbane")
    private val hour = HealthConnectMapping.HOUR_MS
    private val minute = HealthConnectMapping.MINUTE_MS

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun planner(
        repo: FakeHealthRepo,
        now: Long,
        lookback: Long = HealthConnectExportPlanner.DEFAULT_LOOKBACK_MS,
        settings: FakeSettings = FakeSettings(),
    ) = HealthConnectExportPlanner(repo, settings, fixedStrideModel, lookback, { zone }, { now })

    /**
     * What the exporter does after a successful run: the written records' fingerprints (with their companion
     * entries) and the markers go into the ledger.
     */
    private suspend fun FakeHealthRepo.exported(plan: HealthConnectExportPlanner.Plan) {
        markExported(plan.fingerprints, plan.now)
        for (id in plan.fingerprints.keys) plan.companions[id]?.let { markExported(it, plan.now) }
        markExported(plan.markers, plan.now)
        plan.newCursor?.let { cursors[HealthConnectMapping.CURSOR_KIND] = it }
    }

    private fun ids(plan: HealthConnectExportPlanner.Plan): Set<String> = plan.records.map { it.metadata.clientRecordId!! }.toSet()

    /** Everything written at phone time 09:30 on 2026-09-04. */
    private fun sampleRepo(): FakeHealthRepo = FakeHealthRepo().apply {
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
        assertEquals(now, plan.now)
        // every record has a client id and a fingerprint for the ledger
        assertTrue(plan.records.all { !it.metadata.clientRecordId.isNullOrEmpty() })
        assertEquals(plan.records.size, plan.records.map { it.metadata.clientRecordId }.toSet().size)
        assertEquals(ids(plan), plan.fingerprints.keys)
        assertEquals(0, plan.unchanged)
        assertEquals(setOf(HealthConnectMapping.STRIDE_MARKER_ID), plan.markers.keys)
    }

    @Test
    fun incrementalExportUsesChangeStampsAndLooksBackALittle() = runBlocking {
        val repo = sampleRepo()
        val now = at(2026, 9, 4, 10)
        val settings = FakeSettings()
        // a previous run stored the stride marker (an incremental run without one rebuilds every day, see below)
        repo.markExported(mapOf(HealthConnectMapping.STRIDE_MARKER_ID to HealthConnectMapping.strideFingerprint(settings.profileFlow.value, settings.strideFlow.value, fixedStrideModel)), at(2026, 9, 4, 9))
        val cursor = at(2026, 9, 4, 9, 40)                 // after the 09:30 writes: nothing changed since
        val plan = planner(repo, now, lookback = 5 * minute, settings = settings).plan(cursor)
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

    /**
     * The v2 -> v3 upgrade: exports ran before the ledger existed, so the cursor is set but there is no stride
     * marker. The daily distances in Health Connect may carry an old stride, so an incremental run without the
     * marker rebuilds every day with steps (the ledger, empty here, then decides: both days are written).
     */
    @Test
    fun missingStrideMarkerOnAnIncrementalRunRebuildsEveryDay() = runBlocking {
        val repo = sampleRepo()
        val cursor = at(2026, 9, 4, 9, 40)                 // nothing changed since; ledger empty, no marker
        val plan = planner(repo, at(2026, 9, 4, 10), lookback = 5 * minute).plan(cursor)
        val day3 = HealthConnectMapping.dayDistanceId(at(2026, 9, 3, 0))
        val day4 = HealthConnectMapping.dayDistanceId(at(2026, 9, 4, 0))
        assertEquals(setOf(day3, day4), ids(plan))
        assertEquals(0L, repo.asked["steps"])                                  // every day's hours were re-read
        assertEquals(ExportCounts(distance = 2), plan.counts)
        assertEquals(setOf(HealthConnectMapping.STRIDE_MARKER_ID), plan.markers.keys)

        // once the marker is stored the next incremental run is quiet again
        repo.exported(plan)
        val next = planner(repo, at(2026, 9, 4, 10, 30), lookback = 5 * minute).plan(plan.newCursor!!)
        assertTrue(next.records.isEmpty())
        assertEquals(plan.newCursor!! - 5 * minute, repo.asked["steps"])
        // a full run (cursor 0) never needs the marker: it rebuilds everything anyway, and the two distances that
        // went out above are the only candidates the ledger already holds
        repo.ledger.remove(HealthConnectMapping.STRIDE_MARKER_ID)
        val full = planner(repo, at(2026, 9, 4, 10, 40), lookback = 5 * minute).plan(0L)
        assertEquals(0L, repo.asked["steps"])
        assertEquals(2, full.unchanged)
        assertEquals(8, full.records.size)
        assertTrue(ids(full).none { it == day3 || it == day4 })
    }

    @Test
    fun changedHourIsRebuiltFromEverySampleOfThatHour() = runBlocking {
        // 14:00 hour: three samples synced at 14:30, three more synced at 15:00
        val repo = FakeHealthRepo()
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
        val repo = FakeHealthRepo()
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
        val repo = FakeHealthRepo()
        repo.sleep(SleepStage(at(2026, 9, 3, 23), 2, 60), updatedAt = at(2026, 9, 4, 6))
        repo.sleep(SleepStage(at(2026, 9, 4, 0), 1, 60), updatedAt = at(2026, 9, 4, 6))
        repo.sleep(SleepStage(at(2026, 9, 4, 1), 3, 30), updatedAt = at(2026, 9, 4, 7))
        val now = at(2026, 9, 4, 8)
        val plan = planner(repo, now, lookback = 0L).plan(at(2026, 9, 4, 6, 30))    // only the 01:00 stage changed
        val night = plan.records.filterIsInstance<SleepSessionRecord>().single()
        assertEquals(3, night.stages.size)
        assertEquals(at(2026, 9, 3, 23), night.startTime.toEpochMilli())
    }

    /** The exported workout's stored GPS fixes are read once and attached to its session as a route. */
    @Test
    fun workoutRouteIsLoadedFromTheRepository() = runBlocking {
        val repo = sampleRepo()
        val start = at(2026, 9, 4, 7)
        repo.tracks[1L] = listOf(
            TrackPoint(1, start + 1000, -27.50, 153.00, 6f, 1f, 50.0, true, 0.0),
            TrackPoint(1, start + 2000, -27.51, 153.00, 6f, 1f, 50.0, false, 0.0),
            TrackPoint(1, start + 3000, -27.50, 153.01, 6f, 1f, 50.0, true, 900.0),
            TrackPoint(1, start + 4000, -27.51, 153.01, 6f, 1f, 50.0, true, 2000.0),
        )
        val now = at(2026, 9, 4, 10)
        val plan = planner(repo, now).plan(0L)
        assertEquals(listOf(1L), repo.trackReads)
        assertEquals(mapOf("workout-1" to 3), plan.routes)
        assertEquals(3, plan.counts.routePoints)
        assertEquals(1, plan.counts.workouts)
        val session = plan.records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(3, HealthConnectMapping.routePointCount(session))
        // the route lives inside the session: the record count is unchanged
        assertEquals(ExportCounts(steps = 2, heartRate = 2, spo2 = 1, distance = 3, sleep = 1, workouts = 1, routePoints = 3), plan.counts)
        assertEquals(10, plan.counts.total)

        // without WRITE_EXERCISE_ROUTE the tracks are not even read
        repo.trackReads.clear()
        val noRoute = planner(repo, now).plan(0L, withRoutes = false)
        assertTrue(repo.trackReads.isEmpty())
        assertTrue(noRoute.routes.isEmpty())
        assertEquals(0, noRoute.counts.routePoints)
        assertEquals(0, HealthConnectMapping.routePointCount(noRoute.records.filterIsInstance<ExerciseSessionRecord>().single()))
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
        val repo = FakeHealthRepo()
        val plan = planner(repo, at(2026, 9, 4, 10)).plan(0L)
        assertTrue(plan.records.isEmpty())
        assertTrue(plan.counts.isEmpty)
        assertNull(plan.newCursor)
        // the stride marker is still offered, so the exporter can record the stride even when nothing was written
        assertEquals(setOf(HealthConnectMapping.STRIDE_MARKER_ID), plan.markers.keys)
    }

    @Test
    fun futureDataIsIgnored() = runBlocking {
        val now = at(2026, 9, 4, 10)
        val repo = FakeHealthRepo().apply {
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
        val repo = FakeHealthRepo().apply {
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

    // ---- the ledger: re-sends only when a row really changed after its last export

    /**
     * The export right after a workout (09:30 writes, export at 09:31) leaves every row inside the next export's
     * lookback window; without the ledger the second export re-sent the whole batch. Now the candidates are
     * re-read (the lookback is still there for clock jitter) but every one is identical to its last export.
     */
    @Test
    fun secondExportWithNoChangesPlansNothing() = runBlocking {
        val repo = sampleRepo()
        val first = planner(repo, at(2026, 9, 4, 9, 31)).plan(0L)
        assertEquals(10, first.records.size)
        repo.exported(first)

        val second = planner(repo, at(2026, 9, 4, 9, 33)).plan(first.newCursor!!)
        assertEquals(at(2026, 9, 4, 9, 26), second.from)            // 09:31 - 5 min: the 09:30 rows are candidates again
        assertEquals(at(2026, 9, 4, 9, 26), repo.asked["workouts"])
        assertTrue(second.records.isEmpty())
        assertTrue(second.counts.isEmpty)
        assertNull(second.newCursor)
        assertEquals(10, second.unchanged)
        assertTrue(second.fingerprints.isEmpty())

        // an hour later the 09:00 hour has closed: its steps record goes out once more with the final end time
        // (the count is the same; the interval is now 09:00-10:00 instead of 09:00-09:31), nothing else does
        val third = planner(repo, at(2026, 9, 4, 10, 31)).plan(first.newCursor!!)
        assertEquals(setOf(HealthConnectMapping.stepsId(at(2026, 9, 4, 9))), ids(third))
        assertEquals(at(2026, 9, 4, 10), third.records.filterIsInstance<StepsRecord>().single().endTime.toEpochMilli())
        assertEquals(9, third.unchanged)
        repo.exported(third)
        val fourth = planner(repo, at(2026, 9, 4, 11, 31)).plan(third.newCursor!!)
        assertTrue(fourth.records.isEmpty())
    }

    @Test
    fun changedWorkoutIsReplannedWithOnlyItsDistanceRecord() = runBlocking {
        val repo = sampleRepo()
        val first = planner(repo, at(2026, 9, 4, 9, 31)).plan(0L)
        repo.exported(first)

        // the workout is re-processed at 09:40 (sport type and distance change): one row changed
        val edited = repo.workouts.single().first.copy(sportType = 0x23, distanceMeters = 2600.0)
        repo.replaceWorkout(edited, updatedAt = at(2026, 9, 4, 9, 40))
        val plan = planner(repo, at(2026, 9, 4, 9, 45)).plan(first.newCursor!!)
        assertEquals(setOf("workout-1", "wdist-1"), ids(plan))
        assertEquals(ExportCounts(distance = 1, workouts = 1), plan.counts)
        assertEquals(at(2026, 9, 4, 9, 45), plan.newCursor)
        assertEquals(setOf("workout-1", "wdist-1"), plan.fingerprints.keys)
        assertNotEquals(repo.ledger["workout-1"], plan.fingerprints["workout-1"])
        assertNotEquals(repo.ledger["wdist-1"], plan.fingerprints["wdist-1"])
        val session = plan.records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, session.exerciseType)
        assertEquals(2600.0, plan.records.filterIsInstance<DistanceRecord>().single().distance.inMeters, 1e-6)

        repo.exported(plan)
        assertTrue(planner(repo, at(2026, 9, 4, 9, 50)).plan(plan.newCursor!!).records.isEmpty())

        // a change that leaves the distance record's content alone (sport type only) re-sends the session alone
        repo.replaceWorkout(edited.copy(sportType = 0x09), updatedAt = at(2026, 9, 4, 9, 52))
        val sessionOnly = planner(repo, at(2026, 9, 4, 9, 55)).plan(plan.newCursor!!)
        assertEquals(setOf("workout-1"), ids(sessionOnly))
    }

    @Test
    fun changedHourReplansItsStepsAndTheDayDistanceOnly() = runBlocking {
        val repo = sampleRepo()
        val first = planner(repo, at(2026, 9, 4, 9, 31)).plan(0L)
        repo.exported(first)

        // the 09:00 hour grows at the 09:58 sync
        repo.replaceSteps(StepsHour(at(2026, 9, 4, 9), 900, 900, 0), updatedAt = at(2026, 9, 4, 9, 58))
        val plan = planner(repo, at(2026, 9, 4, 10)).plan(first.newCursor!!)
        assertEquals(setOf(HealthConnectMapping.stepsId(at(2026, 9, 4, 9)), HealthConnectMapping.dayDistanceId(at(2026, 9, 4, 0))), ids(plan))
        assertEquals(900L, plan.records.filterIsInstance<StepsRecord>().single().count)
        assertEquals(900 * 0.7, plan.records.filterIsInstance<DistanceRecord>().single().distance.inMeters, 1e-6)
    }

    /** A sync that re-delivers the day's hours unchanged bumps nothing (the repository skips them), so nothing is planned. */
    @Test
    fun unchangedRedeliveryInsideTheLookbackIsNotReSent() = runBlocking {
        val repo = sampleRepo()
        val first = planner(repo, at(2026, 9, 4, 9, 36)).plan(0L)     // cursor 09:36: the 09:30 rows leave the 5-min window
        repo.exported(first)
        // an hour-in-progress row is re-written with the same values but a new stamp (a repository that did not de-duplicate)
        repo.replaceSteps(StepsHour(at(2026, 9, 4, 9), 300, 300, 0), updatedAt = at(2026, 9, 4, 9, 50))
        val plan = planner(repo, at(2026, 9, 4, 9, 55)).plan(first.newCursor!!)
        assertTrue(plan.records.isEmpty())
        assertEquals(2, plan.unchanged)                                   // the hour's steps and the day's distance were candidates
    }

    @Test
    fun strideChangeReplansEveryDayDistanceOnce() = runBlocking {
        val repo = sampleRepo()
        val settings = FakeSettings()
        val first = planner(repo, at(2026, 9, 4, 9, 31), settings = settings).plan(0L)
        repo.exported(first)
        val strideBefore = repo.ledger[HealthConnectMapping.STRIDE_MARKER_ID]!!

        // calibration at 09:40 changes the walking stride: no steps row changed, but every day's distance did
        settings.setStride(StrideSettings(walkStrideM = 0.8))
        val plan = planner(repo, at(2026, 9, 4, 9, 45), settings = settings).plan(first.newCursor!!)
        val day3 = HealthConnectMapping.dayDistanceId(at(2026, 9, 3, 0))
        val day4 = HealthConnectMapping.dayDistanceId(at(2026, 9, 4, 0))
        assertEquals(setOf(day3, day4), ids(plan))
        assertEquals(ExportCounts(distance = 2), plan.counts)
        val byId = plan.records.filterIsInstance<DistanceRecord>().associateBy { it.metadata.clientRecordId }
        assertEquals(450 * 0.8 + 50 * 1.0, byId[day3]!!.distance.inMeters, 1e-6)
        assertEquals(300 * 0.8, byId[day4]!!.distance.inMeters, 1e-6)
        assertEquals(0L, repo.asked["steps"])                                  // every day's hours were re-read
        assertNotEquals(strideBefore, plan.markers[HealthConnectMapping.STRIDE_MARKER_ID])

        // once written (marker included) the days are not sent again
        repo.exported(plan)
        val again = planner(repo, at(2026, 9, 4, 9, 50), settings = settings).plan(plan.newCursor!!)
        assertTrue(again.records.isEmpty())
        assertEquals(plan.markers, again.markers)
    }

    /** The derived stride comes from the profile's height, so a height change is a stride change too. */
    @Test
    fun strideMarkerFollowsTheEffectiveStrideNotJustTheSettings() {
        val model = au.buzz.ryzewave.workout.DefaultStrideModel()
        val short = HealthConnectMapping.strideFingerprint(UserProfile(heightCm = 170), StrideSettings(), model)
        val tall = HealthConnectMapping.strideFingerprint(UserProfile(heightCm = 185), StrideSettings(), model)
        val calibrated = HealthConnectMapping.strideFingerprint(UserProfile(heightCm = 170), StrideSettings(walkStrideM = 0.75), model)
        assertNotEquals(short, tall)
        assertNotEquals(short, calibrated)
        assertEquals(short, HealthConnectMapping.strideFingerprint(UserProfile(heightCm = 170, weightKg = 99), StrideSettings(), model))
    }

    /** With a stride change and a changed hour in the same run, each record is planned once. */
    @Test
    fun strideChangeAndChangedHourTogetherPlanEachRecordOnce() = runBlocking {
        val repo = sampleRepo()
        val settings = FakeSettings()
        val first = planner(repo, at(2026, 9, 4, 9, 31), settings = settings).plan(0L)
        repo.exported(first)
        settings.setStride(StrideSettings(walkStrideM = 0.8))
        repo.replaceSteps(StepsHour(at(2026, 9, 4, 9), 900, 900, 0), updatedAt = at(2026, 9, 4, 9, 58))
        val plan = planner(repo, at(2026, 9, 4, 10), settings = settings).plan(first.newCursor!!)
        val expected = setOf(
            HealthConnectMapping.stepsId(at(2026, 9, 4, 9)),
            HealthConnectMapping.dayDistanceId(at(2026, 9, 3, 0)),
            HealthConnectMapping.dayDistanceId(at(2026, 9, 4, 0)),
        )
        assertEquals(expected, ids(plan))
        assertEquals(plan.records.size, ids(plan).size)
        assertEquals(900 * 0.8, plan.records.filterIsInstance<DistanceRecord>().single { it.metadata.clientRecordId == HealthConnectMapping.dayDistanceId(at(2026, 9, 4, 0)) }.distance.inMeters, 1e-6)
    }

    /** A full export (cursor 0, the "Export now" button) also goes through the ledger: only what changed is written. */
    @Test
    fun fullExportAfterAnExportOnlyPlansWhatChanged() = runBlocking {
        val repo = sampleRepo()
        val first = planner(repo, at(2026, 9, 4, 9, 31)).plan(0L)
        repo.exported(first)
        assertTrue(planner(repo, at(2026, 9, 4, 9, 35)).plan(0L).records.isEmpty())

        repo.spo2(Spo2Sample(at(2026, 9, 4, 9, 40), 96), updatedAt = at(2026, 9, 4, 9, 41))
        val plan = planner(repo, at(2026, 9, 4, 9, 45)).plan(0L)
        assertEquals(setOf(HealthConnectMapping.spo2Id(at(2026, 9, 4, 9, 40))), ids(plan))
        assertEquals(10, plan.unchanged)
    }

    /** The hour in progress: a re-export with the same count but a later `now` is not a change (the cap is not content). */
    @Test
    fun growingHourIsReSentOnlyWhenItsCountChanges() = runBlocking {
        val repo = FakeHealthRepo()
        repo.steps(StepsHour(at(2026, 9, 4, 9), 40, 40, 0), updatedAt = at(2026, 9, 4, 9, 4))
        val first = planner(repo, at(2026, 9, 4, 9, 5)).plan(0L)
        assertEquals(ExportCounts(steps = 1, distance = 1), first.counts)
        repo.exported(first)

        // same row, nothing changed, later export inside the lookback: not re-sent
        val same = planner(repo, at(2026, 9, 4, 9, 8)).plan(first.newCursor!!)
        assertTrue(same.records.isEmpty())

        // the count grew: hour and day go out again with the new count
        repo.replaceSteps(StepsHour(at(2026, 9, 4, 9), 1900, 1700, 200), updatedAt = at(2026, 9, 4, 9, 20))
        val grown = planner(repo, at(2026, 9, 4, 9, 25)).plan(first.newCursor!!)
        assertEquals(ExportCounts(steps = 1, distance = 1), grown.counts)
        assertEquals(1900L, grown.records.filterIsInstance<StepsRecord>().single().count)
        assertEquals(at(2026, 9, 4, 9, 25), grown.records.filterIsInstance<StepsRecord>().single().endTime.toEpochMilli())
    }

    // ---- sessions: the track of a finished workout never changes, so it is read once

    private fun FakeHealthRepo.track1(start: Long) {
        tracks[1L] = listOf(
            TrackPoint(1, start + 1000, -27.50, 153.00, 6f, 1f, 50.0, true, 0.0),
            TrackPoint(1, start + 2000, -27.51, 153.00, 6f, 1f, 50.0, true, 900.0),
            TrackPoint(1, start + 3000, -27.51, 153.01, 6f, 1f, 50.0, true, 2000.0),
        )
    }

    /** After a session went out with its route, later runs know it is unchanged without reading the track. */
    @Test
    fun unchangedSessionIsSkippedWithoutReadingItsTrack() = runBlocking {
        val repo = sampleRepo()
        repo.track1(at(2026, 9, 4, 7))
        val first = planner(repo, at(2026, 9, 4, 9, 31)).plan(0L)
        assertEquals(listOf(1L), repo.trackReads)
        assertEquals(mapOf("workout-1" to 3), first.routes)
        assertEquals(0, first.sessionsSkipped)
        val base = HealthConnectMapping.sessionBaseMarkerId(1)
        val route = HealthConnectMapping.sessionRouteMarkerId(1)
        assertEquals(setOf(base, route), first.companions["workout-1"]!!.keys)
        assertEquals(3L, first.companions["workout-1"]!![route])
        assertEquals(setOf("workout-1"), first.companions.keys)                     // only sessions carry companions
        repo.exported(first)
        assertEquals(3L, repo.ledger[route])
        assertNotEquals(repo.ledger["workout-1"], repo.ledger[base])              // route-less fingerprint differs from the full one

        repo.trackReads.clear()
        val second = planner(repo, at(2026, 9, 4, 9, 33)).plan(first.newCursor!!)
        assertTrue(repo.trackReads.isEmpty())                                      // not read again
        assertTrue(second.records.isEmpty())
        assertEquals(1, second.sessionsSkipped)
        assertEquals(10, second.unchanged)                                         // 9 compared + 1 skipped session
        assertTrue(second.companions.isEmpty())

        // a full run ("Export now") skips it the same way
        val full = planner(repo, at(2026, 9, 4, 9, 34)).plan(0L)
        assertTrue(repo.trackReads.isEmpty())
        assertEquals(1, full.sessionsSkipped)
        assertTrue(full.records.isEmpty())

        // a changed workout row is read and planned again, with its route
        repo.replaceWorkout(repo.workouts.single().first.copy(distanceMeters = 2600.0), updatedAt = at(2026, 9, 4, 9, 40))
        val changed = planner(repo, at(2026, 9, 4, 9, 45)).plan(first.newCursor!!)
        assertEquals(listOf(1L), repo.trackReads)
        assertEquals(setOf("workout-1", "wdist-1"), ids(changed))
        assertEquals(0, changed.sessionsSkipped)
        assertEquals(mapOf("workout-1" to 3), changed.routes)
        assertNotEquals(repo.ledger[base], changed.companions["workout-1"]!![base])
    }

    /** Written without the route permission, then with it: the track is read when the permission arrives, then never again. */
    @Test
    fun sessionIsReReadWhenTheRoutePermissionArrives() = runBlocking {
        val repo = sampleRepo()
        repo.track1(at(2026, 9, 4, 7))
        val route = HealthConnectMapping.sessionRouteMarkerId(1)
        val first = planner(repo, at(2026, 9, 4, 9, 31)).plan(0L, withRoutes = false)
        assertTrue(repo.trackReads.isEmpty())
        assertEquals(HealthConnectMapping.ROUTE_NOT_PERMITTED, first.companions["workout-1"]!![route])
        repo.exported(first)

        // still no permission: the session is unchanged without a track read
        val again = planner(repo, at(2026, 9, 4, 9, 32)).plan(first.newCursor!!, withRoutes = false)
        assertTrue(repo.trackReads.isEmpty())
        assertEquals(1, again.sessionsSkipped)
        assertTrue(again.records.isEmpty())

        // permission granted: read, re-sent with the route, companions updated
        val withRoute = planner(repo, at(2026, 9, 4, 9, 35)).plan(first.newCursor!!, withRoutes = true)
        assertEquals(listOf(1L), repo.trackReads)
        assertEquals(setOf("workout-1"), ids(withRoute))
        assertEquals(mapOf("workout-1" to 3), withRoute.routes)
        assertEquals(3L, withRoute.companions["workout-1"]!![route])
        repo.exported(withRoute)

        repo.trackReads.clear()
        val settled = planner(repo, at(2026, 9, 4, 9, 36)).plan(withRoute.newCursor!!, withRoutes = true)
        assertTrue(repo.trackReads.isEmpty())
        assertEquals(1, settled.sessionsSkipped)
        assertTrue(settled.records.isEmpty())
    }

    /** A ledger from before the companions existed: the track is read once more, the companions are stored via the markers. */
    @Test
    fun sessionExportedBeforeCompanionsExistedGetsThemOnTheNextRun() = runBlocking {
        val repo = sampleRepo()
        repo.track1(at(2026, 9, 4, 7))
        val first = planner(repo, at(2026, 9, 4, 9, 31)).plan(0L)
        repo.markExported(first.fingerprints, first.now)                           // the old exporter: no companions
        repo.markExported(first.markers, first.now)

        repo.trackReads.clear()
        val second = planner(repo, at(2026, 9, 4, 9, 33)).plan(first.newCursor!!)
        assertEquals(listOf(1L), repo.trackReads)                                  // read once more
        assertTrue(second.records.isEmpty())                                       // the full record is unchanged
        assertEquals(0, second.sessionsSkipped)
        val base = HealthConnectMapping.sessionBaseMarkerId(1)
        val route = HealthConnectMapping.sessionRouteMarkerId(1)
        assertEquals(setOf(HealthConnectMapping.STRIDE_MARKER_ID, base, route), second.markers.keys)
        assertEquals(3L, second.markers[route])
        repo.exported(second)                                                      // markers stored on "nothing to export" too

        repo.trackReads.clear()
        val third = planner(repo, at(2026, 9, 4, 9, 34)).plan(first.newCursor!!)
        assertTrue(repo.trackReads.isEmpty())
        assertEquals(1, third.sessionsSkipped)
    }
}
