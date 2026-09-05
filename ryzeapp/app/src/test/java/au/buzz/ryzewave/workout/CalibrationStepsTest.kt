package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which per-workout step count stride calibration uses — watch, else phone, else the honest message. */
class CalibrationStepsTest {
    private fun workout(steps: Int? = null, phoneSteps: Int? = null) = Workout(
        id = 1, start = 0, end = 1_000, sportType = 1, distanceMeters = 1_000.0,
        durationSeconds = 600, avgHr = null, maxHr = null, calories = 0,
        steps = steps, phoneSteps = phoneSteps,
    )

    @Test
    fun watchStepsPreferredWhenEnough() {
        val r = DefaultStrideModel.calibrationSteps(workout(steps = 900, phoneSteps = 800), minSteps = 200)
        assertEquals(CalibrationSteps.Use(900, "watch"), r)
    }

    @Test
    fun phoneStepsUsedWhenWatchStepsMissingOrTooFew() {
        assertEquals(
            CalibrationSteps.Use(800, "phone"),
            DefaultStrideModel.calibrationSteps(workout(steps = null, phoneSteps = 800), minSteps = 200),
        )
        assertEquals(
            CalibrationSteps.Use(800, "phone"),
            DefaultStrideModel.calibrationSteps(workout(steps = 50, phoneSteps = 800), minSteps = 200),
        )
    }

    @Test
    fun honestMessageWhenNeitherSourceHasEnough() {
        val r = DefaultStrideModel.calibrationSteps(workout(steps = null, phoneSteps = null), minSteps = 200)
        assertTrue(r is CalibrationSteps.Unavailable)
        assertEquals(DefaultStrideModel.NO_PER_WORKOUT_STEPS_MESSAGE, (r as CalibrationSteps.Unavailable).message)

        val r2 = DefaultStrideModel.calibrationSteps(workout(steps = 10, phoneSteps = 20), minSteps = 200)
        assertTrue(r2 is CalibrationSteps.Unavailable)
    }
}
