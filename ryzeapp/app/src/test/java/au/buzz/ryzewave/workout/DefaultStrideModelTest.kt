package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultStrideModelTest {
    private val model = DefaultStrideModel()
    private val male182 = UserProfile(heightCm = 182, male = true)
    private val female170 = UserProfile(heightCm = 170, male = false)

    @Test
    fun vendorFactorsByGender() {
        assertEquals(0.7462, model.walkStrideM(male182, StrideSettings()), 1e-9)
        assertEquals(0.99372, model.runStrideM(male182, StrideSettings()), 1e-9)
        assertEquals(0.7055, model.walkStrideM(female170, StrideSettings()), 1e-9)
        assertEquals(0.8585, model.runStrideM(female170, StrideSettings()), 1e-9)
    }

    @Test
    fun calibratedOverrideWins() {
        val both = StrideSettings(walkStrideM = 0.80, runStrideM = 1.10)
        assertEquals(0.80, model.walkStrideM(male182, both), 0.0)
        assertEquals(1.10, model.runStrideM(male182, both), 0.0)
        val walkOnly = StrideSettings(walkStrideM = 0.80)
        assertEquals(0.80, model.walkStrideM(male182, walkOnly), 0.0)
        assertEquals(0.99372, model.runStrideM(male182, walkOnly), 1e-9)
    }

    @Test
    fun invalidCalibrationFallsBackToHeight() {
        assertEquals(0.7462, model.walkStrideM(male182, StrideSettings(walkStrideM = 0.0)), 1e-9)
        assertEquals(0.7462, model.walkStrideM(male182, StrideSettings(walkStrideM = -1.0)), 1e-9)
        assertEquals(0.99372, model.runStrideM(male182, StrideSettings(runStrideM = Double.NaN)), 1e-9)
    }

    @Test
    fun stepsToMetersUsesBothStrides() {
        assertEquals(746.2 + 99.372, model.stepsToMeters(1000, 100, male182, StrideSettings()), 1e-6)
        assertEquals(0.0, model.stepsToMeters(0, 0, male182, StrideSettings()), 0.0)
    }

    @Test
    fun calibrationFromGpsWalk() {
        assertEquals(0.75, DefaultStrideModel.calibratedStrideM(1000, 750.0)!!, 1e-9)
        assertNull(DefaultStrideModel.calibratedStrideM(100, 750.0))     // too few steps
        assertNull(DefaultStrideModel.calibratedStrideM(1000, 100.0))    // too short
        assertNull(DefaultStrideModel.calibratedStrideM(1000, 2000.0))   // 2 m per step: nonsense
        assertNull(DefaultStrideModel.calibratedStrideM(1000, Double.NaN))
    }
}
