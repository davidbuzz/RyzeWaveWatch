package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.protocol.SportTypes
import kotlin.math.sqrt

/** How a window of a workout was moving. */
enum class Gait { WALK, RUN, UNKNOWN }

/** What a sport is allowed to teach the stride model. */
enum class SportGait {
    /** Steps mean nothing here (cycling, rowing, swimming, yoga): never calibrate. */
    NONE,

    /** A walking sport: every usable window calibrates the walking stride, whatever the classifier thinks. */
    WALK_ONLY,

    /** Running and mixed sports: windows are classified individually, because a run contains walking. */
    ANY,
}

/**
 * One fixed-length slice of a workout with everything needed to judge how it was moving.
 * [strideM] is the measured stride for the slice: GPS distance divided by the steps taken in it.
 */
data class StrideWindow(
    val startTime: Long,
    val durationSec: Double,
    val distanceM: Double,
    val steps: Int,
    val speedMps: Double,
    val cadenceSpm: Double,
    val strideM: Double,
    val gait: Gait,
)

/** A stride derived from the windows of one gait. */
data class BandCalibration(
    val gait: Gait,
    val strideM: Double,
    val distanceM: Double,
    val steps: Int,
    val windows: Int,
)

/**
 * What a calibration produced. Either band may be null: a workout can teach one stride, both, or neither.
 * [message] is the sentence shown to the user; [notes] explains anything that was measured but not saved.
 */
data class CalibrationOutcome(
    val walk: BandCalibration?,
    val run: BandCalibration?,
    val message: String,
    val notes: List<String> = emptyList(),
) {
    val changedAnything: Boolean get() = walk != null || run != null
}

/**
 * Stride calibration from one GPS workout, split by how the wearer was actually moving.
 *
 * The old calibration divided the whole workout's distance by its whole step count and filed the answer under
 * "walking" or "running" according to the *average* speed. On a session that mixes both — sprint, amble, sprint —
 * the average lands between the two real gaits, so the number describes neither, and on a deliberately varied run
 * it was filed as walking (Buzz's 2026-09-06 run: 772 m / 874 steps = 0.883 m stored as a walking stride, when the
 * walking parts alone measured 0.699 and the running parts 0.958).
 *
 * This version slices the workout into [WINDOW_MS] windows, measures each one, classifies it, and derives a stride
 * per gait from the windows that agree. Ambiguous windows are dropped rather than guessed at.
 *
 * **Classifying without assuming an athlete.** Absolute speed is useless across body sizes and fitness: a slow
 * jogger runs below the speed a tall person walks at. The two signals that survive are body-relative:
 *
 *  - **stride ÷ height.** Walking sits near 0.41 of height and running near 0.55 (docs/PROTOCOL.md §9). A stride
 *    at or beyond [RUN_RATIO] cannot be walking, because a walking step is bounded by leg length.
 *  - **cadence.** Ordinary walking stays under [WALK_CADENCE]; sustained cadence above [RUN_CADENCE] is running
 *    even when the stride is short and the speed low, which is exactly the slow jogger a stride test would
 *    otherwise misfile as a walk.
 *
 * Each covers the other's failure: a brisk walker has a high cadence but a short stride, a slow jogger a short
 * stride but a high cadence.
 *
 * **Speed is deliberately weak here.** It is only a floor, to stop a near-stationary window being called running
 * ([walkRunSpeedMps], the Froude-number walk-run transition for the wearer's leg length). It is not a decider,
 * because how fast a given person runs is exactly what varies with age and fitness, and we have measured only one
 * body so far: on that run, moving the speed threshold from 2.18 to 2.0 m/s changed nothing at all, since the gait
 * signals had already decided every window. Any height-to-speed table would be invention until there is data from
 * more than one wearer.
 *
 * **Provenance of the numbers.** [RUN_RATIO] sits between the walking and running stride factors in
 * docs/PROTOCOL.md §9;
 * [RUN_CADENCE] and [WALK_CADENCE] are the usual gait-literature bands. They were checked against one real
 * workout (2026-09-06: 26 windows, 11 running, 11 walking, 4 too ambiguous to call), which recovered 0.927 m
 * running and 0.660 m walking against the 0.994 / 0.746 that height alone predicts. One subject is not a
 * validation set; treat the thresholds as provisional and revisit them as more workouts are recorded.
 *
 * **The sport the user chose gates the result** ([sportGait]). A walking sport can only ever produce a walking
 * stride; cycling, rowing and swimming produce nothing at all, because their step counts are meaningless.
 *
 * Pure Kotlin: no Android, no database, unit-tested against a real recorded run.
 */
object StrideCalibration {

