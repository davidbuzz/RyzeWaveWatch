package au.buzz.ryzewave.ui

import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A (time, value) point; the shape every chart draws. Pure Kotlin, unit-tested. */
data class Pt(val time: Long, val value: Double)

/** Series preparation shared by the charts, the workout tracker and the stride calibration. No Android imports. */
object ChartData {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    const val TEN_MIN_MS = 10 * MINUTE_MS

    /** Splits a time series into runs; a gap longer than [maxGapMs] between neighbours breaks the line. */
    fun segments(points: List<Pt>, maxGapMs: Long): List<List<Pt>> {
        val sorted = points.sortedBy { it.time }
        val out = ArrayList<List<Pt>>()
        var cur = ArrayList<Pt>()
        for (p in sorted) {
            if (cur.isNotEmpty() && p.time - cur.last().time > maxGapMs) {
                out.add(cur)
                cur = ArrayList()
            }
            cur.add(p)
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    fun hrPoints(samples: List<HrSample>): List<Pt> = samples.map { Pt(it.time, it.bpm.toDouble()) }

    /** The mean of [points] rounded to the nearest whole number (null when empty); the one "average" a card shows. */
    fun meanValue(points: List<Pt>): Int? =
        if (points.isEmpty()) null else points.map { it.value }.average().roundToInt()
    fun spo2Points(samples: List<Spo2Sample>): List<Pt> = samples.map { Pt(it.time, it.percent.toDouble()) }

    /**
     * 24 slots for a day, index = wall-clock hours after [dayStart]; null where the watch had no record. The
     * offset is taken on the local clock (not `(hourStart - dayStart) / 1 h`) so a 23- or 25-hour DST-change day
     * still lands each record in its own labelled hour: the repeated DST hour is merged, the skipped one stays
     * empty. Records outside the 24 labelled hours are ignored.
     */
    fun hourSlots(hours: List<StepsHour>, dayStart: Long, zone: ZoneId = ZoneId.systemDefault()): List<StepsHour?> {
        val slots = arrayOfNulls<StepsHour>(24)
        val start = Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDateTime()
        for (h in hours) {
            val local = Instant.ofEpochMilli(h.hourStart).atZone(zone).toLocalDateTime()
            val idx = Duration.between(start, local).toHours().toInt()
            if (idx in 0..23) {
                val prev = slots[idx]
                slots[idx] = if (prev == null) h
                else StepsHour(prev.hourStart, prev.total + h.total, prev.walk + h.walk, prev.run + h.run)
            }
        }
        return slots.toList()
    }

    /** Running total per slot (index i = steps up to and including hour i). */
    fun cumulativeSteps(slots: List<StepsHour?>): IntArray {
        val out = IntArray(slots.size)
        var acc = 0
        for (i in slots.indices) {
            acc += slots[i]?.total ?: 0
            out[i] = acc
        }
        return out
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Cumulative distance along the accepted fixes: time -> metres so far. Uses the tracker's own running total
     * stored on each point ([TrackPoint.cumulativeM]) when every accepted point carries one, so the series is
     * the workout's distance exactly (no hop across a pause, Doppler-credited movement); rows from before that
     * column existed fall back to the haversine sum of the accepted hops.
     */
    fun cumulativeDistance(points: List<TrackPoint>): List<Pt> {
        val acc = points.filter { it.accepted }.sortedBy { it.time }
        if (acc.isNotEmpty() && acc.all { it.cumulativeM != null }) {
            var last = 0.0
            return acc.map { p ->
                last = maxOf(last, p.cumulativeM!!)     // never backwards, whatever the rows hold
                Pt(p.time, last)
            }
        }
        val out = ArrayList<Pt>(acc.size)
        var d = 0.0
        var prev: TrackPoint? = null
        for (p in acc) {
            if (prev != null) d += haversineM(prev.lat, prev.lon, p.lat, p.lon)
            out.add(Pt(p.time, d))
            prev = p
        }
        return out
    }

    /** Times at which each whole kilometre was crossed (linear interpolation); value = km number. */
    fun kmMarkers(cum: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        var nextKm = 1000.0
        for (i in 1 until cum.size) {
            val a = cum[i - 1]
            val b = cum[i]
            while (b.value >= nextKm) {
                val f = if (b.value <= a.value) 1.0 else ((nextKm - a.value) / (b.value - a.value)).coerceIn(0.0, 1.0)
                val t = a.time + ((b.time - a.time) * f).toLong()
                out.add(Pt(t, nextKm / 1000.0))
                nextKm += 1000.0
            }
        }
        return out
    }

    /** Pace (s/km) at each point from a rolling window of [windowMs]; 0 = unknown (too little movement). */
    fun paceSeries(cum: List<Pt>, windowMs: Long = 30_000L, minDistM: Double = 5.0): List<Pt> {
        val out = ArrayList<Pt>(cum.size)
        var j = 0
        for (i in cum.indices) {
            val p = cum[i]
            while (j < i && p.time - cum[j].time > windowMs) j++
            val q = cum[j]
            val dd = p.value - q.value
            val dt = (p.time - q.time) / 1000.0
            val pace = if (dd >= minDistM && dt > 0) dt / (dd / 1000.0) else 0.0
            out.add(Pt(p.time, pace))
        }
        return out
    }

    /** Live pace from the last [windowMs] of the cumulative-distance list; 0 when unknown. */
    fun currentPace(cum: List<Pt>, now: Long, distance: Double, windowMs: Long = 30_000L, minDistM: Double = 5.0): Double {
        var idx = cum.size - 1
        while (idx >= 0 && now - cum[idx].time <= windowMs) idx--
        val q = cum.getOrNull(idx + 1) ?: return 0.0
        val dd = distance - q.value
        val dt = (now - q.time) / 1000.0
        return if (dd >= minDistM && dt > 0) dt / (dd / 1000.0) else 0.0
    }

    /** One summary per day for the last [days] days ending [todayStart], oldest first; zero rows fill the gaps. */
    fun fillDays(summaries: List<DailySummary>, days: Int, todayStart: Long): List<DailySummary> {
        val byDay = HashMap<Long, DailySummary>()
        for (s in summaries) byDay[Fmt.dayStart(s.dayStart)] = s
        return (days - 1 downTo 0).map { back ->
            val d = Fmt.plusDays(todayStart, -back.toLong())
            byDay[d] ?: DailySummary(d, 0, 0, 0, 0.0, null, null, null, null, null)
        }
    }

    fun nearest(points: List<Pt>, time: Long): Pt? = points.minByOrNull { abs(it.time - time) }

    /** A "nice" axis ceiling: max rounded up to 1/2/5 x 10^n. */
    fun niceCeil(max: Double): Double {
        if (max <= 0.0 || max.isNaN()) return 1.0
        val exp = floor(log10(max))
        val base = 10.0.pow(exp)
        val f = max / base
        val nice = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * base
    }

    fun overlapFraction(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Double {
        val len = (aEnd - aStart).toDouble()
        if (len <= 0) return 0.0
        val o = (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).toDouble()
        return (o / len).coerceIn(0.0, 1.0)
    }

    /** Steps taken in a window, from hourly records, pro-rated by overlap with each hour. */
    fun stepsInWindow(hours: List<StepsHour>, from: Long, to: Long): Double =
        hours.sumOf { h -> overlapFraction(h.hourStart, h.hourStart + HOUR_MS, from, to) * h.total }
}

/** Minimal GPX 1.1 writer: one track of the accepted fixes. */
object Gpx {
    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun write(workout: Workout, points: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RyzeWave\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<trk><name>Workout ").append(workout.id).append(' ')
            .append(iso.format(Instant.ofEpochMilli(workout.start))).append("</name><trkseg>\n")
        for (p in points.filter { it.accepted }.sortedBy { it.time }) {
            sb.append("<trkpt lat=\"").append(String.format(Locale.US, "%.7f", p.lat))
                .append("\" lon=\"").append(String.format(Locale.US, "%.7f", p.lon)).append("\">")
            p.altitudeM?.let { sb.append("<ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>") }
            sb.append("<time>").append(iso.format(Instant.ofEpochMilli(p.time))).append("</time>")
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }
}

/** Plot rectangle in pixels plus the data-to-pixel mapping. Pure Kotlin so the maths is testable. */
class ChartFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun x(v: Double): Float = left + (((v - xMin) / (xMax - xMin)) * width).toFloat()
    fun y(v: Double): Float = bottom - (((v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin)) * height).toFloat()
    fun xValue(px: Float): Double = xMin + (px - left) / width * (xMax - xMin)
    fun contains(px: Float, py: Float): Boolean = px in left..right && py in top..bottom

    companion object {
        fun of(
            width: Float, height: Float, gutters: Gutters,
            xMin: Double, xMax: Double, yMin: Double, yMax: Double,
        ): ChartFrame = ChartFrame(
            left = gutters.left,
            top = gutters.top,
            right = (width - gutters.right).coerceAtLeast(gutters.left + 1f),
            bottom = (height - gutters.bottom).coerceAtLeast(gutters.top + 1f),
            xMin = xMin,
            xMax = if (xMax > xMin) xMax else xMin + 1.0,
            yMin = yMin,
            yMax = if (yMax > yMin) yMax else yMin + 1.0,
        )
    }
}

/** Space reserved around the plot for axis labels, in pixels. */
data class Gutters(val left: Float, val right: Float, val top: Float, val bottom: Float)

/** Extends a regular grid upwards (same step) until it covers [dataMax]. */
fun extendGrid(grid: List<Double>, dataMax: Double): List<Double> {
    if (grid.size < 2 || dataMax.isNaN()) return grid
    val step = grid[1] - grid[0]
    if (step <= 0.0) return grid
    val out = ArrayList(grid)
    var guard = 0
    while (out.last() < dataMax && guard++ < 20) out.add(out.last() + step)
    return out
}

/** The point whose x pixel is nearest [px], or null when farther than [maxPx]. */
fun nearestByX(points: List<Pt>, frame: ChartFrame, px: Float, maxPx: Float): Pt? {
    val p = points.minByOrNull { abs(frame.x(it.time.toDouble()) - px) } ?: return null
    return if (abs(frame.x(p.time.toDouble()) - px) <= maxPx) p else null
}

/** One night of sleep summarised from the watch's stage list. Times are epoch ms. */
data class SleepSummary(
    val bedTime: Long?,
    val wakeTime: Long?,
    /** Asleep minutes (deep + light + REM). */
    val totalMin: Int,
    val deepMin: Int,
    val lightMin: Int,
    val remMin: Int,
    val awakeMin: Int,
)

/** Stage codes as delivered by the watch (best current guess; same mapping the exporter uses). */
object SleepMath {
    const val DEEP = 1
    const val LIGHT = 2
    const val REM = 3
    const val AWAKE = 4

    fun stageName(stage: Int): String = when (stage) {
        DEEP -> "Deep"
        LIGHT -> "Light"
        REM -> "REM"
        AWAKE -> "Awake"
        else -> "Stage $stage"
    }

    fun summarize(stages: List<SleepStage>): SleepSummary {
        if (stages.isEmpty()) return SleepSummary(null, null, 0, 0, 0, 0, 0)
        val sorted = stages.sortedBy { it.start }
        var deep = 0
        var light = 0
        var rem = 0
        var awake = 0
        for (s in sorted) {
            when (s.stage) {
                DEEP -> deep += s.minutes
                LIGHT -> light += s.minutes
                REM -> rem += s.minutes
                AWAKE -> awake += s.minutes
                else -> light += s.minutes
            }
        }
        val last = sorted.last()
        return SleepSummary(
            bedTime = sorted.first().start,
            wakeTime = last.start + last.minutes * ChartData.MINUTE_MS,
            totalMin = deep + light + rem,
            deepMin = deep, lightMin = light, remMin = rem, awakeMin = awake,
        )
    }
}

/** An axis-aligned pixel rectangle; the collision maths behind in-chart label placement. Pure Kotlin, unit-tested. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun expand(m: Float): Box = Box(left - m, top - m, right + m, bottom + m)
    fun intersects(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** Liang–Barsky clip test: does the segment (x0, y0)–(x1, y1) touch this box? */
    fun intersectsSegment(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        var t0 = 0f
        var t1 = 1f
        val dx = x1 - x0
        val dy = y1 - y0
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x0 - left, right - x0, y0 - top, bottom - y0)
        for (i in 0..3) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return false          // parallel to this edge and outside it
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
            }
        }
        return true
    }
}

