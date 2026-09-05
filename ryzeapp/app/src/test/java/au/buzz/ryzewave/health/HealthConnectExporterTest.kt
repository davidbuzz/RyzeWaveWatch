package au.buzz.ryzewave.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export loop against a fake Health Connect client that upserts by client record id exactly like the real
 * one (a write under an existing id only counts with a higher `clientRecordVersion`). Read-back after each
 * export: the client holds exactly the planned records, once each.
 */
class HealthConnectExporterTest {
    private val zone: ZoneId = ZoneId.of("Australia/Brisbane")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** The planner's clock; tests move it between exports. */
    private var now = 0L

    private fun exporter(repo: FakeHealthRepo, backend: FakeBackend, settings: FakeSettings = FakeSettings()) =
        HealthConnectExporter(
            backend, repo,
            HealthConnectExportPlanner(repo, settings, fixedStrideModel, HealthConnectExportPlanner.DEFAULT_LOOKBACK_MS, { zone }, { now }),
        )

    /** Everything written at phone time 09:30 on 2026-09-04 (the same data as the planner test). */
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

    /** What the ledger holds besides the records after a run: the stride marker and the session's companions. */
    private val ledgerExtras: Set<String> = setOf(
        HealthConnectMapping.STRIDE_MARKER_ID,
        HealthConnectMapping.sessionBaseMarkerId(1),
        HealthConnectMapping.sessionRouteMarkerId(1),
    )

    /** The client ids the sample repository maps to. */
    private val sampleIds: Set<String> = setOf(
        HealthConnectMapping.stepsId(at(2026, 9, 3, 8)),
        HealthConnectMapping.stepsId(at(2026, 9, 4, 9)),
        HealthConnectMapping.heartRateId(at(2026, 9, 3, 8)),
        HealthConnectMapping.heartRateId(at(2026, 9, 4, 9)),
        HealthConnectMapping.spo2Id(at(2026, 9, 4, 9, 20)),
        HealthConnectMapping.dayDistanceId(at(2026, 9, 3, 0)),
        HealthConnectMapping.dayDistanceId(at(2026, 9, 4, 0)),
        HealthConnectMapping.workoutDistanceId(1),
        HealthConnectMapping.sleepId(LocalDate.of(2026, 9, 4)),
        HealthConnectMapping.workoutId(1),
    )

    @Test
    fun firstExportWritesEveryPlannedRecordOnceAndReadsBack() = runBlocking {
        val repo = sampleRepo()
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        now = at(2026, 9, 4, 9, 31)

        val r = exporter.exportNew()
        assertEquals(ExportResult.Status.OK, r.status)
        assertEquals(10, r.inserted)
        assertEquals(0, r.failed)
        assertEquals(0, r.skipped)
        assertEquals(now, r.cursor)
        assertEquals(ExportCounts(steps = 2, heartRate = 2, spo2 = 1, distance = 3, sleep = 1, workouts = 1), r.counts)
        // read-back: exactly the planned records, keyed by client id, all at this export's version
        assertEquals(sampleIds, backend.client.records.keys)
        assertEquals(10, backend.client.records.size)
        assertTrue(backend.client.versions.values.all { it == now })
        assertEquals(0, backend.client.duplicates)
        assertEquals(1, backend.client.inserts.size)                              // one chunk
        // bookkeeping: cursor and ledger (records + the stride marker)
        assertEquals(now, repo.cursors[HealthConnectMapping.CURSOR_KIND])
        assertEquals(sampleIds + ledgerExtras, repo.ledger.keys)
        assertTrue(repo.ledgerTimes.values.all { it == now })
        assertEquals(r, exporter.lastResult.value)
        assertFalse(exporter.exporting.value)
    }

