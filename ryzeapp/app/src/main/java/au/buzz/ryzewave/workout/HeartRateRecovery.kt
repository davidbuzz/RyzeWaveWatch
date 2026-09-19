package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.TrackPoint
import kotlin.math.exp

/**
 * Heart-rate recovery: `HRR = peak exercise heart rate − heart rate one minute later` (and two minutes later), the
 * form Buzz specified (Cleveland Clinic). "Later" is measured from the end of the effort bout, which is when
 * recovery starts; the peak is the highest rate seen during that bout. The fall is driven by the parasympathetic system switching back on, and a slow fall is one of the better
 * studied markers of cardiovascular risk (Cole et al., NEJM 1999: a one-minute drop of 12 bpm or less was the
 * abnormal band in that cohort). This app reports the numbers; it does not interpret them.
 *
 * "The effort ends" is the last moment the wearer was moving at exercise pace, not the moment the Stop button
 * was pressed, because people stop running and then walk about before stopping the watch — and that walk-about
 * is exactly the recovery window. It needs the heart-rate stream to keep coming for two minutes after the last
 * stride; when the workout was stopped sooner, the later figure is null.
 *
 * Pure Kotlin; the same fixtures as [HrEstimator] exercise it.
 */
object HeartRateRecovery {
    data class Recovery(
        /** When the last exercise bout ended. */
        val effortEnd: Long,
        /** Median heart rate over the last 15 s of effort, for reference. */
        val hrAtEnd: Int,
        /** Peak heart rate during the effort: the baseline the drops are measured from (HRR = peak − rate later). */
        val peakHr: Int,
        /** Peak minus the rate one minute after the effort ended, or null when there are no samples around +60 s. */
        val drop1min: Int?,
        /** Peak minus the rate two minutes after, or null. */
        val drop2min: Int?,
    )

    private const val MOVING_MPS = HrEstimator.MOVING_MPS
    private const val SPEED_TAU_S = HrEstimator.SPEED_TAU_S
    private const val END_WINDOW_MS = 15_000L
    private const val SUSTAINED_MS = 180_000L
    /** A dip in movement shorter than this is part of the same bout; a standstill this long ends it. */
    private const val MERGE_GAP_MS = 120_000L
    private const val PROBE_HALF_MS = 10_000L

    fun of(points: List<TrackPoint>, samples: List<HrSample>): Recovery? {
        val fixes = points.filter { !it.paused && it.accepted }.sortedBy { it.time }
        if (fixes.size < 10) return null
        // The end of the last exercise *bout*. Moving stretches separated by a dip shorter than [MERGE_GAP_MS] are
        // one bout (a traffic light, a short walk break: 2026-09-18's run had one and the rate kept rising after
        // the dip), while a standstill of [MERGE_GAP_MS] or more ends it - so the brisk one-minute walk to the door
        // three and a half minutes after 2026-09-19's run is not the effort, and neither is a noisy fix while
        // standing about. A bout must last [SUSTAINED_MS] in total to count.
        var sm = 0.0
        var last = fixes.first().time
        var boutStart = 0L
        var boutEnd = 0L
        var lastMovingAt = 0L
        var effortEnd = 0L
        for (f in fixes) {
            val gapS = (f.time - last) / 1000.0
            // No fixes for a while means the phone was sitting still (Android stops reporting when stationary).
            if (gapS * 1000.0 > HrEstimator.NO_FIX_MS) sm = 0.0
            val dt = gapS.coerceIn(0.0, HrEstimator.MAX_FIX_GAP_S)
            sm = if (f === fixes.first()) f.speedMps.toDouble() else sm + (1 - exp(-dt / SPEED_TAU_S)) * (f.speedMps - sm)
            last = f.time
            if (sm >= MOVING_MPS) {
                if (boutStart == 0L || f.time - lastMovingAt >= MERGE_GAP_MS) {
                    // a new bout: close the previous one first
                    if (boutStart != 0L && boutEnd - boutStart >= SUSTAINED_MS) effortEnd = boutEnd
                    boutStart = f.time
                }
                boutEnd = f.time
                lastMovingAt = f.time
            }
        }
        if (boutStart != 0L && boutEnd - boutStart >= SUSTAINED_MS) effortEnd = boutEnd
        if (effortEnd == 0L) return null
        val hr = samples.filter { it.bpm > 0 }.sortedBy { it.time }
        val atEnd = median(hr.filter { it.time in (effortEnd - END_WINDOW_MS)..effortEnd }.map { it.bpm }) ?: return null
        fun probe(offsetMs: Long): Int? =
            median(hr.filter { it.time in (effortEnd + offsetMs - PROBE_HALF_MS)..(effortEnd + offsetMs + PROBE_HALF_MS) }.map { it.bpm })
        val at1 = probe(60_000L)
        val at2 = probe(120_000L)
        val peak = hr.filter { it.time <= effortEnd }.maxOfOrNull { it.bpm } ?: atEnd
        return Recovery(effortEnd, atEnd, peak, at1?.let { peak - it }, at2?.let { peak - it })
    }

    /**
     * The usual one-minute bands (Cleveland Clinic): 22 and over excellent, 13-21 normal, 12 and under delayed.
     * A label, not a diagnosis; the app is not a medical device.
     */
    fun band1min(drop: Int): String = when {
        drop >= 22 -> "excellent"
        drop >= 13 -> "normal"
        else -> "delayed"
    }

    /** "-38 bpm" for a fall; a rise (no recovery, or the effort had not really ended) reads "+3 bpm", never "--3". */
    fun dropText(drop: Int): String = if (drop >= 0) "-$drop bpm" else "+${-drop} bpm"

    private fun median(xs: List<Int>): Int? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return s[s.size / 2]
    }
}
