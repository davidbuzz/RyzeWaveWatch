package au.buzz.ryzewave.data

import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.workout.DefaultStrideModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B2-style hourly step rows -> daily distance, through the real pieces of the write/read path that run on the
 * JVM: [newOrChanged] (RoomHealthRepository.kt lines 224-228), [StepsHourEntity] mapping (Db.kt toEntity /
 * toModel), [buildDailySummary] (lines 234-259) and [DefaultStrideModel] with the default vendor factors.
 *
 * Room itself cannot run in a plain JVM unit test (no Robolectric / SQLite in app/build.gradle.kts lines
 * 61-62), so [MemStepsDao] stands in for the generated StepsDao: `upsert` replaces by the `hourStart` primary
 * key (Db.kt lines 46-54, @Upsert at 165-166) and `totalsOnce` is SUM(total/walk/run) over
 * hourStart >= from AND hourStart < to (lines 177-186). The sequence in [upsertSteps] mirrors
 * RoomHealthRepository.upsertSteps (lines 60-70) statement by statement.
 */
class StepsHourUpsertSummaryTest {

    /** In-memory model of the generated StepsDao. */
    private class MemStepsDao : StepsDao {
        val rows = sortedMapOf<Long, StepsHourEntity>()
        override suspend fun upsert(rows: List<StepsHourEntity>) {
            rows.forEach { this.rows[it.hourStart] = it }      // INSERT OR REPLACE semantics on the PK
        }
        override fun between(fromTime: Long, toTime: Long): Flow<List<StepsHourEntity>> =
            flowOf(rows.values.filter { it.hourStart >= fromTime && it.hourStart < toTime })
        override suspend fun rangeOnce(fromTime: Long, toTime: Long): List<StepsHourEntity> =
            rows.values.filter { it.hourStart >= fromTime && it.hourStart <= toTime }
        override suspend fun changedSince(time: Long): List<StepsHourEntity> =
            rows.values.filter { it.updatedAt >= time }
        private fun sum(fromTime: Long, toTime: Long): StepTotals {
            val inRange = rows.values.filter { it.hourStart >= fromTime && it.hourStart < toTime }
            return StepTotals(inRange.sumOf { it.total }, inRange.sumOf { it.walk }, inRange.sumOf { it.run })
        }
        override fun totals(fromTime: Long, toTime: Long): Flow<StepTotals?> = flowOf(sum(fromTime, toTime))
        override suspend fun totalsOnce(fromTime: Long, toTime: Long): StepTotals? = sum(fromTime, toTime)
    }

    private val dao = MemStepsDao()
    private var clock = 1_000L
    private val stride = DefaultStrideModel()
    private val profile = UserProfile(heightCm = 182, male = true)
    private val strideSettings = StrideSettings()

    // Local day 2026-09-04 in UTC for simplicity: hourStart values are dayStart + h * 3600_000
    private val dayStart = 1_788_480_000_000L          // any fixed epoch; only relative arithmetic matters
    private val dayEnd = dayStart + 24 * 3600_000L
    private fun hour(h: Int) = dayStart + h * 3600_000L

    /** RoomHealthRepository.upsertSteps (lines 60-70) with the DAO swapped for the in-memory one. */
    private suspend fun upsertSteps(hours: List<StepsHour>) {
        if (hours.isEmpty()) return
        val now = clock++
        val existing = dao.rangeOnce(hours.minOf { it.hourStart }, hours.maxOf { it.hourStart }).map(StepsHourEntity::toModel)
        val changed = newOrChanged(hours, existing) { it.hourStart }
        if (changed.isNotEmpty()) dao.upsert(changed.map { it.toEntity(now) })
    }

    /** RoomHealthRepository.dailySummaries / dailySummary assembly for one day (lines 128-145, 182-191). */
    private suspend fun summary() = buildDailySummary(
        dayStart = dayStart, totals = dao.totalsOnce(dayStart, dayEnd), stats = null, lastHr = null, lastSpo2 = null,
        profile = profile, strideSettings = strideSettings, stride = stride,
    )

    private val walkStride = 182 * 0.410 / 100.0      // 0.7462 m
    private val runStride = 182 * 0.546 / 100.0       // 0.99372 m

    @Test
    fun strideDefaultsFor182cmMale() {
        assertEquals(0.7462, stride.walkStrideM(profile, strideSettings), 1e-9)
        assertEquals(0.99372, stride.runStrideM(profile, strideSettings), 1e-9)
        assertEquals(walkStride, DefaultStrideModel.defaultWalkStrideM(profile), 1e-12)
        assertEquals(runStride, DefaultStrideModel.defaultRunStrideM(profile), 1e-12)
    }