/**
 * Picks where a floating chart label (the "avg 81" / "goal 8k" text) goes so it hides as little as possible: every
 * candidate box is scored by the data it would cover — points within [pointRadius], polyline segments crossing it,
 * filled blocks (bars) it overlaps — plus a heavy penalty for overlapping another label ([avoid]); the first candidate
 * with the lowest score wins, so order the candidates by preference (e.g. right edge first).
 */
object LabelLayout {
    const val AVOID_WEIGHT = 100

    fun pick(
        candidates: List<Box>,
        points: List<Pair<Float, Float>> = emptyList(),
        pointRadius: Float = 0f,
        polylines: List<List<Pair<Float, Float>>> = emptyList(),
        blocks: List<Box> = emptyList(),
        avoid: List<Box> = emptyList(),
    ): Int {
        require(candidates.isNotEmpty())
        var best = 0
        var bestScore = Int.MAX_VALUE
        candidates.forEachIndexed { i, c ->
            val score = score(c, points, pointRadius, polylines, blocks, avoid)
            if (score == 0) return i
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best
    }

    fun score(
        c: Box,
        points: List<Pair<Float, Float>>, pointRadius: Float,
        polylines: List<List<Pair<Float, Float>>>, blocks: List<Box>, avoid: List<Box>,
    ): Int {
        val grown = c.expand(pointRadius)
        var score = points.count { (x, y) -> grown.contains(x, y) }
        for (line in polylines) {
            for (j in 1 until line.size) {
                val (x0, y0) = line[j - 1]
                val (x1, y1) = line[j]
                if (c.intersectsSegment(x0, y0, x1, y1)) score++
            }
        }
        score += blocks.count { c.intersects(it) }
        score += avoid.count { c.intersects(it) } * AVOID_WEIGHT
        return score
    }
}
