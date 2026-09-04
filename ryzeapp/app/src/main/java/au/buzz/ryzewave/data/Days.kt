package au.buzz.ryzewave.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Local-calendar helpers for the epoch-millisecond timestamps used throughout the app. A "day start" is local
 * midnight; day windows are half-open and follow the wall clock, so a DST-change day is 23 or 25 hours long.
 */
object Days {
    fun localDate(time: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    fun startOf(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Local midnight at or before [time]. */
    fun startOfDay(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        startOf(localDate(time, zone), zone)

    /** The next local midnight after [dayStart] (exclusive end of that day). */
    fun dayEnd(dayStart: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        startOf(localDate(dayStart, zone).plusDays(1), zone)

    /**
     * Day starts for the last [days] calendar days ending with the day containing [now],
     * ascending (oldest first, today last).
     */
    fun recentDayStarts(days: Int, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val today = localDate(now, zone)
        return (days - 1 downTo 0).map { back -> startOf(today.minusDays(back.toLong()), zone) }
    }

    /**
     * The window that holds "last night's" sleep for the day starting at [dayStart]: from noon of the previous
     * day to noon of that day. The watch announces the session by its morning date and stage times after 12:00
     * belong to the previous evening (docs/PROTOCOL.md §6a), so this range captures the whole session.
     */
    fun nightWindow(dayStart: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val day = localDate(dayStart, zone)
        val from = day.minusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        val until = day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return Pair(from, until)
    }
}
