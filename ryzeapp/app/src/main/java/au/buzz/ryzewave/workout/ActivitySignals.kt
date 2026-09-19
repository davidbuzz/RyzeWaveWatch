package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SleepStage
import kotlin.math.max

/** The four activity indicators the stuck-in-exercise-mode detector looks at (docs/PLAN.md). */
enum class Indicator { STEPS, GPS, HR, MOTION }

/**
 * What an indicator says over the rolling window. UNKNOWN = nothing was measured in the window (the signal is
 * unavailable: no GPS session, no phone sensor, no watch pushes), which is deliberately distinct from INACTIVE
 * (measured, and there was nothing): a sport whose only expected indicator is UNKNOWN must never be flagged.
 */
enum class SignalState { ACTIVE, INACTIVE, UNKNOWN }

/**
 * Rolling-window reduction of the raw activity observations of one workout to the four [SignalState]s the
 * [StuckModeDetector] decides on. Pure Kotlin, thread-safe (observations arrive from BLE, sensor and controller
 * threads); the times are whatever clock the caller uses consistently (the monitor passes its own).
 *
 *  - **STEPS**: the watch's per-session step counter (from the `FD <type> <hr> … steps24 …` realtime pushes) or
 *    the phone's `TYPE_STEP_COUNTER` tally rose inside the window. Both counters only ever rise, so "active" is
 *    "any sample in the window is above the last value seen before the window (or the first one in it)".
 *  - **GPS**: the tracker's cumulative distance grew by at least [GPS_MIN_PROGRESS_M] inside the window, or a
 *    fix carried a speed of at least [GPS_NOISE_MPS]. Fed once per evaluation from the controller's state.
 *  - **HR**: any heart-rate sample in the window at or above the resting [restingBaselineBpm] + margin.
 *    [Snapshot.hrSleeping] additionally says whether every sample in the window stayed below baseline + the
 *    sleep margin (the night-time urgency modifier).
 *  - **MOTION**: any accelerometer-variance block in the window at or above [MOTION_STILL_THRESHOLD]
 *    (the phone is being carried / moved, not lying on a table).
 *
 * The window is "covered" once [windowMs] has elapsed since [reset]; before that nothing can be judged.
 */
