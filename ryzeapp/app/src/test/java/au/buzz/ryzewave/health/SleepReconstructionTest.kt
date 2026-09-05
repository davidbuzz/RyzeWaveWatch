package au.buzz.ryzewave.health

import au.buzz.ryzewave.core.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepReconstructionTest {

    private val MIN = 60_000L

    /**
     * The 16 real stages the watch recorded for the night to the morning of 2026-09-06 (from
     * captures/pixel_sleep_20260906/ryzewave.db): only 00:26–06:11 was staged, and it even has a 01:22–02:59 gap
     * the watch never staged, because an accidental exercise mode blocked sleep detection.
     */
    private fun realNight() = listOf(
        SleepStage(1788618360000L, 3, 3),    // 00:26 REM
        SleepStage(1788618540000L, 2, 10),   // 00:29 light
        SleepStage(1788619140000L, 4, 21),   // 00:39 awake
        SleepStage(1788620400000L, 2, 19),   // 01:00 light
        SleepStage(1788621540000L, 1, 3),    // 01:19 deep  (run ends 01:22)
        SleepStage(1788627540000L, 1, 86),   // 02:59 deep  (gap 01:22..02:59)
        SleepStage(1788632700000L, 3, 3),    // 04:25 REM
        SleepStage(1788632880000L, 1, 4),    // 04:28 deep
        SleepStage(1788633120000L, 4, 5),    // 04:32 awake
        SleepStage(1788633420000L, 2, 48),   // 04:37 light
        SleepStage(1788636300000L, 4, 1),    // 05:25 awake
        SleepStage(1788636360000L, 2, 9),    // 05:26 light
        SleepStage(1788636900000L, 4, 2),    // 05:35 awake
        SleepStage(1788637020000L, 2, 22),   // 05:37 light
        SleepStage(1788638340000L, 4, 6),    // 05:59 awake
        SleepStage(1788638700000L, 2, 6),    // 06:05 light (ends 06:11)
    )

    // Buzz's HR shows real sleep ~22:15 .. 07:00.
    private val windowStart = 1788610500000L   // 2026-09-05 22:15 local
    private val windowEnd = 1788642000000L     // 2026-09-06 07:00 local

    @Test
    fun fillsTheWholeWindowKeepingRealStagesAndFillingGapsWithGenericAsleep() {
        val r = SleepReconstruction.fillWindow(windowStart, windowEnd, realNight(), preserveStaged = true)
        val stages = r.stages

        // the result tiles the whole 22:15..07:00 window with no gaps and no overlaps
        assertEquals(windowStart, stages.first().start)
        assertEquals(windowEnd, stages.last().let { it.start + it.minutes * MIN })
        for (i in 1 until stages.size) {
            assertEquals("contiguous at $i", stages[i - 1].start + stages[i - 1].minutes * MIN, stages[i].start)
        }
        val windowMin = ((windowEnd - windowStart) / MIN).toInt()
        assertEquals(525, windowMin)
        assertEquals(windowMin, stages.sumOf { it.minutes })

        // all 16 real stages are kept unchanged; exactly 3 generic-asleep fills (before, mid-gap, after)
        assertEquals(16, r.stagedBlocks)
        assertEquals(3, r.genericBlocks)
        assertTrue(realNight().all { it in stages })

        // the fills are the two ends and the 01:22..02:59 gap the watch never staged
        val generic = stages.filter { it.stage == SleepStage.GENERIC_ASLEEP }
        assertEquals(3, generic.size)
        assertEquals(listOf(131, 97, 49), generic.map { it.minutes })

        // the real stage types survive where they were present (deep=1, light=2, REM=3, awake=4)
        val kept = stages.filterNot { it.stage == SleepStage.GENERIC_ASLEEP }.map { it.stage }.toSet()
        assertEquals(setOf(1, 2, 3, 4), kept)

        // no generic block overlaps a real stage
        for (g in generic) {
            val gEnd = g.start + g.minutes * MIN
            assertFalse(realNight().any { it.start < gEnd && it.start + it.minutes * MIN > g.start })
        }

        // total asleep = whole window (525) minus the 35 awake minutes = 490 (~8h45m minus awake = 8h10m)
        assertEquals(490, r.totalAsleepMin)
        assertEquals(8, r.totalAsleepMin / 60)
        assertEquals(10, r.totalAsleepMin % 60)
        // no deep/light/REM was invented: the staged deep/light/REM minutes equal the watch's own
        assertEquals(93, stages.filter { it.stage == 1 }.sumOf { it.minutes })   // deep: 3+86+4
        assertEquals(114, stages.filter { it.stage == 2 }.sumOf { it.minutes })  // light
        assertEquals(6, stages.filter { it.stage == 3 }.sumOf { it.minutes })    // REM
    }

    @Test
    fun preserveFalseReplacesTheWholeWindowWithOneGenericBlock() {
        val r = SleepReconstruction.fillWindow(windowStart, windowEnd, realNight(), preserveStaged = false)
        assertEquals(1, r.stages.size)
        assertEquals(0, r.stagedBlocks)
        assertEquals(1, r.genericBlocks)
        val only = r.stages.single()
        assertEquals(SleepStage.GENERIC_ASLEEP, only.stage)
        assertEquals(windowStart, only.start)
        assertEquals(525, only.minutes)
        assertEquals(525, r.totalAsleepMin)   // no awake -> the whole window is asleep
    }

    @Test
    fun emptyNightBecomesOneGenericBlockCoveringTheWindow() {
        val r = SleepReconstruction.fillWindow(windowStart, windowEnd, emptyList(), preserveStaged = true)
        assertEquals(1, r.stages.size)
        assertEquals(SleepStage.GENERIC_ASLEEP, r.stages.single().stage)
        assertEquals(525, r.stages.single().minutes)
        assertEquals(0, r.stagedBlocks)
    }

    @Test
    fun stagesOutsideTheWindowAreNotKeptAndGapsAtEndsAreFilled() {
        val before = SleepStage(windowStart - 30 * MIN, 2, 10)   // ends before the window: ignored
        val inside = SleepStage(windowStart + 60 * MIN, 1, 30)   // one real deep block inside
        val r = SleepReconstruction.fillWindow(windowStart, windowEnd, listOf(before, inside), preserveStaged = true)
        assertEquals(1, r.stagedBlocks)                           // only the inside stage is kept
        assertTrue(inside in r.stages)
        assertFalse(before in r.stages)
        assertEquals(windowStart, r.stages.first().start)
        assertEquals(windowEnd, r.stages.last().let { it.start + it.minutes * MIN })
    }
}