    @Test
    fun dailyDistanceIsWalkTimesWalkStridePlusRunTimesRunStride() = runBlocking {
        // A B2 history sync: a walking morning, a 20-minute run at 17:00 (~3000 run steps), quiet otherwise
        upsertSteps(listOf(
            StepsHour(hour(7), total = 850, walk = 850, run = 0),
            StepsHour(hour(8), total = 1200, walk = 1200, run = 0),
            StepsHour(hour(12), total = 400, walk = 400, run = 0),
            StepsHour(hour(17), total = 3350, walk = 350, run = 3000),
        ))
        val s = summary()
        val walk = 850 + 1200 + 400 + 350
        val run = 3000
        println("STEPS_SUMMARY steps=${s.steps} walk=${s.walkSteps} run=${s.runSteps} distance=%.2f m".format(s.distanceMeters))
        assertEquals(5800, s.steps)
        assertEquals(walk, s.walkSteps)
        assertEquals(run, s.runSteps)
        assertEquals(walk * walkStride + run * runStride, s.distanceMeters, 1e-9)
        assertEquals(2800 * 0.7462 + 3000 * 0.99372, s.distanceMeters, 1e-9)     // 5070.52 m
        assertEquals(4, dao.rows.size)
        // the hour rows carry the clock stamp of the write
        assertEquals(1_000L, dao.rows[hour(17)]!!.updatedAt)
    }

    @Test
    fun reSendingTheSameHourIsSkippedButAGrownHourReplacesTheRow() = runBlocking {
        upsertSteps(listOf(StepsHour(hour(17), total = 1200, walk = 200, run = 1000)))
        val firstStamp = dao.rows[hour(17)]!!.updatedAt
        // the watch re-sends its whole history on every sync: identical row -> untouched (newOrChanged drops it)
        upsertSteps(listOf(StepsHour(hour(17), total = 1200, walk = 200, run = 1000)))
        assertEquals(firstStamp, dao.rows[hour(17)]!!.updatedAt)
        assertEquals(200 * walkStride + 1000 * runStride, summary().distanceMeters, 1e-9)

        // the hour grows (the run continued): larger total replaces the value, not adds to it
        upsertSteps(listOf(StepsHour(hour(17), total = 3350, walk = 350, run = 3000)))
        val s = summary()
        println("STEPS_SUMMARY grown hour: steps=${s.steps} walk=${s.walkSteps} run=${s.runSteps} distance=%.2f m".format(s.distanceMeters))
        assertEquals(1, dao.rows.size)
        assertEquals(3350, s.steps)
        assertEquals(350 * walkStride + 3000 * runStride, s.distanceMeters, 1e-9)
        assertEquals(firstStamp + 2, dao.rows[hour(17)]!!.updatedAt)
    }

    /**
     * A later record with a SMALLER total for the same hour (e.g. the B1 realtime `total=0` packet the watch
     * emits at hh:00:01, captures/bridge_passive_20260904_195347.txt line 1725) is NOT rejected: newOrChanged
     * (line 227, `current[key(it)] != it`) only checks inequality, and the @Upsert on the PK replaces the row.
     * The daily distance therefore drops until the next B2 sync re-sends the real value.
     */
    @Test
    fun smallerTotalForTheSameHourAlsoReplaces_reported() = runBlocking {
        upsertSteps(listOf(
            StepsHour(hour(16), total = 500, walk = 500, run = 0),
            StepsHour(hour(17), total = 3350, walk = 350, run = 3000),
        ))
        val before = summary()
        upsertSteps(listOf(StepsHour(hour(17), total = 0, walk = 0, run = 0)))
        val after = summary()
        println("STEPS_SUMMARY smaller total: before steps=${before.steps} distance=%.2f -> after steps=${after.steps} distance=%.2f".format(before.distanceMeters, after.distanceMeters))
        assertEquals(3850, before.steps)
        assertEquals(500, after.steps)
        assertEquals(StepsHourEntity(hour(17), 0, 0, 0, updatedAt = 1_001L), dao.rows[hour(17)])
        assertEquals(500 * walkStride, after.distanceMeters, 1e-9)
        assertEquals(before.distanceMeters - (350 * walkStride + 3000 * runStride), after.distanceMeters, 1e-9)
        // the real B2 re-send restores it (captures/verify_build3/watchgatt_sync.log line 16 shows this happening)
        upsertSteps(listOf(StepsHour(hour(17), total = 3350, walk = 350, run = 3000)))
        assertEquals(before.distanceMeters, summary().distanceMeters, 1e-9)
    }
}
