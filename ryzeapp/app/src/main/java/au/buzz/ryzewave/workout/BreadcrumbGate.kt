package au.buzz.ryzewave.workout

/**
 * The always-on GPS breadcrumb's motion gate (docs/PLAN.md "Always-on GPS breadcrumb"): decides, from the phone's
 * activity-recognition transitions and significant-motion events, whether the location radio should be on and how
 * often to sample — so the phone records where you went while you were actually moving on your own power and
 * discards being still, without a blind timer waking the GPS all day. Pure Kotlin, unit-tested; the Android side
 * (`BreadcrumbService`) feeds it and obeys [Decision].
 *
 *  - WALKING / RUNNING / ON_FOOT / ON_BICYCLE → location ON (running/cycling sample faster);
 *  - STILL → keep sampling for [stillGraceMs] (a traffic light), then OFF;
 *  - IN_VEHICLE → OFF (a car ride is not a workout; the watch has its own GPS-less step count anyway);
 *  - a significant-motion event with no activity report for a while → ON at the slow rate for [motionBurstMs],
 *    so a phone without activity recognition still leaves a trail;
 *  - no report of any kind for [staleMs] while ON → OFF (fail safe for the battery).
 */
class BreadcrumbGate(
    private val stillGraceMs: Long = 2 * 60_000L,
    private val motionBurstMs: Long = 3 * 60_000L,
    private val staleMs: Long = 10 * 60_000L,
    val slowIntervalMs: Long = 60_000L,
    val fastIntervalMs: Long = 20_000L,
) {
    enum class Activity { STILL, WALKING, RUNNING, ON_FOOT, ON_BICYCLE, IN_VEHICLE, UNKNOWN }

    data class Decision(val locationOn: Boolean, val intervalMs: Long, val reason: String)

    private var activity = Activity.UNKNOWN
    private var activityAt = 0L
    private var stillSince = -1L
    private var motionUntil = -1L
    private var lastDecision = Decision(false, slowIntervalMs, "start")

    val current: Decision get() = lastDecision

    fun onActivity(a: Activity, time: Long): Decision {
        activity = a
        activityAt = time
        stillSince = if (a == Activity.STILL) (if (stillSince < 0) time else stillSince) else -1L
        return decide(time)
    }

    fun onSignificantMotion(time: Long): Decision {
        motionUntil = time + motionBurstMs
        return decide(time)
    }

    /** Periodic re-evaluation (the service calls it every minute or so). */
    fun tick(time: Long): Decision = decide(time)

    private fun decide(now: Long): Decision {
        val d = when {
            activity == Activity.IN_VEHICLE -> Decision(false, slowIntervalMs, "in a vehicle")
            activity == Activity.RUNNING || activity == Activity.ON_BICYCLE ->
                if (now - activityAt > staleMs) Decision(false, slowIntervalMs, "no activity report for ${staleMs / 60_000} min")
                else Decision(true, fastIntervalMs, activity.name.lowercase())
            activity == Activity.WALKING || activity == Activity.ON_FOOT ->
                if (now - activityAt > staleMs) Decision(false, slowIntervalMs, "no activity report for ${staleMs / 60_000} min")
                else Decision(true, slowIntervalMs, activity.name.lowercase())
            activity == Activity.STILL && stillSince >= 0 && now - stillSince < stillGraceMs ->
                if (lastDecision.locationOn) Decision(true, slowIntervalMs, "still, within the grace") else Decision(false, slowIntervalMs, "still")
            motionUntil > now -> Decision(true, slowIntervalMs, "significant motion")
            else -> Decision(false, slowIntervalMs, if (activity == Activity.STILL) "still" else "no movement known")
        }
        lastDecision = d
        return d
    }
}