    @Test
    fun secondExportWithoutChangesWritesNothing() = runBlocking {
        val repo = sampleRepo()
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        now = at(2026, 9, 4, 9, 31)
        exporter.exportNew()
        val before = HashMap(backend.client.versions)

        now = at(2026, 9, 4, 9, 33)                                               // inside the lookback: same candidates
        val r = exporter.exportNew()
        assertEquals(ExportResult.Status.NOTHING_TO_EXPORT, r.status)
        assertEquals(0, r.inserted)
        assertEquals(10, r.skipped)
        assertTrue(r.ok)
        assertEquals(at(2026, 9, 4, 9, 31), r.cursor)                             // unchanged
        assertEquals(1, backend.client.inserts.size)                              // no insertRecords call at all
        assertEquals(before, backend.client.versions)
        assertEquals(at(2026, 9, 4, 9, 31), repo.cursors[HealthConnectMapping.CURSOR_KIND])

        // the "Export now" button (whole history) is just as quiet
        now = at(2026, 9, 4, 9, 34)
        val all = exporter.exportAll()
        assertEquals(ExportResult.Status.NOTHING_TO_EXPORT, all.status)
        assertEquals(1, backend.client.inserts.size)
    }

    @Test
    fun changedWorkoutIsUpsertedUnderTheSameIdWithAHigherVersion() = runBlocking {
        val repo = sampleRepo()
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        val first = at(2026, 9, 4, 9, 31)
        now = first
        exporter.exportNew()

        // the workout row changes (distance recomputed) at 09:40
        val w = repo.workouts.single().first.copy(distanceMeters = 2600.0)
        repo.replaceWorkout(w, updatedAt = at(2026, 9, 4, 9, 40))
        now = at(2026, 9, 4, 9, 45)
        val r = exporter.exportNew()
        assertEquals(ExportResult.Status.OK, r.status)
        assertEquals(2, r.inserted)
        assertEquals(ExportCounts(distance = 1, workouts = 1), r.counts)
        assertEquals(now, r.cursor)
        // read-back: still ten records, the two changed ones at the new version, the others untouched
        assertEquals(sampleIds, backend.client.records.keys)
        assertEquals(10, backend.client.records.size)
        assertEquals(0, backend.client.duplicates)
        assertEquals(0, backend.client.ignoredStale)
        assertEquals(now, backend.client.versions["workout-1"])
        assertEquals(now, backend.client.versions["wdist-1"])
        assertEquals(2600.0, (backend.client.records["wdist-1"] as androidx.health.connect.client.records.DistanceRecord).distance.inMeters, 1e-6)
        assertTrue(backend.client.records["workout-1"]!!.let { (it as ExerciseSessionRecord).notes!!.startsWith("2.60 km") })
        for (id in sampleIds - setOf("workout-1", "wdist-1")) assertEquals(id, first, backend.client.versions[id])
        assertEquals(now, repo.ledgerTimes["workout-1"])
        assertEquals(first, repo.ledgerTimes["hr-${at(2026, 9, 4, 9)}"])
    }

    @Test
    fun forcedFullExportRewritesEverythingWithHigherVersionsWithoutDuplicates() = runBlocking {
        val repo = sampleRepo()
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        now = at(2026, 9, 4, 9, 31)
        exporter.exportNew()

        now = at(2026, 9, 4, 10, 0)
        val r = exporter.exportAll(force = true)
        assertEquals(ExportResult.Status.OK, r.status)
        assertEquals(10, r.inserted)
        assertEquals(0, r.skipped)
        assertEquals(1, repo.ledgerClears)
        assertEquals(sampleIds, backend.client.records.keys)
        assertEquals(10, backend.client.records.size)
        assertTrue(backend.client.versions.values.all { it == now })
        assertEquals(0, backend.client.duplicates)
        assertEquals(0, backend.client.ignoredStale)
        assertEquals(sampleIds + ledgerExtras, repo.ledger.keys)
    }

