package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutFormatTest {
    @Test
    fun elapsed() {
        assertEquals("00:00", WorkoutFormat.elapsed(0))
        assertEquals("01:05", WorkoutFormat.elapsed(65))
        assertEquals("1:01:01", WorkoutFormat.elapsed(3661))
        assertEquals("00:00", WorkoutFormat.elapsed(-5))
    }

    @Test
    fun pace() {
        assertEquals("5:12 /km", WorkoutFormat.pace(312.0))
        assertEquals("99:59 /km", WorkoutFormat.pace(5999.0))
        assertEquals("--:-- /km", WorkoutFormat.pace(0.0))
        assertEquals("--:-- /km", WorkoutFormat.pace(Double.NaN))
        assertEquals("--:-- /km", WorkoutFormat.pace(6000.0))
    }

    @Test
    fun distanceAndSpeed() {
        assertEquals("0 m", WorkoutFormat.distance(0.0))
        assertEquals("999 m", WorkoutFormat.distance(999.0))
        assertEquals("1.23 km", WorkoutFormat.distance(1234.0))
        assertEquals("5.4 km/h", WorkoutFormat.speed(1.5))
    }

    @Test
    fun gpsLabel() {
        assertEquals("no GPS", WorkoutFormat.gps(null, false))
        assertEquals("GPS searching", WorkoutFormat.gps(null, true))
        assertEquals("GPS ±8 m", WorkoutFormat.gps(8.4f, true))
    }

    @Test
    fun notificationLines() {
        val st = WorkoutState(
            state = WorkoutPhase.RUNNING, elapsedSeconds = 754, distanceMeters = 1230.0, paceSecPerKm = 312.0,
            speedMps = 3.2, calories = 96, lastHr = 132, avgHr = 125, maxHr = 140, gpsAccuracyM = 8f, gpsAvailable = true,
            trackPointCount = 700, acceptedPointCount = 680,
        )
        assertEquals("12:34 · 1.23 km · 5:12 /km · HR 132 · GPS ±8 m", WorkoutFormat.summary(st))
        val detail = WorkoutFormat.detail(st)
        assertTrue(detail.contains("Time 12:34   Distance 1.23 km"))
        assertTrue(detail.contains("Pace 5:12 /km   Speed 11.5 km/h"))
        assertTrue(detail.contains("HR 132 (avg 125, max 140)   96 kcal"))
        assertTrue(detail.contains("GPS ±8 m (680/700 fixes used)"))
        assertEquals("00:00 · 0 m · --:-- /km · no GPS", WorkoutFormat.summary(WorkoutState()))
    }

    @Test
    fun stateDerivedValues() {
        assertEquals(300.0, WorkoutState(elapsedSeconds = 600, distanceMeters = 2000.0).averagePaceSecPerKm, 1e-9)
        assertEquals(0.0, WorkoutState(elapsedSeconds = 600).averagePaceSecPerKm, 0.0)
        assertFalse(WorkoutState().isActive)
        assertTrue(WorkoutState(state = WorkoutPhase.PAUSED).isActive)
        assertFalse(WorkoutState(state = WorkoutPhase.PAUSED).isRunning)
        assertTrue(WorkoutState(state = WorkoutPhase.RUNNING).isRunning)
    }
}
