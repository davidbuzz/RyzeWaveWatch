package au.buzz.ryzewave.workout

import au.buzz.ryzewave.workout.BreadcrumbGate.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbGateTest {
    private val M = 60_000L

    @Test
    fun walkingTurnsLocationOnAtTheSlowRateRunningAtTheFastRate() {
        val g = BreadcrumbGate()
        assertFalse(g.current.locationOn)
        val walk = g.onActivity(Activity.WALKING, 0)
        assertTrue(walk.locationOn); assertEquals(g.slowIntervalMs, walk.intervalMs)
        val run = g.onActivity(Activity.RUNNING, M)
        assertTrue(run.locationOn); assertEquals(g.fastIntervalMs, run.intervalMs)
        assertTrue(g.onActivity(Activity.ON_BICYCLE, 2 * M).locationOn)
    }

    @Test
    fun stillKeepsSamplingThroughTheGraceThenTurnsOff() {
        val g = BreadcrumbGate(stillGraceMs = 2 * M)
        g.onActivity(Activity.WALKING, 0)
        assertTrue(g.onActivity(Activity.STILL, 10 * M).locationOn)          // traffic light: still within the grace
        assertTrue(g.tick(10 * M + M).locationOn)
        assertFalse(g.tick(10 * M + 2 * M + 1).locationOn)                    // grace over → off
        assertFalse(g.onActivity(Activity.STILL, 20 * M).locationOn)          // still while already off stays off
    }

    @Test
    fun aVehicleRideNeverRecords() {
        val g = BreadcrumbGate()
        g.onActivity(Activity.WALKING, 0)
        assertFalse(g.onActivity(Activity.IN_VEHICLE, M).locationOn)
        assertFalse(g.onSignificantMotion(2 * M).locationOn)                  // a bump in the car changes nothing
    }

    @Test
    fun significantMotionWithoutActivityReportsGivesAShortBurst() {
        val g = BreadcrumbGate(motionBurstMs = 3 * M)
        val d = g.onSignificantMotion(0)
        assertTrue(d.locationOn); assertEquals(g.slowIntervalMs, d.intervalMs)
        assertTrue(g.tick(2 * M).locationOn)
        assertFalse(g.tick(3 * M + 1).locationOn)
    }

    @Test
    fun aStaleActivityReportTurnsLocationOffForTheBattery() {
        val g = BreadcrumbGate(staleMs = 10 * M)
        g.onActivity(Activity.RUNNING, 0)
        assertTrue(g.tick(9 * M).locationOn)
        assertFalse(g.tick(10 * M + 1).locationOn)
    }
}
