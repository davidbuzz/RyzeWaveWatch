package au.buzz.ryzewave.ui

import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import au.buzz.ryzewave.workout.DefaultStrideModel

class ChartDataTest {
    private val day = 1_800_000_000_000L   // any epoch ms; the hour maths is relative
    private val h = ChartData.HOUR_MS
    private val m = ChartData.MINUTE_MS

    @Test
    fun segmentsBreakOnGapsLargerThanMax() {
        val pts = listOf(Pt(0, 1.0), Pt(10 * m, 2.0), Pt(20 * m, 3.0), Pt(60 * m, 4.0), Pt(70 * m, 5.0))
        val segs = ChartData.segments(pts, 15 * m)
        assertEquals(2, segs.size)
        assertEquals(3, segs[0].size)
        assertEquals(2, segs[1].size)
        assertEquals(emptyList<List<Pt>>(), ChartData.segments(emptyList(), 15 * m))
    }

    @Test
    fun segmentsSortByTime() {
        val segs = ChartData.segments(listOf(Pt(20 * m, 3.0), Pt(0, 1.0), Pt(10 * m, 2.0)), 15 * m)
        assertEquals(listOf(0L, 10 * m, 20 * m), segs.single().map { it.time })
    }

    @Test
    fun hourSlotsIndexByHourAndMergeDuplicates() {
        val hours = listOf(
            StepsHour(day + 9 * h, 500, 480, 20),
            StepsHour(day + 9 * h, 100, 100, 0),
            StepsHour(day + 23 * h, 42, 42, 0),
            StepsHour(day + 24 * h, 999, 999, 0),   // next day: ignored
        )
        val slots = ChartData.hourSlots(hours, day)
        assertEquals(24, slots.size)
        assertEquals(600, slots[9]!!.total)
        assertEquals(20, slots[9]!!.run)
        assertEquals(42, slots[23]!!.total)
        assertNull(slots[0])
        val cum = ChartData.cumulativeSteps(slots)
        assertEquals(0, cum[8])
        assertEquals(600, cum[9])
        assertEquals(642, cum[23])
    }

    @Test
    fun haversineMatchesKnownDistance() {
        // ~111.2 km per degree of latitude
        val d = ChartData.haversineM(-27.0, 153.0, -28.0, 153.0)
        assertEquals(111_195.0, d, 300.0)
    }

    private fun tp(t: Long, lat: Double, lon: Double, accepted: Boolean = true) =
        TrackPoint(1, t, lat, lon, 5f, 1.5f, null, accepted)

    @Test
    fun cumulativeDistanceSkipsRejectedFixes() {
        val pts = listOf(
            tp(0, -27.0, 153.0),
            tp(1000, -27.0, 153.001, accepted = false),
            tp(2000, -27.0, 153.001),
        )
        val cum = ChartData.cumulativeDistance(pts)
        assertEquals(2, cum.size)
        assertEquals(0.0, cum[0].value, 0.0)
        assertEquals(98.7, cum[1].value, 2.0)   // 0.001 deg lon at -27 lat ~ 98.7 m
    }

    @Test
    fun kmMarkersInterpolateCrossings() {
        val cum = listOf(Pt(0, 0.0), Pt(100_000, 800.0), Pt(200_000, 1_200.0), Pt(300_000, 2_100.0))
        val km = ChartData.kmMarkers(cum)
        assertEquals(2, km.size)
        assertEquals(1.0, km[0].value, 0.0)
        assertEquals(150_000L, km[0].time)           // halfway between 800 and 1200
        assertEquals(2.0, km[1].value, 0.0)
        assertTrue(km[1].time in 288_000L..290_000L)  // (2000-1200)/900 of the way
    }

    @Test
    fun paceSeriesUsesRollingWindow() {
        // 1 m/s → 1000 s/km; window 30 s
        val cum = (0..60).map { s -> Pt(s * 1000L, s.toDouble()) }
        val pace = ChartData.paceSeries(cum, windowMs = 30_000L, minDistM = 5.0)
        assertEquals(61, pace.size)
        assertEquals(0.0, pace[0].value, 0.0)           // too little movement yet
        assertEquals(1000.0, pace[60].value, 1.0)
        assertEquals(1000.0, ChartData.currentPace(cum, 60_000L, 60.0), 1.0)
    }

    @Test
    fun niceCeilRoundsUpTo125() {
        assertEquals(1.0, ChartData.niceCeil(0.0), 0.0)
        assertEquals(500.0, ChartData.niceCeil(432.0), 0.0)
        assertEquals(1000.0, ChartData.niceCeil(501.0), 0.0)
        assertEquals(2000.0, ChartData.niceCeil(1500.0), 0.0)
        assertEquals(10_000.0, ChartData.niceCeil(8_432.0), 0.0)
        assertEquals(5.0, ChartData.niceCeil(4.2), 0.0)
    }

    @Test
    fun stepsInWindowProRatesHours() {
        val hours = listOf(StepsHour(day + 9 * h, 600, 600, 0), StepsHour(day + 10 * h, 300, 300, 0))
        // 09:30 → 10:20 = half of hour 9 + a third of hour 10
        val steps = ChartData.stepsInWindow(hours, day + 9 * h + 30 * m, day + 10 * h + 20 * m)
        assertEquals(400.0, steps, 0.01)
        assertEquals(0.0, ChartData.stepsInWindow(hours, day + 12 * h, day + 13 * h), 0.0)
    }