    /** Window length. Long enough for the watch's step pushes to average out, short enough to catch a sprint. */
    const val WINDOW_MS = 15_000L

    /** Stride ÷ height at or above this is running: a walking step cannot be this long. */
    const val RUN_RATIO = 0.50

    /** Steps per minute at or above this is running even when the stride is short (the slow jogger). */
    const val RUN_CADENCE = 155.0

    /** Steps per minute at or below this is walking-shaped. */
    const val WALK_CADENCE = 140.0

    /** A window must cover at least this to be measured at all. */
    const val MIN_WINDOW_M = 5.0
    const val MIN_WINDOW_STEPS = 8

    /** A band needs this much evidence before it is allowed to change a setting. */
    const val MIN_BAND_M = 100.0
    const val MIN_BAND_STEPS = 120
    const val MIN_BAND_WINDOWS = 3

    /** A calibrated stride further than this from the height-derived value is rejected as implausible. */
    const val MAX_RATIO_TO_DEFAULT = 1.35
    const val MIN_RATIO_TO_DEFAULT = 0.65

    /** Fraction of standing height taken as leg length, for the walk-run transition speed. */
    private const val LEG_FRACTION = 0.53
    private const val GRAVITY = 9.81

    /** Froude number of the walk-run transition (~0.5): people stop walking near this. */
    private const val FROUDE_TRANSITION = 0.5

    /** Sports whose step counts say nothing about stride. */
    private val NO_STRIDE = setOf(
        0x02, // Cycling
        0x04, // Swimming
        0x12, // Spinning
        0x13, // Yoga
        0x17, // Boating
        0x1E, // Horse Riding
        0x1F, // Elliptical
        0x25, // Skiing
        0x29, // Rower
        0x35, // Archery
        0x44, // Snowboarding
        0x48, // Fishing
        0x4D, // Downhill Skiing
        0x4E, // Snow Sports
        0x51, // Skating
        0x62, // Shooting
        0x65, // Skateboarding
        0x6A, // Surfing
        0x6B, // Snorkeling
        0x6F, // Rock Climbing
        0x71, // Bungee Jumping
    )

    /** Sports that are walking by definition, however fast the wearer gets down a hill. */
    private val WALKING_SPORTS = setOf(
        0x08, // Hiking
        0x09, // Walking
        0x23, // Outdoor Walking
        0x4B, // Golf
    )

    /** What [sportType] is allowed to teach. An unknown or absent sport is treated leniently as [SportGait.ANY]. */
    fun sportGait(sportType: Int?): SportGait = when (sportType) {
        null -> SportGait.ANY
        in NO_STRIDE -> SportGait.NONE
        in WALKING_SPORTS -> SportGait.WALK_ONLY
        else -> SportGait.ANY
    }

    /**
     * Speed of the walk-run transition for this body: `sqrt(Fr · g · legLength)`. Around 2.2 m/s for a tall adult
     * and 2.0 for a short one, so it is a weak signal — used only to veto, never to decide.
     */
    fun walkRunSpeedMps(profile: UserProfile): Double =
        sqrt(FROUDE_TRANSITION * GRAVITY * LEG_FRACTION * profile.heightCm / 100.0)

    /**
     * How a single window was moving. Returns [Gait.UNKNOWN] whenever the signals disagree, so an ambiguous slice
     * is dropped instead of polluting a band.
     */
    fun classify(strideM: Double, cadenceSpm: Double, speedMps: Double, profile: UserProfile): Gait {
        val heightM = profile.heightCm / 100.0
        if (heightM <= 0.0) return Gait.UNKNOWN
        val ratio = strideM / heightM
        val transition = walkRunSpeedMps(profile)

        // Running: either the step is too long to be a walk, or the cadence is too high to be one.
        val longStride = ratio >= RUN_RATIO
        val fastFeet = cadenceSpm >= RUN_CADENCE
        // A tall person strolling downhill can stretch a step; require some pace before calling it running.
        val movingEnough = speedMps >= transition * 0.55
        if ((longStride || fastFeet) && movingEnough) return Gait.RUN

        // Walking: the step is not running-long, the feet are unhurried, and the pace is below the transition.
        // Cadence decides here just as it does for running - nobody runs at 120 steps a minute - so a brisk
        // walker with a long step is still called a walk rather than thrown away.
        if (ratio < RUN_RATIO && cadenceSpm <= WALK_CADENCE && speedMps < transition) return Gait.WALK

        return Gait.UNKNOWN
    }

