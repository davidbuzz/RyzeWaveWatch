package au.buzz.ryzewave.workout

/**
 * Maps workout phase transitions to spoken announcements. Pure Kotlin (no Android / TTS), so the transition
 * logic is unit-tested; [WorkoutService] feeds it every [WorkoutState.state] (with the [WorkoutState.stopReason])
 * and speaks whatever it returns.
 *
 * It announces once per real transition and nothing on a repeated same-phase emission (StateFlow is conflated,
 * but a fix or HR sample re-emits the same phase). Because the announcement is driven from the controller's
 * state — not the button handlers — a pause/resume/stop from the watch is announced exactly like one from the
 * app, each exactly once. A stop the stuck-workout detector made ([StopReason.NO_ACTIVITY]) says why.
 *
 * Watch-originated workouts (no controller session) are announced by [StuckWorkoutMonitor] with the same words.
 */
class StateAnnouncer {
    private var last: WorkoutPhase? = null

    /** The utterance for the transition into [phase], or null when there is nothing new to say. */
    fun onPhase(phase: WorkoutPhase, stopReason: StopReason? = null): String? {
        val prev = last
        last = phase
        if (prev == phase) return null
        return when (phase) {
            WorkoutPhase.RUNNING -> if (prev == WorkoutPhase.PAUSED) RESUMED else STARTED
            WorkoutPhase.PAUSED -> PAUSED
            // The idle STOPPED the announcer starts in must not speak; only STOPPED after an active phase.
            WorkoutPhase.STOPPED -> when {
                prev == null -> null
                stopReason == StopReason.NO_ACTIVITY -> STOPPED_NO_ACTIVITY
                else -> STOPPED
            }
        }
    }

    companion object {
        const val STARTED = "workout started"
        const val PAUSED = "workout paused"
        const val RESUMED = "workout resumed"
        const val STOPPED = "workout stopped"
        const val STOPPED_NO_ACTIVITY = "workout stopped, no activity"

        /**
         * A watch-button start the phone could NOT escalate into a tracked session (location off, permission
         * missing, or the foreground start was refused). Deliberately distinct from [STARTED]: on 2026-09-10 the
         * plain phrase was spoken for an untracked watch start and masked that nothing was being recorded.
         */
        const val STARTED_WATCH_ONLY = "workout on watch only, open the app to track it"
    }
}
