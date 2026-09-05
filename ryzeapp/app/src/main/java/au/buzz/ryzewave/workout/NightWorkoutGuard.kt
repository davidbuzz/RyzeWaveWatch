package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import java.time.Instant
import java.time.ZoneId

/**
 * Pure decision: is a workout the *watch* just started likely an accidental overnight touch rather than a real
 * session? No Android imports; unit-tested (see [NightWorkoutGuardController] for the wiring).
 *
 * The wearer bumps the watch in their sleep, it silently enters an exercise mode, and that blocks sleep
 * detection for the rest of the night (captures/pixel_sleep_20260906). We flag a watch-started workout as
 * *likely accidental* when all three hold:
 *
 *  1. it started at night — local hour in [NIGHT_START_HOUR, 24) ∪ [0, NIGHT_END_HOUR), i.e. 22:00–05:59;
 *  2. the recent resting heart rate is below [RESTING_HR_BPM] (a real workout raises HR within a minute);
 *  3. there is no GPS movement in the first ~60 s (a real outdoor session moves).
 *
 * The rate is deliberately conservative: an unknown HR (null) is *not* treated as low, so we never auto-stop a
 * session we cannot vouch for.
 */
object NightWorkoutGuard {

    /** Night window (local wall-clock hours): start inclusive, end exclusive. 22:00 … 06:00. */
    const val NIGHT_START_HOUR = 22
    const val NIGHT_END_HOUR = 6

    /** Recent resting HR strictly below this counts as "still asleep". */
    const val RESTING_HR_BPM = 75

    /** How many of the most recent periodic samples the resting average is taken over. */
    const val RECENT_HR_SAMPLES = 5

    data class Inputs(
        /** Local start time of the workout (epoch ms), as reported by the watch. */
        val startTime: Long,
        /** Average of the last few periodic HR samples, or null when none are known. */
        val recentRestingHr: Int?,
        /** True when GPS shows real movement in the first ~60 s of the session. */
        val gpsMovement: Boolean,
    )

    data class Decision(val accidental: Boolean, val reason: String)

    /** True when [startTime]'s local hour falls in the night window. */
    fun isNight(startTime: Long, zone: ZoneId): Boolean {
        val hour = Instant.ofEpochMilli(startTime).atZone(zone).hour
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    /**
     * Resting HR = the average of the last [RECENT_HR_SAMPLES] periodic samples (auto pushes, falling back to
     * the history series when there are no auto ones); null when there are none. The 1 Hz live / workout streams
     * are ignored — they would be the very spike we are trying to detect.
     */
    fun restingHr(samples: List<HrSample>): Int? {
        val periodic = samples.filter { it.bpm > 0 && it.source == SampleSource.AUTO }
            .ifEmpty { samples.filter { it.bpm > 0 && it.source == SampleSource.HISTORY } }
            .sortedBy { it.time }
        val recent = periodic.takeLast(RECENT_HR_SAMPLES)
        return if (recent.isEmpty()) null else recent.sumOf { it.bpm } / recent.size
    }

    fun evaluate(inputs: Inputs, zone: ZoneId, restingThreshold: Int = RESTING_HR_BPM): Decision {
        val night = isNight(inputs.startTime, zone)
        val hr = inputs.recentRestingHr
        val hrLow = hr != null && hr < restingThreshold
        val still = !inputs.gpsMovement
        val accidental = night && hrLow && still
        val reason = buildString {
            append(if (night) "night" else "daytime")
            append(", hr=").append(hr?.toString() ?: "?").append(if (hrLow) "<$restingThreshold" else ">=$restingThreshold")
            append(if (still) ", still" else ", moving")
        }
        return Decision(accidental, reason)
    }

    fun likelyAccidental(
        startTime: Long,
        zone: ZoneId,
        recentRestingHr: Int?,
        gpsMovement: Boolean,
        restingThreshold: Int = RESTING_HR_BPM,
    ): Boolean = evaluate(Inputs(startTime, recentRestingHr, gpsMovement), zone, restingThreshold).accidental
}