class ActivitySignals(
    val windowMs: Long = StuckModeDetector.WINDOW_MS,
    private val hrMarginBpm: Int = StuckModeDetector.HR_MARGIN_BPM,
    private val sleepMarginBpm: Int = StuckModeDetector.SLEEP_MARGIN_BPM,
    private val gpsNoiseMps: Double = GPS_NOISE_MPS,
    private val gpsMinProgressM: Double = GPS_MIN_PROGRESS_M,
    private val motionStillThreshold: Double = MOTION_STILL_THRESHOLD,
) {
    private data class Count(val time: Long, val value: Long)
    private data class Gps(val time: Long, val distanceM: Double, val speedMps: Double)
    private data class Hr(val time: Long, val bpm: Int)
    private data class Motion(val time: Long, val variance: Double)

    private val lock = Any()
    private val watchSteps = ArrayList<Count>()
    private val phoneSteps = ArrayList<Count>()
    private val gps = ArrayList<Gps>()
    private val hr = ArrayList<Hr>()
    private val motion = ArrayList<Motion>()
    private var start = 0L

    /** Resting heart rate the HR indicator is judged against (see [RestingHrBaseline]). */
    @Volatile
    var restingBaselineBpm: Int = StuckModeDetector.FALLBACK_RESTING_HR_BPM

    /** Start (or restart, after a resume) the window at [now]; every earlier observation is forgotten. */
    fun reset(now: Long) {
        synchronized(lock) {
            start = now
            watchSteps.clear()
            phoneSteps.clear()
            gps.clear()
            hr.clear()
            motion.clear()
        }
    }

    fun onWatchSteps(time: Long, steps: Int) {
        if (steps < 0) return
        synchronized(lock) { watchSteps += Count(time, steps.toLong()) }
    }

    fun onPhoneSteps(time: Long, steps: Int) {
        if (steps < 0) return
        synchronized(lock) { phoneSteps += Count(time, steps.toLong()) }
    }

    /** One GPS observation: the tracker's cumulative distance and the current (Doppler / rolling) speed. */
    fun onGps(time: Long, distanceM: Double, speedMps: Double) {
        if (distanceM.isNaN() || speedMps.isNaN()) return
        synchronized(lock) { gps += Gps(time, distanceM, speedMps) }
    }

    fun onHr(time: Long, bpm: Int) {
        if (bpm <= 0) return
        synchronized(lock) { hr += Hr(time, bpm) }
    }

    /** One accelerometer block: the variance of |a| over the block, in (m/s²)². */
    fun onMotion(time: Long, variance: Double) {
        if (variance.isNaN() || variance < 0.0) return
        synchronized(lock) { motion += Motion(time, variance) }
    }

    data class Snapshot(
        val states: Map<Indicator, SignalState>,
        /** HR was measured in the window and never left the sleeping band (below baseline + sleep margin). */
        val hrSleeping: Boolean,
        /** [windowMs] has elapsed since [reset]: the states describe a full window. */
        val windowCovered: Boolean,
        /** One-line summary for the log. */
        val detail: String,
    ) {
        operator fun get(indicator: Indicator): SignalState = states[indicator] ?: SignalState.UNKNOWN
    }

    fun snapshot(now: Long): Snapshot {
        val from = now - windowMs
        synchronized(lock) {
            pruneCounts(watchSteps, from)
            pruneCounts(phoneSteps, from)
            gps.removeAll { it.time < from - windowMs }
            hr.removeAll { it.time < from }
            motion.removeAll { it.time < from }

            val watch = rising(watchSteps, from)
            val phone = rising(phoneSteps, from)
            val steps = when {
                watch == SignalState.ACTIVE || phone == SignalState.ACTIVE -> SignalState.ACTIVE
                watch == SignalState.INACTIVE || phone == SignalState.INACTIVE -> SignalState.INACTIVE
                else -> SignalState.UNKNOWN
            }

            val gpsIn = gps.filter { it.time >= from }
            val gpsState = if (gpsIn.isEmpty()) {
                SignalState.UNKNOWN
            } else {
                val reference = gps.lastOrNull { it.time < from }?.distanceM ?: gpsIn.first().distanceM
                val moved = gpsIn.any { it.distanceM - reference >= gpsMinProgressM || it.speedMps >= gpsNoiseMps }
                if (moved) SignalState.ACTIVE else SignalState.INACTIVE
            }

            val hrIn = hr.filter { it.time >= from }
            val baseline = restingBaselineBpm
            val hrState = when {
                hrIn.isEmpty() -> SignalState.UNKNOWN
                hrIn.any { it.bpm >= baseline + hrMarginBpm } -> SignalState.ACTIVE
                else -> SignalState.INACTIVE
            }
            val hrMax = hrIn.maxOfOrNull { it.bpm }
            val sleeping = hrMax != null && hrMax < baseline + sleepMarginBpm

            val motionIn = motion.filter { it.time >= from }
            val motionState = when {
                motionIn.isEmpty() -> SignalState.UNKNOWN
                motionIn.any { it.variance >= motionStillThreshold } -> SignalState.ACTIVE
                else -> SignalState.INACTIVE
            }

            val covered = now - start >= windowMs
            val detail = buildString {
                append("steps=").append(steps.tag())
                append("(watch ").append(countSummary(watchSteps, from)).append(", phone ").append(countSummary(phoneSteps, from)).append(')')
                append(" gps=").append(gpsState.tag()).append('(').append(gpsIn.size).append(" fixes)")
                append(" hr=").append(hrState.tag())
                if (hrMax != null) append("(max ").append(hrMax).append(" vs rest ").append(baseline).append(if (sleeping) ", sleeping)" else ")")
                append(" motion=").append(motionState.tag())
                motionIn.maxOfOrNull { it.variance }?.let { append("(max var %.3f)".format(java.util.Locale.ROOT, it)) }
                append(" window=").append(if (covered) "covered" else "${(now - start) / 1000}s/${windowMs / 1000}s")
            }
            return Snapshot(
                states = mapOf(Indicator.STEPS to steps, Indicator.GPS to gpsState, Indicator.HR to hrState, Indicator.MOTION to motionState),
                hrSleeping = sleeping,
                windowCovered = covered,
                detail = detail,
            )
        }
    }

    /** Keeps the last sample before [from] (the reference the window's rise is judged against) and everything after. */
    private fun pruneCounts(list: ArrayList<Count>, from: Long) {
        var lastBefore = -1
        for (i in list.indices) if (list[i].time < from) lastBefore = i
        if (lastBefore > 0) list.subList(0, lastBefore).clear()
    }

    private fun rising(list: List<Count>, from: Long): SignalState {
        val inWindow = list.filter { it.time >= from }
        if (inWindow.isEmpty()) return SignalState.UNKNOWN
        val reference = list.lastOrNull { it.time < from }?.value ?: inWindow.first().value
        return if (inWindow.any { it.value > reference }) SignalState.ACTIVE else SignalState.INACTIVE
    }

    private fun countSummary(list: List<Count>, from: Long): String {
        val inWindow = list.filter { it.time >= from }
        if (inWindow.isEmpty()) return "-"
        val reference = list.lastOrNull { it.time < from }?.value ?: inWindow.first().value
        return "$reference→${inWindow.maxOf { it.value }}"
    }

    private fun SignalState.tag(): String = when (this) {
        SignalState.ACTIVE -> "ACTIVE"
        SignalState.INACTIVE -> "none"
        SignalState.UNKNOWN -> "?"
    }

    companion object {
        /** Doppler speed at or above this (2.5 km/h) is real movement, below is receiver noise. */
        const val GPS_NOISE_MPS = 0.7

        /** The tracker's cumulative distance must grow by at least this inside the window to count as moving. */
        const val GPS_MIN_PROGRESS_M = 10.0

        /**
         * Variance of |a| (m/s²)² over a ~5 s block: a phone on a table or bed measures well under 0.01, one in
         * the pocket of someone holding a yoga pose or shifting in a chair around 0.05–0.3, walking several.
         */
        const val MOTION_STILL_THRESHOLD = 0.15
    }
}

