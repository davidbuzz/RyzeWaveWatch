package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.TrackPoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A physiological heart-rate estimator that runs beside the watch and says when the watch is wrong.
 *
 * **Why.** The optical sensor loses the wrist now and then. On 2026-09-19 it read 95-113 for five and a half
 * minutes in the middle of a run at an unchanged pace, between stretches of 140-160. Nothing in the watch's
 * packet marks a bad reading: the number simply arrives and looks like any other. The only way to know is to
 * predict what the heart should be doing from the effort, and notice when the watch disagrees for too long.
 *
 * **What it is.** An extended Kalman filter over the state `[hr, offset]`:
 *
 *  - `hr` is the true heart rate. Its dynamics are first-order: it chases a *target* set by the effort, rising with
 *    time constant [TAU_UP_S] and falling with [TAU_DOWN_S] (recovery is slower than onset).
 *  - `offset` is a slowly varying personal shift of the target, learned during the session, so a hot day, a cold,
 *    or a fitter month do not need refitting. It is the "extended" part: the target is nonlinear in the state
 *    through the floor and the ceiling below, so the Jacobian is linearised each step.
 *
 * The target itself comes from the wearer's own history. Nine GPS runs (2026-09-07 to -19, 14 263 samples,
 * leave-one-run-out) fitted `HR = 134.0 + 7.0 × speed(m/s) + 19.3 × climb(m/s)` with a residual of 10.8 bpm.
 * The speed term is small on purpose: over this wearer's pace range the heart sits near 145 regardless, and the
 * filter's power comes from the dynamics and the gate, not the regression. Below the walking threshold the target
 * ramps down to the resting rate ([restingBpm], from the last day's periodic samples), so a stop is predicted as a
 * fall to rest rather than as a fault.
 *
 * **The gate.** Each watch reading is tested against the prediction's innovation covariance; one further than
 * [GATE_SIGMA] standard deviations away is rejected, and the filter coasts on physiology instead. A reading below
 * the wearer's exercising floor ([floorBpm]; "physically impossible for my heart rate to get under 125 while
 * exercising") is rejected outright while moving. Rejections for longer than [FLAG_MS] make the reading
 * *flagged*, and [Verdict.wrongByPercent] says by how much.
 *
 * **The ramps are protected on purpose.** The climb at the start and the fall after the last stride are the parts
 * of the trace that describe recovery and fitness, and they must never be "corrected". Two rules do that: the
 * floor only applies once the session is *warmed up* (an accepted reading at or above the floor for
 * [WARMUP_MS]), and once movement stops the target ramps to rest with no floor at all. On the nine reference runs
 * this flags nothing during any warm-up or cool-down, and only the 2026-09-19 dropout.
 *
 * Pure Kotlin, no Android: unit-tested by replaying real runs ([replay]).
 */
class HrEstimator(
    private val restingBpm: Int,
    private val floorBpm: Int = FLOOR_BPM,
    private val hrMaxBpm: Int = HR_MAX_BPM,
    private val targetIntercept: Double = TARGET_INTERCEPT,
    private val targetPerMps: Double = TARGET_PER_MPS,
    private val targetPerClimbMps: Double = TARGET_PER_CLIMB_MPS,
) {
    /** The estimator's view of one watch reading. */
    data class Verdict(
        /** Best belief of the true heart rate right now. */
        val estimate: Int,
        /** The watch's reading passed the gate and updated the filter. */
        val accepted: Boolean,
        /** Readings have been rejected for at least [FLAG_MS]: the watch is judged wrong. */
        val flagged: Boolean,
        /** The session has warmed up, so the exercising floor is in force. */
        val warmedUp: Boolean,
        /** The wearer is moving at exercise pace (smoothed speed at or above [MOVING_MPS]). */
        val moving: Boolean,
        /** How far the watch is from the estimate, as a percentage of the estimate; positive = watch reads high. */
        val wrongByPercent: Double,
    )

    // filter state
    private var hr = 0.0
    private var offset = 0.0
    private var p00 = 100.0
    private var p01 = 0.0
    private var p11 = 25.0
    private var started = false
    private var lastHrTime = 0L

    // effort inputs
    private var smoothedSpeed = 0.0
    private var haveSpeed = false
    private var lastFixTime = 0L
    private var climbRate = 0.0
    private val altitudes = ArrayDeque<Pair<Long, Double>>()

    // gating
    private var rejectedSince = 0L
    private var warmedUp = false
    private var atFloorSince = 0L

    val estimate: Int get() = hr.roundToInt()
    val isWarmedUp: Boolean get() = warmedUp
    val isMoving: Boolean get() = smoothedSpeed >= MOVING_MPS

    fun reset() {
        hr = 0.0; offset = 0.0; p00 = 100.0; p01 = 0.0; p11 = 25.0; started = false; lastHrTime = 0L
        smoothedSpeed = 0.0; haveSpeed = false; lastFixTime = 0L; climbRate = 0.0; altitudes.clear()
        rejectedSince = 0L; warmedUp = false; atFloorSince = 0L
    }

    /** A GPS fix: the effort the heart is responding to. Paused fixes should not be passed in. */
    fun onFix(time: Long, speedMps: Double, altitudeM: Double?) {
        val v = if (speedMps.isFinite() && speedMps >= 0.0) speedMps else 0.0
        if (!haveSpeed) {
            smoothedSpeed = v; haveSpeed = true
        } else {
            // A lone fix after a long gap must not dominate: standing still, fixes come sparsely and one noisy
            // reading would otherwise flip "moving" on and re-arm the floor at the very end of a run.
            val dt = ((time - lastFixTime) / 1000.0).coerceIn(0.0, MAX_FIX_GAP_S)
            if (dt > 0.0) smoothedSpeed += (1 - exp(-dt / SPEED_TAU_S)) * (v - smoothedSpeed)
        }
        lastFixTime = time
        if (altitudeM != null && altitudeM.isFinite()) {
            altitudes.addLast(time to altitudeM)
            while (altitudes.size > 1 && time - altitudes.first().first > CLIMB_WINDOW_MS) altitudes.removeFirst()
            val (t0, a0) = altitudes.first()
            climbRate = if (time > t0) (altitudeM - a0) / ((time - t0) / 1000.0) else 0.0
        }
    }

    /**
     * When the phone has had no fix for a while the effort is unknown; decay the smoothed speed so a lost signal
     * does not leave the floor in force forever. Called by the controller each tick.
     */
    fun onNoFix(time: Long) {
        if (haveSpeed && time - lastFixTime > NO_FIX_MS) smoothedSpeed *= 0.9
    }

    /** A watch reading. Returns the verdict; the caller stores the estimate and the flag with the sample. */
    fun onHr(time: Long, bpm: Int): Verdict {
        if (!started) {
            hr = bpm.toDouble(); started = true; lastHrTime = time
            return Verdict(bpm, accepted = true, flagged = false, warmedUp = false, moving = isMoving, wrongByPercent = 0.0)
        }
        val dt = ((time - lastHrTime) / 1000.0).coerceIn(0.05, 30.0)
        lastHrTime = time
        val moving = isMoving

        // ---- predict: hr chases the target with a first-order lag
        val target = target(smoothedSpeed, climbRate, offset, moving)
        val tau = if (target > hr) TAU_UP_S else TAU_DOWN_S
        val a = 1 - exp(-dt / tau)
        val hrPred = hr + a * (target - hr)
        // Jacobian of the prediction w.r.t. [hr, offset]: d(hrPred)/d(hr) = 1-a; d(hrPred)/d(offset) = a·dT/dOffset.
        // The offset only reaches the target through the running branch (above the resting ramp).
        val dTdOff = if (smoothedSpeed >= RAMP_LOW_MPS) a * rampFraction(smoothedSpeed) else 0.0
        val f00 = 1 - a
        val f01 = dTdOff
        var q00 = p00 * f00 * f00 + 2 * p01 * f00 * f01 + p11 * f01 * f01 + Q_HR_PER_S * dt
        var q01 = p01 * f00 + p11 * f01
        var q11 = p11 + Q_OFFSET_PER_S * dt

        // ---- gate
        val s = q00 + R_WATCH
        val innovation = bpm - hrPred
        val belowFloor = warmedUp && moving && bpm < floorBpm
        val implausible = belowFloor || abs(innovation) > GATE_SIGMA * sqrt(s)
        val accepted: Boolean
        if (implausible) {
            if (rejectedSince == 0L) rejectedSince = time
            hr = hrPred; p00 = q00; p01 = q01; p11 = q11
            accepted = false
        } else {
            val k0 = q00 / s
            val k1 = q01 / s
            hr = hrPred + k0 * innovation
            offset += k1 * innovation
            p00 = (1 - k0) * q00
            p01 = (1 - k0) * q01
            p11 = q11 - k1 * q01
            rejectedSince = 0L
            accepted = true
            // warmed up once the accepted rate has sat at or above the floor for a while
            if (bpm >= floorBpm) {
                if (atFloorSince == 0L) atFloorSince = time
                if (!warmedUp && time - atFloorSince >= WARMUP_MS) warmedUp = true
            } else {
                atFloorSince = 0L
            }
        }
        hr = hr.coerceIn(30.0, hrMaxBpm.toDouble())
        val flagged = rejectedSince != 0L && time - rejectedSince >= FLAG_MS
        val est = hr.roundToInt()
        val pct = if (est > 0) 100.0 * (bpm - est) / est else 0.0
        return Verdict(est, accepted, flagged, warmedUp, moving, pct)
    }

    /** Steady-state heart rate for the current effort. */
    private fun target(speed: Double, climb: Double, off: Double, moving: Boolean): Double {
        val running = targetIntercept + targetPerMps * speed + targetPerClimbMps * climb + off
        var t = when {
            speed >= MOVING_MPS -> running
            speed <= RAMP_LOW_MPS -> restingBpm.toDouble()
            else -> restingBpm + (running - restingBpm) * rampFraction(speed)
        }
        if (warmedUp && moving) t = max(t, floorBpm.toDouble())
        return min(t, hrMaxBpm.toDouble())
    }

    private fun rampFraction(speed: Double): Double =
        ((speed - RAMP_LOW_MPS) / (MOVING_MPS - RAMP_LOW_MPS)).coerceIn(0.0, 1.0)

    companion object {
        /** Below this the wearer cannot be exercising (Buzz, 2026-09-19); applies once warmed up and moving. */
        const val FLOOR_BPM = 125
        const val HR_MAX_BPM = 185

        /** The fitted target, see the class doc. Speed in m/s, climb in m/s of altitude. */
        const val TARGET_INTERCEPT = 134.0
        const val TARGET_PER_MPS = 7.0
        const val TARGET_PER_CLIMB_MPS = 19.3

        /** Heart-rate onset and recovery time constants. */
        const val TAU_UP_S = 45.0
        const val TAU_DOWN_S = 75.0

        /** Smoothing of GPS speed (the effort the heart sees is not the instantaneous fix). */
        const val SPEED_TAU_S = 20.0
        const val CLIMB_WINDOW_MS = 30_000L
        /** Longest gap between fixes the speed smoothing will credit; longer gaps count as this. */
        const val MAX_FIX_GAP_S = 5.0
        const val NO_FIX_MS = 15_000L

        /** Moving at exercise pace at or above this; the target ramps from rest to running between the two. */
        const val MOVING_MPS = 1.0
        const val RAMP_LOW_MPS = 0.4

        /** Watch noise variance (bpm²) and process noise per second. */
        const val R_WATCH = 16.0
        const val Q_HR_PER_S = 4.0
        const val Q_OFFSET_PER_S = 0.02

        /** Innovation gate, in standard deviations. */
        const val GATE_SIGMA = 3.0

        /** Rejections must persist this long before the watch is called wrong. */
        const val FLAG_MS = 15_000L

        /** Accepted readings at or above the floor for this long mean the session is warmed up. */
        const val WARMUP_MS = 30_000L

        /** A flagged reading further than this from the estimate is worth saying out loud. */
        const val WRONG_PERCENT = 5.0

        /**
         * Replay a finished workout: returns the workout's samples with [HrSample.estimate] and [HrSample.flagged]
         * filled in, in time order. Used by the repair action and the tests. [points] and [samples] may be in any
         * order; paused fixes are ignored.
         */
        fun replay(
            points: List<TrackPoint>,
            samples: List<HrSample>,
            restingBpm: Int,
            estimator: HrEstimator = HrEstimator(restingBpm),
        ): List<HrSample> {
            estimator.reset()
            val fixes = points.filter { !it.paused && it.accepted }.sortedBy { it.time }
            val hr = samples.filter { it.bpm > 0 }.sortedBy { it.time }
            val out = ArrayList<HrSample>(hr.size)
            var fi = 0
            for (s in hr) {
                while (fi < fixes.size && fixes[fi].time <= s.time) {
                    val f = fixes[fi]
                    estimator.onFix(f.time, f.speedMps.toDouble(), f.altitudeM)
                    fi++
                }
                if (fi > 0 && s.time - fixes[fi - 1].time > NO_FIX_MS) estimator.onNoFix(s.time)
                val v = estimator.onHr(s.time, s.bpm)
                // a flag only counts as a dropout while warmed up and moving: the ramps are never candidates
                val dropout = v.flagged && v.warmedUp && v.moving
                out += s.copy(estimate = v.estimate, flagged = dropout)
            }
            return out
        }

        /**
         * The repair for a replayed list: every flagged sample gets [HrSample.bpm] replaced by its estimate, with
         * the watch value kept in [HrSample.measured]. Already-repaired samples are left alone. Returns only the
         * samples that changed.
         */
        fun repair(replayed: List<HrSample>): List<HrSample> =
            replayed.filter { it.flagged && !it.repaired && it.estimate != null && it.estimate != it.bpm }
                .map { it.copy(measured = it.bpm, bpm = it.estimate!!, source = it.source.takeIf { s -> s != SampleSource.HISTORY } ?: SampleSource.WORKOUT) }
    }
}
