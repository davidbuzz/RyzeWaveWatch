package au.buzz.ryzewave.workout

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckModeDetectorTest {
    private val A = SignalState.ACTIVE
    private val N = SignalState.INACTIVE
    private val U = SignalState.UNKNOWN
    private fun states(steps: SignalState, gps: SignalState, hr: SignalState, motion: SignalState) =
        mapOf(Indicator.STEPS to steps, Indicator.GPS to gps, Indicator.HR to hr, Indicator.MOTION to motion)

    @Test
    fun yogaWithLowHrFlatStepsNoGpsButBodyMotionIsLive() {
        val v = StuckModeDetector.evaluate(SportSignature.expected(0x13), states(N, U, N, A), true, false, false)
        assertTrue(v.live); assertFalse(v.likelyStuck)
    }

    @Test
    fun rowingMachineWithHighHrAndNoGpsIsLive() {
        val v = StuckModeDetector.evaluate(SportSignature.expected(0x12), states(N, U, A, N), true, false, false)
        assertTrue(v.live); assertFalse(v.likelyStuck)
    }

    @Test
    fun outdoorRunWithGpsMovingButFlatStepsIsLive() {                    // phone in a bag: steps flat, GPS moving
        val v = StuckModeDetector.evaluate(SportSignature.expected(0x01), states(N, A, N, N), true, false, false)
        assertTrue(v.live); assertFalse(v.likelyStuck)
    }

    @Test
    fun outdoorRunWithEverythingFlatForTheWholeWindowIsStuckNormalUrgency() {
        val v = StuckModeDetector.evaluate(SportSignature.expected(0x01), states(N, N, N, N), true, false, false)
        assertTrue(v.likelyStuck); assertEquals(Urgency.NORMAL, v.urgency)
        assertEquals(StuckModeDetector.GRACE_NORMAL_MS, StuckModeDetector.graceMs(v.urgency))
    }

    @Test
    fun sameAtNightWithSleepingHrAndStillPhoneIsHighUrgency() {
        val v = StuckModeDetector.evaluate(SportSignature.expected(0x01), states(N, N, N, N), true, true, true)
        assertTrue(v.likelyStuck); assertEquals(Urgency.HIGH, v.urgency)
        assertEquals(StuckModeDetector.GRACE_HIGH_MS, StuckModeDetector.graceMs(v.urgency))
    }

    @Test
    fun aRestBetweenSetsBeforeTheWindowIsCoveredIsNotJudged() {
        val v = StuckModeDetector.evaluate(SportSignature.expected(0x61), states(N, U, N, N), false, false, false)
        assertFalse(v.likelyStuck); assertTrue(v.reason.contains("window"))
    }

    @Test
    fun aSportWhoseOnlyExpectedSignalIsUnavailableNeverFires() {          // yoga on a phone without an accelerometer
        val v = StuckModeDetector.evaluate(SportSignature.expected(0x13), states(N, N, N, U), true, true, true)
        assertFalse(v.likelyStuck); assertTrue(v.measurable.isEmpty())
        val swim = StuckModeDetector.evaluate(SportSignature.expected(0x04), states(N, N, N, N), true, true, true)
        assertFalse(swim.likelyStuck)                                       // unmonitored: nothing expected
    }

    @Test
    fun nightWindowIs2200To0559Local() {
        val zone = ZoneId.of("Australia/Brisbane")
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 9, 6, h, m, 0, 0, zone).toInstant().toEpochMilli()
        assertFalse(StuckModeDetector.isNight(at(21, 59), zone))
        assertTrue(StuckModeDetector.isNight(at(22, 0), zone))
        assertTrue(StuckModeDetector.isNight(at(5, 59), zone))
        assertFalse(StuckModeDetector.isNight(at(6, 0), zone))
    }
}
