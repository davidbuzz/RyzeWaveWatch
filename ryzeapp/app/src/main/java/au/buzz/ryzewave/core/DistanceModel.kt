package au.buzz.ryzewave.core

/**
 * Distance is never reported by the watch. Daily distance comes from steps × stride; workout distance
 * from the phone's GPS track. Both implementations live in `workout/`; these are the contracts.
 */
interface StrideModel {
    /** Metres per walking / running step for the profile, using calibrated values when present. */
    fun walkStrideM(profile: UserProfile, stride: StrideSettings): Double
    fun runStrideM(profile: UserProfile, stride: StrideSettings): Double
    fun stepsToMeters(walkSteps: Int, runSteps: Int, profile: UserProfile, stride: StrideSettings): Double =
        walkSteps * walkStrideM(profile, stride) + runSteps * runStrideM(profile, stride)
}

/**
 * Accumulates GPS distance with jitter rejection. Feed every fix; read [distanceMeters] and [paceSecPerKm].
 * Rules (see ../../../../../docs/PLAN.md §3b): drop fixes with poor accuracy, ignore movement smaller than the
 * accuracy radius when nearly stationary, haversine between accepted fixes, pace from a rolling window.
 */
interface GpsDistanceTracker {
    val distanceMeters: Double
    val paceSecPerKm: Double        // 0.0 when unknown
    val speedMps: Double
    /** Returns true if the fix was accepted (and contributed to distance). */
    fun addFix(time: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float): Boolean
    fun reset()
}
