package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivitySignalsTest {
    private val W = 60_000L

    @Test
    fun stepsAreActiveOnlyWhenTheCounterRisesInsideTheWindow() {
        val s = ActivitySignals(windowMs = W); s.reset(0)
        s.onWatchSteps(5_000, 100); s.onWatchSteps(30_000, 100)
        assertEquals(SignalState.INACTIVE, s.snapshot(W)[Indicator.STEPS])          // flat: measured, nothing
        s.onWatchSteps(50_000, 101)
        assertEquals(SignalState.ACTIVE, s.snapshot(W)[Indicator.STEPS])
        val none = ActivitySignals(windowMs = W); none.reset(0)
        assertEquals(SignalState.UNKNOWN, none.snapshot(W)[Indicator.STEPS])        // never measured
        assertFalse(none.snapshot(W - 1).windowCovered); assertTrue(none.snapshot(W).windowCovered)
    }

    @Test
    fun theRiseIsJudgedAgainstTheLastCountBeforeTheWindow() {
        val s = ActivitySignals(windowMs = W); s.reset(0)
        s.onWatchSteps(1_000, 100)              // before the window judged at t = 2W
        s.onWatchSteps(W + 10_000, 100)         // inside: still 100 → inactive
        assertEquals(SignalState.INACTIVE, s.snapshot(2 * W)[Indicator.STEPS])
        s.onWatchSteps(W + 20_000, 103)
        assertEquals(SignalState.ACTIVE, s.snapshot(2 * W)[Indicator.STEPS])
    }

    @Test
    fun gpsHrAndMotionThresholds() {
        val s = ActivitySignals(windowMs = W); s.reset(0); s.restingBaselineBpm = 60
        s.onGps(5_000, 0.0, 0.3); s.onGps(20_000, 4.0, 0.5)                        // jitter only
        s.onHr(6_000, 70); s.onMotion(7_000, 0.05)
        val snap = s.snapshot(W)
        assertEquals(SignalState.INACTIVE, snap[Indicator.GPS])
        assertEquals(SignalState.INACTIVE, snap[Indicator.HR])                     // 70 < 60 + 15
        assertEquals(SignalState.INACTIVE, snap[Indicator.MOTION])
        assertFalse(snap.hrSleeping)                                              // 70 >= 60 + 5
        s.onGps(30_000, 25.0, 0.5); s.onHr(31_000, 76); s.onMotion(32_000, 0.5)
        val live = s.snapshot(W)
        assertEquals(SignalState.ACTIVE, live[Indicator.GPS])                       // 25 m of progress
        assertEquals(SignalState.ACTIVE, live[Indicator.HR])                       // 76 >= 75
        assertEquals(SignalState.ACTIVE, live[Indicator.MOTION])
        val asleep = ActivitySignals(windowMs = W); asleep.reset(0); asleep.restingBaselineBpm = 60
        asleep.onHr(1_000, 58); asleep.onHr(30_000, 62)
        assertTrue(asleep.snapshot(W).hrSleeping)                                  // all < 65
    }

    @Test
    fun restingBaselineIsThe10thPercentileOfPeriodicSamplesOnly() {
        val periodic = (60..99).map { HrSample(it.toLong(), it, SampleSource.AUTO) }
        val live = (1..40).map { HrSample(1000L + it, 150, SampleSource.WORKOUT) }
        assertEquals(63, RestingHrBaseline.of(periodic + live))                     // live spikes ignored
        assertEquals(65, RestingHrBaseline.of(live))                                // too few periodic → fallback
        assertEquals(40, RestingHrBaseline.of((1..10).map { HrSample(it.toLong(), 30, SampleSource.HISTORY) }))  // clamp
    }

    @Test
    fun motionVarianceCompletesBlocks() {
        val mv = MotionVariance(blockSize = 4)
        assertNull(mv.add(9.8)); assertNull(mv.add(9.8)); assertNull(mv.add(9.8))
        val still = mv.add(9.8); assertNotNull(still); assertEquals(0.0, still!!, 1e-9)
        listOf(8.0, 12.0, 8.0).forEach { mv.add(it) }
        val moving = mv.add(12.0); assertNotNull(moving); assertTrue(moving!! > ActivitySignals.MOTION_STILL_THRESHOLD)
    }
}