    /**
     * Slice [points] into windows. Points must be time-ordered; paused ones and those without a step count or a
     * cumulative distance are skipped, and a window is only produced when both counters actually moved.
     */
    fun windows(points: List<TrackPoint>, profile: UserProfile, windowMs: Long = WINDOW_MS): List<StrideWindow> {
        val usable = points.filter { !it.paused && it.steps != null && it.cumulativeM != null }.sortedBy { it.time }
        if (usable.size < 2) return emptyList()
        val out = ArrayList<StrideWindow>()
        var i = 0
        while (i < usable.size - 1) {
            val start = usable[i]
            val limit = start.time + windowMs
            var j = i
            while (j + 1 < usable.size && usable[j + 1].time <= limit) j++
            if (j == i) { i++; continue }
            val end = usable[j]
            val dt = (end.time - start.time) / 1000.0
            val dd = (end.cumulativeM ?: 0.0) - (start.cumulativeM ?: 0.0)
            val ds = (end.steps ?: 0) - (start.steps ?: 0)
            if (dt > 0.0 && dd >= MIN_WINDOW_M && ds >= MIN_WINDOW_STEPS) {
                val stride = dd / ds
                val cadence = ds * 60.0 / dt
                val speed = dd / dt
                out += StrideWindow(
                    startTime = start.time, durationSec = dt, distanceM = dd, steps = ds,
                    speedMps = speed, cadenceSpm = cadence, strideM = stride,
                    gait = classify(stride, cadence, speed, profile),
                )
            }
            i = j
        }
        return out
    }

    /**
     * Calibrate from one workout's track. [sportType] gates the result, [profile] supplies the body the
     * thresholds and the plausibility bounds are relative to.
     */
    fun calibrate(points: List<TrackPoint>, profile: UserProfile, sportType: Int?): CalibrationOutcome {
        val gate = sportGait(sportType)
        val sportName = sportType?.let { SportTypes.name(it) } ?: "this workout"
        if (gate == SportGait.NONE) {
            return CalibrationOutcome(null, null, "$sportName does not measure strides, so nothing was changed")
        }
        val all = windows(points, profile)
        if (all.isEmpty()) {
            return CalibrationOutcome(
                null, null,
                "Not enough of this workout has both GPS distance and step counts to measure a stride",
            )
        }

        // A walking sport files every usable window as walking, whatever the gait signals say.
        val classified = if (gate == SportGait.WALK_ONLY) {
            all.map { if (it.gait == Gait.UNKNOWN) it else it.copy(gait = Gait.WALK) }
        } else {
            all
        }

        val notes = ArrayList<String>()
        val walk = band(classified, Gait.WALK, profile, notes)
        val run = if (gate == SportGait.WALK_ONLY) null else band(classified, Gait.RUN, profile, notes)
        val skipped = classified.count { it.gait == Gait.UNKNOWN }
        if (skipped > 0) notes += "$skipped of ${classified.size} windows were between gaits and were ignored"

        val parts = listOfNotNull(
            walk?.let { "walking ${fmt(it.strideM)} m/step from ${it.distanceM.toInt()} m / ${it.steps} steps" },
            run?.let { "running ${fmt(it.strideM)} m/step from ${it.distanceM.toInt()} m / ${it.steps} steps" },
        )
        val message = if (parts.isEmpty()) {
            "No part of this $sportName was steady enough to calibrate a stride"
        } else {
            "Calibrated " + parts.joinToString("; ")
        }
        return CalibrationOutcome(walk, run, message, notes)
    }

    private fun band(
        windows: List<StrideWindow>,
        gait: Gait,
        profile: UserProfile,
        notes: MutableList<String>,
    ): BandCalibration? {
        val w = windows.filter { it.gait == gait }
        if (w.isEmpty()) return null
        val distance = w.sumOf { it.distanceM }
        val steps = w.sumOf { it.steps }
        val label = if (gait == Gait.RUN) "running" else "walking"
        if (w.size < MIN_BAND_WINDOWS || distance < MIN_BAND_M || steps < MIN_BAND_STEPS) {
            notes += "too little $label to calibrate (${distance.toInt()} m, $steps steps in ${w.size} windows)"
            return null
        }
        val stride = distance / steps
        val default = if (gait == Gait.RUN) {
            DefaultStrideModel.defaultRunStrideM(profile)
        } else {
            DefaultStrideModel.defaultWalkStrideM(profile)
        }
        if (stride < DefaultStrideModel.MIN_STRIDE_M || stride > DefaultStrideModel.MAX_STRIDE_M ||
            stride < default * MIN_RATIO_TO_DEFAULT || stride > default * MAX_RATIO_TO_DEFAULT
        ) {
            notes += "measured $label stride ${fmt(stride)} m is too far from the ${fmt(default)} m your height " +
                "implies, so it was not saved"
            return null
        }
        return BandCalibration(gait, stride, distance, steps, w.size)
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}
