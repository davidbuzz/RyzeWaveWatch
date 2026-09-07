package au.buzz.ryzewave.data

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StrideModel
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailySummaryTest {
    /** Fixed strides unless calibrated values are present. */
    private val model = object : StrideModel {
        override fun walkStrideM(profile: UserProfile, stride: StrideSettings) = stride.walkStrideM ?: 0.7
        override fun runStrideM(profile: UserProfile, stride: StrideSettings) = stride.runStrideM ?: 1.0
    }

    @Test
    fun aggregatesBecomeSummary() {
        val lastHr = HrSample(900L, 75, SampleSource.AUTO)
        val lastSpo2 = Spo2Sample(800L, 97)
        val s = buildDailySummary(
            dayStart = 0L,
            totals = StepTotals(total = 1100, walk = 1000, run = 100),
            stats = HrStats(minBpm = 55, maxBpm = 130, avgBpm = 72.6),
            lastHr = lastHr,
            lastSpo2 = lastSpo2,
            profile = UserProfile(),
            strideSettings = StrideSettings(),
            stride = model,
        )
        assertEquals(1100, s.steps)
        assertEquals(1000, s.walkSteps)
        assertEquals(100, s.runSteps)
        assertEquals(800.0, s.distanceMeters, 1e-9)
        assertEquals(55, s.minHr)
        assertEquals(130, s.maxHr)
        assertEquals(73, s.avgHr)
        assertEquals(lastHr, s.lastHr)
        assertEquals(lastSpo2, s.lastSpo2)
    }

    @Test
    fun calibratedStrideWins() {
        val s = buildDailySummary(0L, StepTotals(200, 100, 100), null, null, null,
            UserProfile(), StrideSettings(walkStrideM = 0.5, runStrideM = 2.0), model)
        assertEquals(250.0, s.distanceMeters, 1e-9)
    }

    /** Workout GPS metres are added on top of steps × stride (the watch's hourly steps exclude workouts). */
    @Test
    fun workoutGpsMetersAddToStrideDistance() {
        val s = buildDailySummary(0L, StepTotals(1100, 1000, 100), null, null, null,
            UserProfile(), StrideSettings(), model, workoutMeters = 2780.0)
        assertEquals(800.0 + 2780.0, s.distanceMeters, 1e-9)
    }

    @Test
    fun emptyDayIsZeroAndNull() {
        val s = buildDailySummary(123L, null, null, null, null, UserProfile(), StrideSettings(), model)
        assertEquals(123L, s.dayStart)
        assertEquals(0, s.steps)
        assertEquals(0.0, s.distanceMeters, 0.0)
        assertNull(s.minHr)
        assertNull(s.maxHr)
        assertNull(s.avgHr)
        assertNull(s.lastHr)
        assertNull(s.lastSpo2)
        val empty = buildDailySummary(123L, StepTotals(0, 0, 0), HrStats(null, null, null), null, null,
            UserProfile(), StrideSettings(), model)
        assertNull(empty.avgHr)
    }
}
