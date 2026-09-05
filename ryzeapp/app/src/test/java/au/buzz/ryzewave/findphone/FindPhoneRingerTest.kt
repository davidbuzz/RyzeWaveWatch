package au.buzz.ryzewave.findphone

import au.buzz.ryzewave.core.WatchEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The find-my-phone state machine on virtual time: the watch's `D1 0A 01` / `D1 0A 00` pushes
 * (docs/PROTOCOL.md, captures/bridge_passive_20260904_195347.txt) as [WatchEvent.FindPhone], the notification's
 * Stop action as [FindPhoneRinger.stop] with reason `user`, and the 30 s timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FindPhoneRingerTest {

    private class FakeAlerter : FindPhoneAlerter {
        var starts = 0
        var stops = 0
        var failStart = false
        val order = ArrayList<String>()
        override fun startAlarm() {
            starts++
            order += "start"
            if (failStart) throw IllegalStateException("no ringtone")
        }
        override fun stopAlarm() {
            stops++
            order += "stop"
        }
    }

    private val lines = ArrayList<String>()

    private fun TestScope.ringer(alerter: FakeAlerter, timeoutMs: Long = FindPhoneRinger.TIMEOUT_MS) =
        FindPhoneRinger(alerter, backgroundScope, timeoutMs) { m, _ -> lines += m }

    @Test
    fun watchStartThenWatchStop() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        assertFalse(r.ringing.value)
        assertNull(r.lastStopReason)

        r.onEvent(WatchEvent.FindPhone(start = true))        // D1 0A 01
        assertTrue(r.ringing.value)
        assertEquals(1, a.starts)
        assertEquals(0, a.stops)
        assertEquals(1, r.startCount)

        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.ringing.value)                           // well within the 30 s

        r.onEvent(WatchEvent.FindPhone(start = false))       // D1 0A 00
        assertFalse(r.ringing.value)
        assertEquals(1, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_WATCH, r.lastStopReason)
        assertEquals(listOf("start", "stop"), a.order)

        advanceTimeBy(60_000); runCurrent()                   // the timer was cancelled: no second stop
        assertEquals(1, a.stops)
        assertFalse(r.ringing.value)
    }

    @Test
    fun timesOutAfter30Seconds() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        r.onEvent(WatchEvent.FindPhone(start = true))
        advanceTimeBy(FindPhoneRinger.TIMEOUT_MS - 1); runCurrent()
        assertTrue(r.ringing.value)
        assertEquals(0, a.stops)

        advanceTimeBy(2); runCurrent()
        assertFalse(r.ringing.value)
        assertEquals(1, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_TIMEOUT, r.lastStopReason)
        assertTrue(lines.any { it.contains("stopped (timeout)") })

        // a late D1 0A 00 from the watch after the timeout is a harmless no-op
        assertFalse(r.stop(FindPhoneRinger.SOURCE_WATCH))
        assertEquals(1, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_TIMEOUT, r.lastStopReason)
    }

    @Test
    fun userStopFromTheNotificationCancelsTheTimer() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        r.onEvent(WatchEvent.FindPhone(start = true))
        advanceTimeBy(10_000); runCurrent()

        assertTrue(r.stop(FindPhoneRinger.SOURCE_USER))       // Stop action -> WatchService -> stop("user")
        assertFalse(r.ringing.value)
        assertEquals(1, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_USER, r.lastStopReason)

        advanceTimeBy(FindPhoneRinger.TIMEOUT_MS); runCurrent()
        assertEquals(1, a.stops)                              // the timer did not fire a second stop
        assertEquals(FindPhoneRinger.SOURCE_USER, r.lastStopReason)
    }

    @Test
    fun duplicateStartIsIgnoredAndKeepsTheOriginalTimer() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        assertTrue(r.start(FindPhoneRinger.SOURCE_WATCH))
        advanceTimeBy(20_000); runCurrent()

        assertFalse(r.start(FindPhoneRinger.SOURCE_WATCH))    // second D1 0A 01 while ringing
        r.onEvent(WatchEvent.FindPhone(start = true))         // and once more through the event path
        assertEquals(1, a.starts)                             // ringtone not restarted
        assertEquals(1, r.startCount)
        assertTrue(lines.count { it.contains("already ringing") } == 2)

        advanceTimeBy(10_001); runCurrent()                   // 30 s after the FIRST start, not the duplicate
        assertFalse(r.ringing.value)
        assertEquals(1, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_TIMEOUT, r.lastStopReason)
    }

    @Test
    fun stopWhileIdleIsANoOp() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        r.onEvent(WatchEvent.FindPhone(start = false))       // the watch sends D1 0A 00 on every connect
        assertFalse(r.stop(FindPhoneRinger.SOURCE_USER))
        assertFalse(r.ringing.value)
        assertEquals(0, a.starts)
        assertEquals(0, a.stops)
        assertNull(r.lastStopReason)
    }

    @Test
    fun ringsAgainAfterAStop() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        r.onEvent(WatchEvent.FindPhone(start = true))
        r.onEvent(WatchEvent.FindPhone(start = false))
        r.onEvent(WatchEvent.FindPhone(start = true))
        assertTrue(r.ringing.value)
        assertEquals(2, a.starts)
        assertEquals(1, a.stops)
        assertEquals(2, r.startCount)
        assertNull(r.lastStopReason)                          // cleared by the new ring

        advanceTimeBy(FindPhoneRinger.TIMEOUT_MS + 1); runCurrent()
        assertFalse(r.ringing.value)
        assertEquals(2, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_TIMEOUT, r.lastStopReason)
    }

    @Test
    fun otherEventsAreIgnored() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        r.onEvent(WatchEvent.Spo2Result(1L, 98))
        r.onEvent(WatchEvent.HrSummary(1L, 120, 50, 70))
        r.onEvent(WatchEvent.Raw("33F2", "440f"))
        assertFalse(r.ringing.value)
        assertEquals(0, a.starts)
    }

    @Test
    fun alerterFailureDoesNotBreakTheStateMachine() = runTest {
        val a = FakeAlerter().apply { failStart = true }
        val r = ringer(a)
        r.onEvent(WatchEvent.FindPhone(start = true))         // startAlarm throws: still counted as ringing
        assertTrue(r.ringing.value)
        assertTrue(lines.any { it.contains("alerter start failed") })

        advanceTimeBy(FindPhoneRinger.TIMEOUT_MS + 1); runCurrent()
        assertFalse(r.ringing.value)                          // and still times out cleanly
        assertEquals(1, a.stops)
    }

    /**
     * Every ring has a generation; the timer of ring 1 that was already past its delay (its cancel raced a user
     * Stop + a new start from the watch) must not stop ring 2.
     */
    @Test
    fun staleTimeoutOfAnEarlierRingDoesNotStopTheNextOne() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        assertEquals(0, r.generation)
        assertTrue(r.start(FindPhoneRinger.SOURCE_WATCH))            // ring 1
        assertEquals(1, r.generation)
        assertTrue(r.stop(FindPhoneRinger.SOURCE_USER))
        assertTrue(r.start(FindPhoneRinger.SOURCE_WATCH))            // ring 2, 1 ms later
        assertEquals(2, r.generation)

        assertFalse(r.stop(FindPhoneRinger.SOURCE_TIMEOUT, 1))       // what ring 1's timer would do
        assertTrue(r.ringing.value)
        assertEquals(1, a.stops)
        assertTrue(lines.any { it.contains("stale timeout for ring 1 ignored") })

        assertTrue(r.stop(FindPhoneRinger.SOURCE_TIMEOUT, 2))        // ring 2's own timer
        assertFalse(r.ringing.value)
        assertEquals(2, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_TIMEOUT, r.lastStopReason)
        assertFalse(r.stop(FindPhoneRinger.SOURCE_TIMEOUT, 2))       // and only once
    }

    /** The real timer carries its ring's generation: a ring restarted just before the old deadline gets its full time. */
    @Test
    fun aRestartedRingGetsItsOwnFullTimeout() = runTest {
        val a = FakeAlerter()
        val r = ringer(a)
        r.start(FindPhoneRinger.SOURCE_WATCH)
        advanceTimeBy(FindPhoneRinger.TIMEOUT_MS - 10); runCurrent()
        r.stop(FindPhoneRinger.SOURCE_WATCH)
        r.start(FindPhoneRinger.SOURCE_WATCH)                        // ring 2, 10 ms before ring 1 would have timed out
        advanceTimeBy(20); runCurrent()
        assertTrue(r.ringing.value)                                  // ring 1's deadline passed, ring 2 still rings
        assertEquals(1, a.stops)
        advanceTimeBy(FindPhoneRinger.TIMEOUT_MS); runCurrent()
        assertFalse(r.ringing.value)
        assertEquals(2, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_TIMEOUT, r.lastStopReason)
    }

    /** The alerter runs outside the state lock: a stop issued from inside startAlarm (re-entrant) is honoured. */
    @Test
    fun stopFromInsideStartAlarmIsHonoured() = runTest {
        lateinit var r: FindPhoneRinger
        val a = object : FindPhoneAlerter {
            var starts = 0
            var stops = 0
            override fun startAlarm() { starts++; r.stop(FindPhoneRinger.SOURCE_USER) }
            override fun stopAlarm() { stops++ }
        }
        r = FindPhoneRinger(a, backgroundScope, FindPhoneRinger.TIMEOUT_MS) { m, _ -> lines += m }
        assertTrue(r.start(FindPhoneRinger.SOURCE_WATCH))
        assertFalse(r.ringing.value)
        assertEquals(1, a.starts)
        assertEquals(1, a.stops)
        assertEquals(FindPhoneRinger.SOURCE_USER, r.lastStopReason)
        advanceTimeBy(FindPhoneRinger.TIMEOUT_MS + 1); runCurrent()
        assertEquals(1, a.stops)                                     // the timer was cancelled by that stop
    }

    @Test
    fun customTimeout() = runTest {
        val a = FakeAlerter()
        val r = ringer(a, timeoutMs = 5_000)
        r.start(FindPhoneRinger.SOURCE_DEBUG)
        advanceTimeBy(4_999); runCurrent()
        assertTrue(r.ringing.value)
        advanceTimeBy(2); runCurrent()
        assertFalse(r.ringing.value)
        assertEquals(FindPhoneRinger.SOURCE_TIMEOUT, r.lastStopReason)
    }
}
