package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateAnnouncerTest {

    @Test
    fun startPauseResumeStopYieldsExactlyTheRightSequence() {
        val a = StateAnnouncer()
        // the idle STOPPED the controller starts in must not speak
        assertNull(a.onPhase(WorkoutPhase.STOPPED))
        assertEquals(StateAnnouncer.STARTED, a.onPhase(WorkoutPhase.RUNNING))
        assertEquals(StateAnnouncer.PAUSED, a.onPhase(WorkoutPhase.PAUSED))
        assertEquals(StateAnnouncer.RESUMED, a.onPhase(WorkoutPhase.RUNNING))
        assertEquals(StateAnnouncer.STOPPED, a.onPhase(WorkoutPhase.STOPPED))
    }

    @Test
    fun repeatedSameStateEmissionsDoNotAnnounceTwice() {
        val a = StateAnnouncer()
        assertEquals(StateAnnouncer.STARTED, a.onPhase(WorkoutPhase.RUNNING))
        assertNull(a.onPhase(WorkoutPhase.RUNNING))          // a fix / HR re-emits RUNNING: silence
        assertNull(a.onPhase(WorkoutPhase.RUNNING))
        assertEquals(StateAnnouncer.PAUSED, a.onPhase(WorkoutPhase.PAUSED))
        assertNull(a.onPhase(WorkoutPhase.PAUSED))           // re-emitted PAUSED: silence
    }

    @Test
    fun resumeIsOnlyAfterPauseNotAfterStart() {
        val a = StateAnnouncer()
        // first RUNNING with no prior phase is "started", not "resumed"
        assertEquals(StateAnnouncer.STARTED, a.onPhase(WorkoutPhase.RUNNING))
    }
}
