package au.buzz.ryzewave.workout

/**
 * Maps workout phase transitions to spoken announcements. Pure Kotlin (no Android / TTS), so the transition
 * logic is unit-tested; [WorkoutService] feeds it every [WorkoutState.state] and speaks whatever it returns.
 *
 * It announces once per real transition and nothing on a repeated same-phase emission (StateFlow is conflated,
 * but a fix or HR sample re-emits the same phase). Because the announcement is driven from the controller's
 * state — not the button handlers — a pause/resume/stop from the watch is announced exactly like one from the
 * app, each exactly once.
 */
class StateAnnouncer {
    private var last: WorkoutPhase? = null

    /** The utterance for the transition into [phase], or null when there is nothing new to say. */
    fun onPhase(phase: WorkoutPhase): String? {
        val prev = last
        last = phase
        if (prev == phase) return null
        return when (phase) {
            WorkoutPhase.RUNNING -> if (prev == WorkoutPhase.PAUSED) RESUMED else STARTED
            WorkoutPhase.PAUSED -> PAUSED
            // The idle STOPPED the announcer starts in must not speak; only STOPPED after an active phase.
            WorkoutPhase.STOPPED -> if (prev == null) null else STOPPED
        }
    }

    companion object {
        const val STARTED = "workout started"
        const val PAUSED = "workout paused"
        const val RESUMED = "workout resumed"
        const val STOPPED = "workout stopped"
    }
}
