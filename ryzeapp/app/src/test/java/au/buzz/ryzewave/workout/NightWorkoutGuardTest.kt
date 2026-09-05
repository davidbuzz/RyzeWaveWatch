package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class NightWorkoutGuardTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** Epoch ms for [hour]:[minute] local (UTC) on a fixed day. */
    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 9, 6).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun accidental(startTime: Long, hr: Int? = 58, moving: Boolean = false) =
        NightWorkoutGuard.likelyAccidental(startTime, utc, hr, moving)

    @Test
    fun nightWindowBoundaries() {
        assertTrue(NightWorkoutGuard.isNight(at(22, 0), utc))     // 22:00 inclusive
        assertTrue(NightWorkoutGuard.isNight(at(23, 59), utc))
        assertTrue(NightWorkoutGuard.isNight(at(0, 0), utc))
        assertTrue(NightWorkoutGuard.isNight(at(5, 59), utc))     // still night
        assertFalse(NightWorkoutGuard.isNight(at(6, 0), utc))     // 06:00 exclusive: daytime
        assertFalse(NightWorkoutGuard.isNight(at(21, 59), utc))   // just before the window
        assertFalse(NightWorkoutGuard.isNight(at(13, 0), utc))
    }

    @Test
    fun accidentalOnlyWhenNightAndLowHrAndStill() {
        // all three conditions hold
        assertTrue(accidental(at(2, 30), hr = 58, moving = false))
        // exactly on the night boundary counts
        assertTrue(accidental(at(22, 0), hr = 60, moving = false))
        assertTrue(accidental(at(5, 59), hr = 74, moving = false))   // 74 < 75 threshold

        // daytime is never accidental, however low the HR
        assertFalse(accidental(at(6, 0), hr = 50, moving = false))
        assertFalse(accidental(at(13, 0), hr = 50, moving = false))

        // resting HR at/above the threshold: a real workout
        assertFalse(accidental(at(2, 30), hr = 75, moving = false))
        assertFalse(accidental(at(2, 30), hr = 90, moving = false))

        // unknown HR is treated conservatively (not accidental): we never auto-stop what we cannot vouch for
        assertFalse(accidental(at(2, 30), hr = null, moving = false))

        // GPS movement in the first minute means a genuine outdoor session
        assertFalse(accidental(at(2, 30), hr = 58, moving = true))
    }

    @Test
    fun customRestingThresholdIsHonoured() {
        assertFalse(NightWorkoutGuard.likelyAccidental(at(2, 30), utc, 70, false, restingThreshold = 65))
        assertTrue(NightWorkoutGuard.likelyAccidental(at(2, 30), utc, 60, false, restingThreshold = 65))
    }

    @Test
    fun restingHrAveragesTheRecentPeriodicSamples() {
        val samples = listOf(
            HrSample(1_000L, 60, SampleSource.AUTO),
            HrSample(2_000L, 62, SampleSource.AUTO),
            HrSample(3_000L, 64, SampleSource.AUTO),
            // 1 Hz live/workout spikes must be ignored, or they would mask the very thing we look for
            HrSample(3_500L, 140, SampleSource.WORKOUT),
            HrSample(3_600L, 150, SampleSource.LIVE),
        )
        assertEquals(62, NightWorkoutGuard.restingHr(samples))
    }

    @Test
    fun restingHrFallsBackToHistoryThenNull() {
        val history = listOf(HrSample(1L, 55, SampleSource.HISTORY), HrSample(2L, 57, SampleSource.HISTORY))
        assertEquals(56, NightWorkoutGuard.restingHr(history))
        assertNull(NightWorkoutGuard.restingHr(listOf(HrSample(1L, 140, SampleSource.WORKOUT))))
        assertNull(NightWorkoutGuard.restingHr(emptyList()))
    }
}