    @Test
    fun strideChangeReExportsTheDailyDistancesOnly() = runBlocking {
        val repo = sampleRepo()
        val backend = FakeBackend()
        val settings = FakeSettings()
        val exporter = exporter(repo, backend, settings)
        val first = at(2026, 9, 4, 9, 31)
        now = first
        exporter.exportNew()

        settings.setStride(StrideSettings(walkStrideM = 0.8))
        now = at(2026, 9, 4, 9, 45)
        val r = exporter.exportNew()
        assertEquals(ExportResult.Status.OK, r.status)
        assertEquals(ExportCounts(distance = 2), r.counts)
        assertEquals(2, r.inserted)
        val day3 = HealthConnectMapping.dayDistanceId(at(2026, 9, 3, 0))
        val day4 = HealthConnectMapping.dayDistanceId(at(2026, 9, 4, 0))
        assertEquals(now, backend.client.versions[day3])
        assertEquals(now, backend.client.versions[day4])
        assertEquals(450 * 0.8 + 50 * 1.0, (backend.client.records[day3] as androidx.health.connect.client.records.DistanceRecord).distance.inMeters, 1e-6)
        assertEquals(first, backend.client.versions["wdist-1"])                    // the GPS distance is not stride-based
        assertEquals(10, backend.client.records.size)

        now = at(2026, 9, 4, 9, 50)
        assertEquals(ExportResult.Status.NOTHING_TO_EXPORT, exporter.exportNew().status)
    }

    @Test
    fun nothingToExportStillRecordsTheStrideMarker() = runBlocking {
        val repo = FakeHealthRepo()
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        now = at(2026, 9, 4, 9, 31)
        val r = exporter.exportNew()
        assertEquals(ExportResult.Status.NOTHING_TO_EXPORT, r.status)
        assertNull(r.cursor)
        assertTrue(backend.client.inserts.isEmpty())
        assertEquals(setOf(HealthConnectMapping.STRIDE_MARKER_ID), repo.ledger.keys)
    }

    @Test
    fun rejectedRecordIsNotRememberedAndTheRestIs() = runBlocking {
        val repo = sampleRepo()
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        val bad = HealthConnectMapping.spo2Id(at(2026, 9, 4, 9, 20))
        backend.client.reject += bad
        now = at(2026, 9, 4, 9, 31)
        val r = exporter.exportNew()
        assertEquals(ExportResult.Status.OK, r.status)
        assertEquals(9, r.inserted)
        assertEquals(1, r.failed)
        assertTrue(r.message!!.contains("1 records rejected"))
        assertEquals(sampleIds - bad, backend.client.records.keys)
        assertEquals(sampleIds - bad + ledgerExtras, repo.ledger.keys)
        assertEquals(now, repo.cursors[HealthConnectMapping.CURSOR_KIND])
        assertEquals(11, backend.client.inserts.size)                             // the chunk, then one by one
    }

    @Test
    fun transientFailureKeepsCursorAndLedgerForARetry() = runBlocking {
        val repo = sampleRepo()
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        backend.client.failWith = IllegalStateException("ERROR_RATE_LIMIT_EXCEEDED")
        now = at(2026, 9, 4, 9, 31)
        val r = exporter.exportNew()
        assertEquals(ExportResult.Status.ERROR, r.status)
        assertEquals(0, r.inserted)
        assertFalse(r.ok)
        assertNull(repo.cursors[HealthConnectMapping.CURSOR_KIND])
        assertTrue(repo.ledger.isEmpty())
        assertTrue(backend.client.records.isEmpty())

        backend.client.failWith = null
        now = at(2026, 9, 4, 9, 36)
        val again = exporter.exportNew()
        assertEquals(ExportResult.Status.OK, again.status)
        assertEquals(10, again.inserted)
        assertEquals(sampleIds, backend.client.records.keys)
    }

    @Test
    fun missingPermissionOrProviderWritesNothing() = runBlocking {
        val repo = sampleRepo()
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        now = at(2026, 9, 4, 9, 31)

        backend.client.grantedPermissions = HealthConnectExporter.WRITE_PERMISSIONS - HealthConnectExporter.REQUIRED_PERMISSIONS.first()
        val noPerm = exporter.exportNew()
        assertEquals(ExportResult.Status.NO_PERMISSION, noPerm.status)
        assertTrue(backend.client.inserts.isEmpty())
        assertFalse(exporter.canExport())

        backend.client.grantedPermissions = HealthConnectExporter.WRITE_PERMISSIONS
        backend.status = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        val stale = exporter.exportNew()
        assertEquals(ExportResult.Status.UNAVAILABLE, stale.status)
        assertEquals(HealthConnectAvailability.UPDATE_REQUIRED, exporter.availability())
        assertTrue(backend.client.inserts.isEmpty())
        assertTrue(repo.ledger.isEmpty())
    }

