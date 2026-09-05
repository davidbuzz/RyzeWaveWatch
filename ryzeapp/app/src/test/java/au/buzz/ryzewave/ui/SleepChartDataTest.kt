package au.buzz.ryzewave.ui

import au.buzz.ryzewave.core.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class SleepChartDataTest {
    private val m = ChartData.MINUTE_MS
    private val h = ChartData.HOUR_MS
    private val bed = 1_800_000_000_000L
    private val utc: ZoneId = ZoneId.of("UTC")

    @Test
    fun buildAssignsLanesAndTotalsOneNight() {
        val stages = listOf(
            SleepStage(bed + 60 * m, SleepMath.DEEP, 90),
            SleepStage(bed, SleepMath.LIGHT, 60),
            SleepStage(bed + 150 * m, SleepMath.REM, 30),
            SleepStage(bed + 180 * m, SleepMath.AWAKE, 10),
        )
        val chart = SleepChartData.build(stages)!!
        assertEquals(bed, chart.start)
        assertEquals(bed + 190 * m, chart.end)
        // sorted by start; lanes top to bottom are awake 0, REM 1, light 2, deep 3
        assertEquals(listOf(SleepMath.LIGHT, SleepMath.DEEP, SleepMath.REM, SleepMath.AWAKE), chart.blocks.map { it.stage })
        assertEquals(listOf(2, 3, 1, 0), chart.blocks.map { it.lane })
        assertEquals(listOf(60, 90, 30, 10), chart.blocks.map { it.minutes })
        val s = chart.summary
        assertEquals(180, s.totalMin)
        assertEquals(90, s.deepMin)
        assertEquals(60, s.lightMin)
        assertEquals(30, s.remMin)
        assertEquals(10, s.awakeMin)
        assertEquals(bed, s.bedTime)
        assertEquals(bed + 190 * m, s.wakeTime)
        assertEquals("3 h 0 m asleep · deep 1:30 · light 1:00 · REM 0:30 · awake 0:10", SleepChartData.totalsLine(s))
    }

    @Test
    fun laneLabelsFollowLaneOrder() {
        assertEquals(
            listOf("Awake", "REM", "Light", "Deep", "Asleep"),
            (0 until SleepChartData.LANE_COUNT).map { SleepChartData.laneLabel(it) },
        )
        assertEquals(3, SleepChartData.lane(SleepMath.DEEP))
        assertEquals(0, SleepChartData.lane(SleepMath.AWAKE))
        assertEquals(4, SleepChartData.lane(SleepMath.ASLEEP))
    }

    @Test
    fun genericAsleepCountsAsAsleepInItsOwnLaneNotAwake() {
        val stages = listOf(
            SleepStage(bed, SleepMath.ASLEEP, 120),          // 2 h "asleep, stage unknown"
            SleepStage(bed + 120 * m, SleepMath.DEEP, 30),
            SleepStage(bed + 150 * m, SleepMath.AWAKE, 10),
        )
        val chart = SleepChartData.build(stages)!!
        // generic-asleep sits in its own bottom lane (4), never the awake lane (0)
        assertEquals(4, chart.blocks.first { it.stage == SleepMath.ASLEEP }.lane)
        val s = chart.summary
        assertEquals(120, s.genericMin)
        assertEquals(30, s.deepMin)
        assertEquals(10, s.awakeMin)
        // total asleep = deep + light + REM + generic, and NOT the awake minutes
        assertEquals(150, s.totalMin)
        assertEquals("2 h 30 m asleep · deep 0:30 · light 0:00 · REM 0:00 · awake 0:10 · unstaged 2:00", SleepChartData.totalsLine(s))
    }

    @Test
    fun overlappingStageIsCutAtTheNextStartAndZeroLengthDropped() {
        val stages = listOf(
            SleepStage(bed, SleepMath.LIGHT, 60),
            SleepStage(bed + 40 * m, SleepMath.DEEP, 30),
            SleepStage(bed + 70 * m, SleepMath.REM, 0),
        )
        val chart = SleepChartData.build(stages)!!
        assertEquals(2, chart.blocks.size)
        assertEquals(bed + 40 * m, chart.blocks[0].end)
        assertEquals(40, chart.summary.lightMin)
        assertEquals(30, chart.summary.deepMin)
        assertEquals(70, chart.summary.totalMin)
        assertEquals(bed + 70 * m, chart.end)
    }

    @Test
    fun emptyNightIsNull() {
        assertNull(SleepChartData.build(emptyList()))
        assertNull(SleepChartData.build(listOf(SleepStage(bed, SleepMath.LIGHT, 0))))
        assertEquals(emptyList<Pair<Double, String>>(), SleepChartData.hourTicks(bed, bed, utc))
    }

    @Test
    fun unknownStageCodeLandsInTheLightLane() {
        val chart = SleepChartData.build(listOf(SleepStage(bed, 7, 15)))!!
        assertEquals(2, chart.blocks.single().lane)
        assertEquals(15, chart.summary.lightMin)
        assertEquals(15, chart.summary.totalMin)
    }

    @Test
    fun hourTicksFallOnWholeHours() {
        // 23:27 .. 06:17 UTC on an even day boundary: more than 6 h -> every even hour
        val day = 1_800_000_000_000L / ChartData.DAY_MS * ChartData.DAY_MS   // 00:00 UTC
        val start = day - 33 * m
        val end = day + 6 * h + 17 * m
        val ticks = SleepChartData.hourTicks(start, end, utc)
        assertEquals(listOf("00:00", "02:00", "04:00", "06:00"), ticks.map { it.second })
        assertEquals(day.toDouble(), ticks[0].first, 0.0)
        // 01:30 .. 05:10: hourly
        val short = SleepChartData.hourTicks(day + 90 * m, day + 5 * h + 10 * m, utc)
        assertEquals(listOf("02:00", "03:00", "04:00", "05:00"), short.map { it.second })
        // a tick exactly at the start is kept
        assertEquals("01:00", SleepChartData.hourTicks(day + h, day + 2 * h, utc).first().second)
    }

    @Test
    fun formatsHoursAndMinutes() {
        assertEquals("7 h 12 m", SleepChartData.hoursMinutes(432))
        assertEquals("0 h 5 m", SleepChartData.hoursMinutes(5))
        assertEquals("1:05", SleepChartData.hm(65))
        assertEquals("0:00", SleepChartData.hm(0))
    }

    /** The 32 `32` stage records of the night to 2026-09-05 as synced from the watch into the app's database on the Moto. */
    @Test
    fun replaysTheSyncedNightTo20260905() {
        fun S(start: Long, stage: Int, min: Int) = SleepStage(start, stage, min)
        val stages = listOf(
            S(1788528420000L, 2, 19), S(1788529560000L, 3, 1), S(1788529620000L, 2, 33), S(1788531600000L, 4, 3),
            S(1788531780000L, 2, 23), S(1788533160000L, 1, 1), S(1788533220000L, 4, 4), S(1788533460000L, 2, 14),
            S(1788534300000L, 4, 4), S(1788534540000L, 2, 7), S(1788534960000L, 1, 2), S(1788535080000L, 4, 4),
            S(1788535320000L, 2, 6), S(1788535680000L, 1, 32), S(1788537600000L, 2, 16), S(1788538560000L, 3, 4),
            S(1788538800000L, 2, 1), S(1788538860000L, 4, 2), S(1788538980000L, 1, 47), S(1788541800000L, 2, 7),
            S(1788542220000L, 4, 8), S(1788542700000L, 3, 1), S(1788542760000L, 2, 4), S(1788543000000L, 4, 5),
            S(1788543300000L, 3, 5), S(1788543600000L, 1, 13), S(1788544380000L, 4, 1), S(1788544440000L, 2, 15),
            S(1788545340000L, 3, 1), S(1788545400000L, 2, 1), S(1788545460000L, 1, 20), S(1788546660000L, 2, 106),
        )
        val chart = SleepChartData.build(stages)!!
        assertEquals(32, chart.blocks.size)
        // the watch's list is contiguous: every block ends where the next starts, so nothing was cut
        for (i in 1 until chart.blocks.size) assertEquals(chart.blocks[i].start, chart.blocks[i - 1].end)
        assertEquals(1788528420000L, chart.start)                       // 23:27 AEST
        assertEquals(1788546660000L + 106 * m, chart.end)               // 06:17 AEST
        val s = chart.summary
        assertEquals(115, s.deepMin)
        assertEquals(252, s.lightMin)
        assertEquals(12, s.remMin)
        assertEquals(31, s.awakeMin)
        assertEquals(379, s.totalMin)
        assertEquals(410, ((chart.end - chart.start) / m).toInt())
        assertEquals("6 h 19 m asleep · deep 1:55 · light 4:12 · REM 0:12 · awake 0:31", SleepChartData.totalsLine(s))
        val brisbane = ZoneId.of("Australia/Brisbane")
        assertEquals(listOf("00:00", "02:00", "04:00", "06:00"), SleepChartData.hourTicks(chart.start, chart.end, brisbane).map { it.second })
    }
}
