package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure delta / pause logic of the phone step counter, fed a fake `TYPE_STEP_COUNTER` cumulative source. */
class PhoneStepCounterTest {

    @Test
    fun countsDeltasFromTheBaselineNotTheAbsoluteReading() {
        val c = PhoneStepCounter()
        c.onReading(10_000)          // baseline: since-boot counter starts high
        assertEquals(0, c.steps)
        c.onReading(10_050)
        c.onReading(10_120)
        assertEquals(120, c.steps)
    }

    @Test
    fun stepsWhilePausedAreExcludedThenCountingResumes() {
        val c = PhoneStepCounter()
        c.onReading(1_000)           // baseline
        c.onReading(1_050)           // +50 running
        assertEquals(50, c.steps)
        c.setPaused(true)
        c.onReading(1_500)           // +450 while paused: dropped
        c.onReading(1_600)           // +100 while paused: dropped
        assertEquals(50, c.steps)
        c.setPaused(false)
        c.onReading(1_660)           // +60 running again
        assertEquals(110, c.steps)
    }

    @Test
    fun aCounterResetIsIgnoredAndRebaselines() {
        val c = PhoneStepCounter()
        c.onReading(5_000)
        c.onReading(5_030)
        assertEquals(30, c.steps)
        c.onReading(20)              // device rebooted: counter reset, non-positive delta ignored
        assertEquals(30, c.steps)
        c.onReading(45)              // continues from the new baseline
        assertEquals(55, c.steps)
    }

    @Test
    fun resetClearsEverything() {
        val c = PhoneStepCounter()
        c.onReading(100)
        c.onReading(160)
        assertEquals(60, c.steps)
        c.reset()
        assertEquals(0, c.steps)
        c.onReading(1_000)          // new baseline after reset
        c.onReading(1_010)
        assertEquals(10, c.steps)
    }
}
