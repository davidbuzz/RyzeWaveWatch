package au.buzz.ryzewave.ui

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DayClockTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun emitsTodayThenRollsOverAtMidnightOnly() = runTest {
        // Fake wall clock: 23:58 on 4 Sept, advanced by the ticker's own virtual time.
        val base = at(2026, 9, 4, 23, 58)
        val start = testScheduler.currentTime
        val clock = { base + (testScheduler.currentTime - start) }
        val seen = mutableListOf<Long>()
        val job = launch { DayClock.dayStarts(tickMs = DayClock.TICK_MS, now = clock).toList(seen) }

        advanceTimeBy(1)
        assertEquals(listOf(at(2026, 9, 4, 0, 0)), seen)

        advanceTimeBy(DayClock.TICK_MS)            // 23:59 — same day, no new emission
        assertEquals(1, seen.size)

        advanceTimeBy(2 * DayClock.TICK_MS)        // 00:01 on 5 Sept — rolled over exactly once
        assertEquals(listOf(at(2026, 9, 4, 0, 0), at(2026, 9, 5, 0, 0)), seen)

        advanceTimeBy(10 * DayClock.TICK_MS)       // still 5 Sept
        assertEquals(2, seen.size)
        job.cancel()
    }
}