/**
 * The wearer's resting heart rate from the last day of periodic samples (the watch's automatic 10-minute
 * measurements and synced history — never the 1 Hz live / workout streams, which are the very spikes the HR
 * indicator looks for): the 10th percentile, clamped to a plausible band, or [FALLBACK] when there are too few.
 *
 * Since 2026-09-19 the day is split at the sleep record: [daytime] is the 10th percentile of the samples taken
 * outside the night (the "daytime RHR" column of [FitnessBand]'s table) and [sleeping] is the median of the samples
 * taken while actually asleep (the "sleeping HR" column). With no sleep record the whole day counts, which is [of].
 * A periodic reading is stored twice (the watch's live push, then the same value again from the synced history),
 * so samples are counted once per timestamp.
 */
object RestingHrBaseline {
    const val LOOKBACK_MS = 24 * 3600_000L
    const val PERCENTILE = 0.10
    const val MIN_SAMPLES = 6
    const val MIN_BPM = 40
    const val MAX_BPM = 90
    /** Sleep stages closer together than this are one night (a break the watch logs as a gap). */
    const val SPAN_GAP_MS = 60 * 60_000L
    const val FALLBACK = StuckModeDetector.FALLBACK_RESTING_HR_BPM

    fun of(samples: List<HrSample>, fallback: Int = FALLBACK): Int {
        val periodic = periodic(samples)
        if (periodic.size < MIN_SAMPLES) return fallback
        val index = (PERCENTILE * (periodic.size - 1)).toInt().coerceIn(0, periodic.size - 1)
        return periodic[index].coerceIn(MIN_BPM, MAX_BPM)
    }

    /** True when there are at least [MIN_SAMPLES] distinct periodic readings. */
    fun hasEnough(samples: List<HrSample>): Boolean = periodic(samples).size >= MIN_SAMPLES

    /** The daytime resting rate: [of] over the samples outside the [night] spans; the whole day when that leaves too few. */
    fun daytime(samples: List<HrSample>, night: List<LongRange>, fallback: Int = FALLBACK): Int {
        val awake = samples.filter { s -> night.none { s.time in it } }
        return if (hasEnough(awake)) of(awake, fallback) else of(samples, fallback)
    }

