package au.buzz.ryzewave.ui

import au.buzz.ryzewave.core.SleepStage
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One filled block of the hypnogram: [start, end) epoch ms, drawn in [lane] (0 = top lane). */
data class SleepBlock(val start: Long, val end: Long, val stage: Int, val lane: Int) {
    val minutes: Int get() = ((end - start) / ChartData.MINUTE_MS).toInt()
}

/**
 * One night laid out for the sleep card: the x axis runs from [start] (bed, first stage start) to [end] (rise,
 * last stage end), [blocks] are the stages in lane order, [summary] the totals of exactly those blocks.
 */
data class SleepChart(val start: Long, val end: Long, val blocks: List<SleepBlock>, val summary: SleepSummary)

/**
 * Pure layout maths for the sleep hypnogram (History) and the one-line dashboard entry. Watch stage codes per
 * docs/PROTOCOL.md §6a: 1 = deep, 2 = light, 3 = REM, 4 = awake — the same mapping as
 * `HealthConnectMapping.sleepStageType` and `SleepMath`. No Android imports; unit-tested.
 */
object SleepChartData {
    /** Lanes top to bottom, as on a clinical hypnogram: awake, REM, light, deep. */
    val LANES: List<Int> = listOf(SleepMath.AWAKE, SleepMath.REM, SleepMath.LIGHT, SleepMath.DEEP)
    const val LANE_COUNT = 4

    /** Lane index (0 = top) for a watch stage code; unknown codes go in the light lane, as [SleepMath.summarize] counts them. */
    fun lane(stage: Int): Int = when (stage) {
        SleepMath.AWAKE -> 0
        SleepMath.REM -> 1
        SleepMath.LIGHT -> 2
        SleepMath.DEEP -> 3
        else -> 2
    }

    fun laneLabel(lane: Int): String = SleepMath.stageName(LANES[lane])

    /**
     * Blocks for one night: stages sorted by start, each ending at `start + minutes` but cut short by the next
     * stage's start (the watch's list is contiguous; an overlap would otherwise over-draw), zero-length stages
     * dropped. The totals are summed from the blocks so the line under the chart matches what is drawn.
     * Null for an empty night.
     */
    fun build(stages: List<SleepStage>): SleepChart? {
        val sorted = stages.filter { it.minutes > 0 }.sortedBy { it.start }
        if (sorted.isEmpty()) return null
        val blocks = ArrayList<SleepBlock>(sorted.size)
        for ((i, s) in sorted.withIndex()) {
            var end = s.start + s.minutes * ChartData.MINUTE_MS
            sorted.getOrNull(i + 1)?.let { next -> end = minOf(end, next.start) }
            if (end > s.start) blocks += SleepBlock(s.start, end, s.stage, lane(s.stage))
        }
        if (blocks.isEmpty()) return null
        val perLane = IntArray(LANE_COUNT)
        for (b in blocks) perLane[b.lane] += b.minutes
        val deep = perLane[lane(SleepMath.DEEP)]
        val light = perLane[lane(SleepMath.LIGHT)]
        val rem = perLane[lane(SleepMath.REM)]
        val awake = perLane[lane(SleepMath.AWAKE)]
        val start = blocks.first().start
        val end = blocks.last().end
        return SleepChart(
            start, end, blocks,
            SleepSummary(bedTime = start, wakeTime = end, totalMin = deep + light + rem, deepMin = deep, lightMin = light, remMin = rem, awakeMin = awake),
        )
    }

    /** "7 h 12 m" */
    fun hoursMinutes(minutes: Int): String = "${minutes / 60} h ${minutes % 60} m"

    /** "1:05" (hours:minutes, for the per-stage totals). */
    fun hm(minutes: Int): String = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)

    /** "7 h 12 m asleep · deep 1:05 · light 4:30 · REM 1:37 · awake 0:20" */
    fun totalsLine(s: SleepSummary): String =
        "${hoursMinutes(s.totalMin)} asleep · deep ${hm(s.deepMin)} · light ${hm(s.lightMin)} · REM ${hm(s.remMin)} · awake ${hm(s.awakeMin)}"

    /**
     * X-axis ticks at whole local hours inside [start, end]: every hour for a span up to 6 h, every second
     * (even) hour above that, so a night's labels never crowd. Positions are epoch ms.
     */
    fun hourTicks(start: Long, end: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Double, String>> {
        if (end <= start) return emptyList()
        val stepH = if (end - start <= 6 * ChartData.HOUR_MS) 1L else 2L
        val floor = Instant.ofEpochMilli(start).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        var t = if (floor.toInstant().toEpochMilli() < start) floor.plusHours(1) else floor
        while (t.hour % stepH != 0L) t = t.plusHours(1)
        val out = ArrayList<Pair<Double, String>>()
        while (t.toInstant().toEpochMilli() <= end) {
            out += Pair(t.toInstant().toEpochMilli().toDouble(), Fmt.hourLabel(t.hour))
            t = t.plusHours(stepH)
        }
        return out
    }
}
