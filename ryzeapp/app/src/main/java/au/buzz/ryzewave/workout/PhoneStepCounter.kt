package au.buzz.ryzewave.workout

/**
 * Turns the phone's `Sensor.TYPE_STEP_COUNTER` (a monotonic since-boot cumulative count) into the number of
 * steps taken during a workout, excluding whatever was walked while paused. Pure Kotlin so the delta / pause
 * logic is unit-tested with a fake source; [WorkoutService] owns the Android `SensorEventListener` and feeds
 * each reading in.
 *
 * Rules:
 *  - the first reading of a session is the baseline (contributes nothing);
 *  - each later reading adds its delta to the total, unless [paused] — steps taken while paused are dropped;
 *  - a non-positive delta (a device reboot resets the counter, or no movement) is ignored and re-baselines.
 */
class PhoneStepCounter {
    private var lastReading: Long? = null
    private var counted = 0L
    private var paused = false

    /** Steps counted so far this session (never negative). */
    val steps: Int get() = counted.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun reset() {
        lastReading = null
        counted = 0L
        paused = false
    }

    fun setPaused(value: Boolean) {
        paused = value
    }

    /** Feed one cumulative sensor reading. */
    fun onReading(cumulative: Long) {
        val prev = lastReading
        lastReading = cumulative
        if (prev == null) return
        val delta = cumulative - prev
        if (delta <= 0L) return                 // counter reset or no change: skip, keep the new baseline
        if (!paused) counted += delta
    }
}