    /** The sleeping rate: the median of the periodic samples inside the [asleep] spans, or null with fewer than [MIN_SAMPLES]. */
    fun sleeping(samples: List<HrSample>, asleep: List<LongRange>): Int? {
        val inBed = periodic(samples.filter { s -> asleep.any { s.time in it } })
        if (inBed.size < MIN_SAMPLES) return null
        return inBed[inBed.size / 2]
    }

    /**
     * The time covered by [stages] (each runs from its start for its minutes) as contiguous spans, bridging gaps
     * up to [SPAN_GAP_MS]. With [includeAwake] false the awake stages are cut out, leaving the time actually asleep.
     */
    fun spans(stages: List<SleepStage>, includeAwake: Boolean): List<LongRange> {
        val merged = ArrayList<LongRange>()
        for (st in stages.sortedBy { it.start }) {
            val end = st.start + st.minutes * 60_000L
            val last = merged.lastOrNull()
            if (last != null && st.start <= last.last + SPAN_GAP_MS) merged[merged.size - 1] = last.first..maxOf(last.last, end)
            else merged += st.start..end
        }
        if (includeAwake) return merged
        val awake = stages.filter { it.stage == SleepStage.AWAKE }.map { it.start..(it.start + it.minutes * 60_000L) }.sortedBy { it.first }
        return merged.flatMap { span ->
            val pieces = ArrayList<LongRange>()
            var from = span.first
            for (a in awake) {
                if (a.last <= from || a.first >= span.last) continue
                if (a.first > from) pieces += from..a.first
                from = maxOf(from, a.last)
            }
            if (from < span.last) pieces += from..span.last
            pieces
        }
    }

    /** Sorted bpm of the periodic samples, one per timestamp. */
    private fun periodic(samples: List<HrSample>): List<Int> =
        samples.filter { it.bpm > 0 && (it.source == SampleSource.AUTO || it.source == SampleSource.HISTORY) }
            .distinctBy { it.time }
            .map { it.bpm }
            .sorted()
}

/**
 * Fitness level by resting heart rate, the table Buzz supplied on 2026-09-19. The daytime resting rate and the
 * night-time sleeping rate are judged against different columns:
 *
 * | Level                    | Daytime RHR | Sleeping HR |
 * |--------------------------|-------------|-------------|
 * | Out of shape / sedentary | 75–100      | 60–80+      |
 * | Average / healthy        | 60–75       | 50–60       |
 * | Fit / active             | 50–60       | 45–50       |
 * | Athletic / elite         | 40–50       | 35–45       |
 *
 * A value on a shared edge belongs to the band it starts (75 by day is sedentary, 60 is average, 50 is fit);
 * above the top or below the bottom of the table takes the nearest band. A label, not a diagnosis.
 */
enum class FitnessBand(val label: String) {
    SEDENTARY("sedentary"), AVERAGE("average"), FIT("fit"), ATHLETIC("athletic");

    companion object {
        fun ofDaytime(bpm: Int): FitnessBand = when {
            bpm >= 75 -> SEDENTARY
            bpm >= 60 -> AVERAGE
            bpm >= 50 -> FIT
            else -> ATHLETIC
        }

        fun ofSleeping(bpm: Int): FitnessBand = when {
            bpm >= 60 -> SEDENTARY
            bpm >= 50 -> AVERAGE
            bpm >= 45 -> FIT
            else -> ATHLETIC
        }
    }
}

/**
 * Variance of the accelerometer magnitude over fixed blocks of [blockSize] samples: [add] returns the block's
 * variance when a block completes, null otherwise. Pure so the still / moving thresholds are unit-tested; the
 * Android sampler feeds it |a| at a few Hz.
 */
class MotionVariance(private val blockSize: Int = 20) {
    private var n = 0
    private var mean = 0.0
    private var m2 = 0.0

    init {
        require(blockSize >= 2) { "blockSize must be at least 2" }
    }

    fun add(magnitude: Double): Double? {
        if (magnitude.isNaN()) return null
        n++
        val delta = magnitude - mean
        mean += delta / n
        m2 += delta * (magnitude - mean)
        if (n < blockSize) return null
        val variance = max(m2 / (n - 1), 0.0)
        n = 0
        mean = 0.0
        m2 = 0.0
        return variance
    }
}
