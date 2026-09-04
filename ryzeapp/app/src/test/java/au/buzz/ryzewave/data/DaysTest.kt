package au.buzz.ryzewave.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DaysTest {
    private val brisbane: ZoneId = ZoneId.of("Australia/Brisbane")   // no DST
    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")       // DST

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun startOfDayIsLocalMidnight() {
        val noon = at(brisbane, 2026, 9, 4, 12, 30)
        assertEquals(at(brisbane, 2026, 9, 4), Days.startOfDay(noon, brisbane))
        assertEquals(LocalDate.of(2026, 9, 4), Days.localDate(noon, brisbane))
    }

    @Test
    fun dayEndIsNextMidnight() {
        val start = at(brisbane, 2026, 9, 4)
        assertEquals(start + 24L * 3600_000, Days.dayEnd(start, brisbane))
    }

    @Test
    fun dayEndFollowsWallClockAcrossDst() {
        // Sydney: DST starts 2026-10-04 (23 h day) and ends 2026-04-05 (25 h day)
        val shortDay = at(sydney, 2026, 10, 4)
        assertEquals(23L * 3600_000, Days.dayEnd(shortDay, sydney) - shortDay)
        val longDay = at(sydney, 2026, 4, 5)
        assertEquals(25L * 3600_000, Days.dayEnd(longDay, sydney) - longDay)
    }

    @Test
    fun recentDayStartsAreAscendingAndEndToday() {
        val now = at(brisbane, 2026, 9, 4, 20, 15)
        val starts = Days.recentDayStarts(7, now, brisbane)
        assertEquals(7, starts.size)
        assertEquals(at(brisbane, 2026, 8, 29), starts.first())
        assertEquals(at(brisbane, 2026, 9, 4), starts.last())
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun nightWindowSpansPreviousNoonToThisNoon() {
        val day = at(brisbane, 2026, 9, 4)
        val (from, to) = Days.nightWindow(day, brisbane)
        assertEquals(at(brisbane, 2026, 9, 3, 12), from)
        assertEquals(at(brisbane, 2026, 9, 4, 12), to)
    }
}