    @Test
    fun fillDaysZeroFillsAndOrdersOldestFirst() {
        val today = Fmt.dayStart()
        val yesterday = Fmt.plusDays(today, -1)
        val filled = ChartData.fillDays(listOf(DailySummary(yesterday, 1234, 1200, 34, 900.0, null, null, null, null, null)), 7, today)
        assertEquals(7, filled.size)
        assertEquals(today, filled.last().dayStart)
        assertEquals(1234, filled[5].steps)
        assertEquals(0, filled.last().steps)
        assertTrue(filled.zipWithNext().all { (a, b) -> a.dayStart < b.dayStart })
    }

    @Test
    fun extendGridKeepsStepUntilCovered() {
        val grid = listOf(40.0, 75.0, 110.0, 145.0, 180.0)
        assertEquals(grid, extendGrid(grid, 150.0))
        assertEquals(grid + listOf(215.0), extendGrid(grid, 200.0))
        assertEquals(listOf(1.0), extendGrid(listOf(1.0), 99.0))
    }

    @Test
    fun chartFrameMapsExtremesToEdges() {
        val f = ChartFrame.of(300f, 200f, Gutters(left = 30f, right = 10f, top = 5f, bottom = 15f), 0.0, 100.0, 40.0, 180.0)
        assertEquals(30f, f.left, 0f)
        assertEquals(290f, f.right, 0f)
        assertEquals(5f, f.top, 0f)
        assertEquals(185f, f.bottom, 0f)
        assertEquals(30f, f.x(0.0), 0.01f)
        assertEquals(290f, f.x(100.0), 0.01f)
        assertEquals(185f, f.y(40.0), 0.01f)
        assertEquals(5f, f.y(180.0), 0.01f)
        assertEquals(5f, f.y(250.0), 0.01f)         // clamped
        assertEquals(50.0, f.xValue(160f), 0.01)
        assertTrue(f.contains(100f, 100f))
    }

    @Test
    fun chartFrameSurvivesDegenerateRanges() {
        val f = ChartFrame.of(10f, 10f, Gutters(50f, 50f, 50f, 50f), 5.0, 5.0, 1.0, 1.0)
        assertTrue(f.width > 0f)
        assertTrue(f.height > 0f)
        assertTrue(f.xMax > f.xMin)
        assertTrue(f.yMax > f.yMin)
    }

    @Test
    fun nearestByXRespectsThreshold() {
        val f = ChartFrame.of(100f, 100f, Gutters(0f, 0f, 0f, 0f), 0.0, 100.0, 0.0, 1.0)
        val pts = listOf(Pt(10, 0.1), Pt(50, 0.5), Pt(90, 0.9))
        assertEquals(50L, nearestByX(pts, f, 55f, 10f)!!.time)
        assertNull(nearestByX(pts, f, 30f, 10f))
        assertNull(nearestByX(emptyList(), f, 30f, 10f))
    }

    @Test
    fun sleepSummaryTotalsStagesAndFindsBedAndWake() {
        val bed = day + 22 * h
        val stages = listOf(
            SleepStage(bed + 60 * m, SleepMath.DEEP, 90),
            SleepStage(bed, SleepMath.LIGHT, 60),
            SleepStage(bed + 150 * m, SleepMath.REM, 30),
            SleepStage(bed + 180 * m, SleepMath.AWAKE, 10),
        )
        val s = SleepMath.summarize(stages)
        assertEquals(bed, s.bedTime)
        assertEquals(bed + 190 * m, s.wakeTime)
        assertEquals(180, s.totalMin)
        assertEquals(90, s.deepMin)
        assertEquals(60, s.lightMin)
        assertEquals(30, s.remMin)
        assertEquals(10, s.awakeMin)
        assertNotNull(SleepMath.summarize(emptyList()))
        assertNull(SleepMath.summarize(emptyList()).bedTime)
    }

    @Test
    fun strideDefaultsUseVendorFactors() {
        val male = au.buzz.ryzewave.core.UserProfile(heightCm = 180, male = true)
        val model = DefaultStrideModel()
        assertEquals(0.738, DefaultStrideModel.defaultWalkStrideM(male), 1e-9)
        assertEquals(0.9828, DefaultStrideModel.defaultRunStrideM(male), 1e-9)
        val calibrated = au.buzz.ryzewave.core.StrideSettings(walkStrideM = 0.8, runStrideM = null)
        assertEquals(0.8, model.walkStrideM(male, calibrated), 0.0)
        assertEquals(0.9828, model.runStrideM(male, calibrated), 1e-9)
        assertEquals(1000 * 0.8 + 100 * 0.9828, model.stepsToMeters(1000, 100, male, calibrated), 1e-6)
    }

    @Test
    fun meanValueRoundsToNearestAndIsSharedByCardAndChart() {
        // 75.5 must read 76 in both the stats row and the chart's "avg" label (not 75 via truncation)
        val pts = listOf(Pt(0, 75.0), Pt(m, 76.0))
        assertEquals(76, ChartData.meanValue(pts))
        assertEquals(75, ChartData.meanValue(listOf(Pt(0, 75.0), Pt(m, 75.4))))
        assertNull(ChartData.meanValue(emptyList()))
    }
}
