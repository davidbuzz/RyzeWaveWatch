package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily resting figures: the day split at the sleep record into a daytime resting rate and a sleeping rate,
 * each rated against its own column of the fitness table Buzz supplied on 2026-09-19.
 */
class RestingHrBaselineTest {
    private val h = 3600_000L
    private val m = 60_000L

    private fun s(t: Long, bpm: Int, src: SampleSource = SampleSource.AUTO) = HrSample(time = t, bpm = bpm, source = src)

    /** Asleep 01:00-06:00 at 55-65 (30 readings, median 60); awake from 06:05 at 70-95 (108 readings, 10th percentile 72). */
    private fun day(): List<HrSample> =
        (0 until 30).map { i -> s(1 * h + i * 10 * m, 55 + i % 11) } +
            (0 until 108).map { i -> s(6 * h + 5 * m + i * 10 * m, 70 + i % 26) }

    private val night = listOf(SleepStage(start = 1 * h, stage = SleepStage.LIGHT, minutes = 300))

    @Test
    fun theTableEdgesBelongToTheBandTheyStart() {
        assertEquals(FitnessBand.SEDENTARY, FitnessBand.ofDaytime(110))
        assertEquals(FitnessBand.SEDENTARY, FitnessBand.ofDaytime(75))
        assertEquals(FitnessBand.AVERAGE, FitnessBand.ofDaytime(74))
        assertEquals(FitnessBand.AVERAGE, FitnessBand.ofDaytime(60))
        assertEquals(FitnessBand.FIT, FitnessBand.ofDaytime(59))
        assertEquals(FitnessBand.FIT, FitnessBand.ofDaytime(50))
        assertEquals(FitnessBand.ATHLETIC, FitnessBand.ofDaytime(49))
        assertEquals(FitnessBand.ATHLETIC, FitnessBand.ofDaytime(38))

        assertEquals(FitnessBand.SEDENTARY, FitnessBand.ofSleeping(85))
        assertEquals(FitnessBand.SEDENTARY, FitnessBand.ofSleeping(60))
        assertEquals(FitnessBand.AVERAGE, FitnessBand.ofSleeping(59))
        assertEquals(FitnessBand.AVERAGE, FitnessBand.ofSleeping(50))
        assertEquals(FitnessBand.FIT, FitnessBand.ofSleeping(49))
        assertEquals(FitnessBand.FIT, FitnessBand.ofSleeping(45))
        assertEquals(FitnessBand.ATHLETIC, FitnessBand.ofSleeping(44))
        assertEquals(FitnessBand.ATHLETIC, FitnessBand.ofSleeping(33))
    }

    @Test
    fun theDayIsSplitAtTheSleepRecord() {
        val samples = day()
        val daytime = RestingHrBaseline.daytime(samples, RestingHrBaseline.spans(night, includeAwake = true))
        val sleeping = RestingHrBaseline.sleeping(samples, RestingHrBaseline.spans(night, includeAwake = false))
        assertEquals(72, daytime)
        assertEquals(60, sleeping)
        assertEquals(FitnessBand.AVERAGE, FitnessBand.ofDaytime(daytime))
        assertEquals(FitnessBand.SEDENTARY, FitnessBand.ofSleeping(sleeping!!))
        // the old whole-day percentile sat inside the night, which is why the split matters
        assertEquals(59, RestingHrBaseline.of(samples))
    }

    @Test
    fun withoutASleepRecordTheWholeDayIsTheDaytimeFigureAndThereIsNoSleepingOne() {
        val samples = day()
        assertEquals(RestingHrBaseline.of(samples), RestingHrBaseline.daytime(samples, emptyList()))
        assertNull(RestingHrBaseline.sleeping(samples, emptyList()))
    }

    @Test
    fun anAwakeSpellInTheNightCountsAsNeitherAsleepNorDaytime() {
        // up for ten minutes at 03:00 with the rate at 90: not sleeping, and not the daytime rest either
        val stages = listOf(
            SleepStage(1 * h, SleepStage.LIGHT, 120),
            SleepStage(3 * h, SleepStage.AWAKE, 10),
            SleepStage(3 * h + 10 * m, SleepStage.LIGHT, 170),
        )
        val samples = day() + s(3 * h + 2 * m, 90) + s(3 * h + 7 * m, 90)
        val asleep = RestingHrBaseline.spans(stages, includeAwake = false)
        assertEquals(listOf(1 * h..3 * h, (3 * h + 10 * m)..6 * h), asleep)
        assertEquals(listOf(1 * h..6 * h), RestingHrBaseline.spans(stages, includeAwake = true))
        assertEquals(60, RestingHrBaseline.sleeping(samples, asleep))
        assertEquals(72, RestingHrBaseline.daytime(samples, RestingHrBaseline.spans(stages, includeAwake = true)))
    }

    @Test
    fun spansBridgeShortGapsAndKeepANapSeparate() {
        val stages = listOf(
            SleepStage(14 * h, SleepStage.LIGHT, 30),
            SleepStage(22 * h, SleepStage.LIGHT, 60),
            SleepStage(23 * h + 30 * m, SleepStage.DEEP, 390),
        )
        assertEquals(listOf(14 * h..(14 * h + 30 * m), 22 * h..30 * h), RestingHrBaseline.spans(stages, includeAwake = true))
    }

    @Test
    fun aReadingStoredAsBothPushAndHistoryCountsOnce() {
        val twice = (0 until 3).flatMap { i -> listOf(s(i * 10 * m, 70, SampleSource.AUTO), s(i * 10 * m, 70, SampleSource.HISTORY)) }
        assertFalse("three readings, six rows", RestingHrBaseline.hasEnough(twice))
        val six = (0 until 6).flatMap { i -> listOf(s(i * 10 * m, 70, SampleSource.AUTO), s(i * 10 * m, 70, SampleSource.HISTORY)) }
        assertTrue(RestingHrBaseline.hasEnough(six))
        // workout and live streams never count
        assertFalse(RestingHrBaseline.hasEnough((0 until 20).map { i -> s(i * m, 150, SampleSource.WORKOUT) }))
    }
}
