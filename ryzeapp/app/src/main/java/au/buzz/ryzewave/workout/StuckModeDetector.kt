package au.buzz.ryzewave.workout

import java.time.Instant
import java.time.ZoneId

/** How sure / how urgent a "stuck" verdict is; decides the grace before an auto-stop. */
enum class Urgency { NORMAL, HIGH }

/**
 * The pure decision of the stuck-in-exercise-mode detector (docs/PLAN.md). Given the sport's expected
 * indicators ([SportSignature]) and what was observed over the rolling window ([ActivitySignals.Snapshot]):
 *
 *  - **live** = any expected indicator is ACTIVE;
 *  - **likelyStuck** = the window is covered, nothing expected is active, and at least one expected indicator was
 *    actually measured (INACTIVE rather than UNKNOWN). A sport whose only expected signals are unavailable can
 *    therefore never be flagged, and neither can an [SportClass.UNMONITORED] one;
 *  - **urgency** = HIGH when it is night, the heart rate sat at sleeping level for the whole window and the phone
 *    did not move (the classic "bumped the watch in my sleep" case, formerly the night workout guard): short
 *    grace. Otherwise NORMAL: long grace, gentle.
 *
 * No Android imports; the wiring (timers, notification, speech, stopping) is [StuckWorkoutMonitor].
 */
object StuckModeDetector {
    /** Rolling window every indicator is judged over. */
    const val WINDOW_MS = 4 * 60_000L

    /** Auto-stop this long after the warning when nothing changes (normal urgency). */
    const val GRACE_NORMAL_MS = 10 * 60_000L

    /** …and this long for a near-certain accidental night start. */
    const val GRACE_HIGH_MS = 2 * 60_000L

    /** HR at or above resting + this is "elevated" (working). */
    const val HR_MARGIN_BPM = 15

    /** HR below resting + this for the whole window is "sleeping level". */
    const val SLEEP_MARGIN_BPM = 5

    /** Resting HR assumed when the history holds too few periodic samples to derive one. */
    const val FALLBACK_RESTING_HR_BPM = 65

    /** Night window (local wall-clock hours): start inclusive, end exclusive → 22:00–05:59. */
    const val NIGHT_START_HOUR = 22
    const val NIGHT_END_HOUR = 6

    data class Verdict(
        val live: Boolean,
        val likelyStuck: Boolean,
        val urgency: Urgency,
        /** Expected indicators that were measured (INACTIVE) — empty means the verdict could not be reached. */
        val measurable: Set<Indicator>,
        val reason: String,
    )

    fun graceMs(urgency: Urgency): Long = if (urgency == Urgency.HIGH) GRACE_HIGH_MS else GRACE_NORMAL_MS

    /** True when [time]'s local hour falls in the night window. */
    fun isNight(time: Long, zone: ZoneId): Boolean {
        val hour = Instant.ofEpochMilli(time).atZone(zone).hour
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    fun evaluate(
        expected: Set<Indicator>,
        states: Map<Indicator, SignalState>,
        windowCovered: Boolean,
        night: Boolean,
        hrSleeping: Boolean,
    ): Verdict {
        val active = expected.filter { states[it] == SignalState.ACTIVE }.toSet()
        val measurable = expected.filter { states[it] == SignalState.INACTIVE }.toSet()
        val live = active.isNotEmpty()
        val likelyStuck = windowCovered && !live && measurable.isNotEmpty()
        val motionStill = states[Indicator.MOTION] != SignalState.ACTIVE
        val urgency = if (likelyStuck && night && hrSleeping && motionStill) Urgency.HIGH else Urgency.NORMAL
        val reason = when {
            live -> "live on ${active.joinToString("+") { it.name.lowercase() }}"
            expected.isEmpty() -> "unmonitored sport"
            !windowCovered -> "window not covered yet"
            measurable.isEmpty() -> "nothing measurable (${expected.joinToString("+") { it.name.lowercase() }} all unknown)"
            else -> buildString {
                append("no activity: ").append(measurable.joinToString("+") { it.name.lowercase() }).append(" measured none")
                val unknown = expected - measurable
                if (unknown.isNotEmpty()) append(", ").append(unknown.joinToString("+") { it.name.lowercase() }).append(" unknown")
                append(if (urgency == Urgency.HIGH) "; night, HR at sleeping level, phone still → HIGH" else "; NORMAL")
            }
        }
        return Verdict(live, likelyStuck, urgency, measurable, reason)
    }
}
