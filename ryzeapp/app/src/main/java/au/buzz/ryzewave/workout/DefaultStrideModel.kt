package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.StrideModel
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile

/**
 * Stride model with the vendor app's factors as defaults (see docs/PROTOCOL.md §9 and docs/PLAN.md §3a):
 * walking stride = height × 0.410 (male) / 0.415 (female), running stride = height × 0.546 / 0.505.
 * A calibrated stride in [StrideSettings] (metres per step, > 0) always wins over the derived value.
 * [calibratedStrideM] is the single place the calibration bounds live (the Settings screen goes through it).
 */
class DefaultStrideModel : StrideModel {

    override fun walkStrideM(profile: UserProfile, stride: StrideSettings): Double =
        stride.walkStrideM?.takeIf { it.isFinite() && it > 0.0 } ?: defaultWalkStrideM(profile)

    override fun runStrideM(profile: UserProfile, stride: StrideSettings): Double =
        stride.runStrideM?.takeIf { it.isFinite() && it > 0.0 } ?: defaultRunStrideM(profile)

    companion object {
        const val WALK_FACTOR_MALE = 0.410
        const val WALK_FACTOR_FEMALE = 0.415
        const val RUN_FACTOR_MALE = 0.546
        const val RUN_FACTOR_FEMALE = 0.505

        /** Sanity bounds for a calibrated stride (metres per step). */
        const val MIN_STRIDE_M = 0.30
        const val MAX_STRIDE_M = 1.60

        /** Vendor default walking stride in metres for the profile (height in cm × factor). */
        fun defaultWalkStrideM(profile: UserProfile): Double =
            profile.heightCm * (if (profile.male) WALK_FACTOR_MALE else WALK_FACTOR_FEMALE) / 100.0

        /** Vendor default running stride in metres for the profile. */
        fun defaultRunStrideM(profile: UserProfile): Double =
            profile.heightCm * (if (profile.male) RUN_FACTOR_MALE else RUN_FACTOR_FEMALE) / 100.0

        /**
         * Stride measured from a GPS workout: [distanceMeters] ÷ [steps]. Returns null when there is too little
         * data or the result is outside the sanity bounds, so the caller can keep the previous setting.
         */
        fun calibratedStrideM(
            steps: Int,
            distanceMeters: Double,
            minSteps: Int = 200,
            minDistanceM: Double = 200.0,
        ): Double? {
            if (steps < minSteps || distanceMeters < minDistanceM || !distanceMeters.isFinite()) return null
            val stride = distanceMeters / steps
            return stride.takeIf { it in MIN_STRIDE_M..MAX_STRIDE_M }
        }
    }
}