    @Test
    fun routeGoesOutOnlyWithTheRoutePermission() = runBlocking {
        val repo = sampleRepo()
        val start = at(2026, 9, 4, 7)
        repo.tracks[1L] = listOf(
            TrackPoint(1, start + 1000, -27.50, 153.00, 6f, 1f, 50.0, true, 0.0),
            TrackPoint(1, start + 2000, -27.51, 153.00, 6f, 1f, 50.0, true, 900.0),
            TrackPoint(1, start + 3000, -27.51, 153.01, 6f, 1f, 50.0, true, 2000.0),
        )
        val backend = FakeBackend(FakeHealthConnectClient(granted = HealthConnectExporter.REQUIRED_PERMISSIONS))
        val exporter = exporter(repo, backend)
        now = at(2026, 9, 4, 9, 31)
        val r = exporter.exportNew()
        assertEquals(ExportResult.Status.OK, r.status)
        assertEquals(0, r.counts.routePoints)
        assertEquals(0, HealthConnectMapping.routePointCount(backend.client.records["workout-1"] as ExerciseSessionRecord))
        assertTrue(repo.trackReads.isEmpty())

        // granting the route permission changes the session's content (the route), so it is re-sent once
        backend.client.grantedPermissions = HealthConnectExporter.WRITE_PERMISSIONS
        now = at(2026, 9, 4, 9, 35)
        val withRoute = exporter.exportNew()
        assertEquals(ExportResult.Status.OK, withRoute.status)
        assertEquals(ExportCounts(workouts = 1, routePoints = 3), withRoute.counts)
        assertEquals(3, HealthConnectMapping.routePointCount(backend.client.records["workout-1"] as ExerciseSessionRecord))
        assertEquals(now, backend.client.versions["workout-1"])
        assertEquals(10, backend.client.records.size)
    }

    /** The exporter stores a session's companion entries with it; the next runs skip the track read. */
    @Test
    fun sessionTrackIsReadOnceAcrossExports() = runBlocking {
        val repo = sampleRepo()
        val start = at(2026, 9, 4, 7)
        repo.tracks[1L] = listOf(
            TrackPoint(1, start + 1000, -27.50, 153.00, 6f, 1f, 50.0, true, 0.0),
            TrackPoint(1, start + 2000, -27.51, 153.00, 6f, 1f, 50.0, true, 900.0),
            TrackPoint(1, start + 3000, -27.51, 153.01, 6f, 1f, 50.0, true, 2000.0),
        )
        val backend = FakeBackend()
        val exporter = exporter(repo, backend)
        now = at(2026, 9, 4, 9, 31)
        val first = exporter.exportNew()
        assertEquals(ExportResult.Status.OK, first.status)
        assertEquals(listOf(1L), repo.trackReads)
        assertEquals(3L, repo.ledger[HealthConnectMapping.sessionRouteMarkerId(1)])
        assertTrue(HealthConnectMapping.sessionBaseMarkerId(1) in repo.ledger)
        assertEquals(now, repo.ledgerTimes[HealthConnectMapping.sessionBaseMarkerId(1)])

        repo.trackReads.clear()
        now = at(2026, 9, 4, 9, 35)
        assertEquals(ExportResult.Status.NOTHING_TO_EXPORT, exporter.exportNew().status)
        assertEquals(ExportResult.Status.NOTHING_TO_EXPORT, exporter.exportAll().status)
        assertTrue(repo.trackReads.isEmpty())
        assertEquals(3, HealthConnectMapping.routePointCount(backend.client.records["workout-1"] as ExerciseSessionRecord))

        // a forced full export clears the ledger (companions included): the track is read and the session re-sent
        now = at(2026, 9, 4, 9, 40)
        val forced = exporter.exportAll(force = true)
        assertEquals(ExportResult.Status.OK, forced.status)
        assertEquals(listOf(1L), repo.trackReads)
        assertEquals(1, forced.counts.workouts)
        assertEquals(3, forced.counts.routePoints)
    }
}
