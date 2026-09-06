package au.buzz.ryzewave.workout

import au.buzz.ryzewave.protocol.SportTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportSignatureTest {
    @Test
    fun everyWatchSportHasAClassAndTheTableMatchesThePlan() {
        for (id in SportTypes.NAMES.keys) assertTrue("sport $id unclassified", SportSignature.CLASSES.containsKey(id))
        assertEquals(SportClass.MOVEMENT, SportSignature.classOf(0x01))            // Outdoor Running
        assertEquals(SportClass.MOVEMENT, SportSignature.classOf(0x23))            // Outdoor Walking
        assertEquals(SportClass.INDOOR_STEPS, SportSignature.classOf(0x15))        // Treadmill
        assertEquals(SportClass.RIDE, SportSignature.classOf(0x02))                // Cycling
        assertEquals(SportClass.STATIONARY_MACHINE, SportSignature.classOf(0x29))  // Rower (erg or on water)
        assertEquals(SportClass.STATIONARY_MACHINE, SportSignature.classOf(0x1F))  // Elliptical
        assertEquals(SportClass.STATIONARY_CARDIO, SportSignature.classOf(0x12))   // Spinning
        assertEquals(SportClass.FLOOR_WORK, SportSignature.classOf(0x13))          // Yoga
        assertEquals(SportClass.STRENGTH, SportSignature.classOf(0x61))            // HIIT
        assertEquals(SportClass.UNMONITORED, SportSignature.classOf(0x04))         // Swimming
        assertEquals(SportClass.UNKNOWN, SportSignature.classOf(0x7F))
        assertEquals(SportClass.UNKNOWN, SportSignature.classOf(null))
    }

    @Test
    fun expectedIndicatorsPerClass() {
        assertEquals(setOf(Indicator.STEPS, Indicator.GPS, Indicator.HR, Indicator.MOTION), SportSignature.expected(0x01))
        assertEquals(setOf(Indicator.STEPS, Indicator.HR, Indicator.MOTION), SportSignature.expected(0x15))
        assertEquals(setOf(Indicator.GPS, Indicator.HR, Indicator.MOTION), SportSignature.expected(0x02))
        assertEquals(setOf(Indicator.HR, Indicator.MOTION), SportSignature.expected(0x12))
        assertEquals(setOf(Indicator.MOTION), SportSignature.expected(0x13))
        assertTrue(SportSignature.expected(0x04).isEmpty())
        assertTrue(SportSignature.describe(0x13).contains("Yoga"))
    }

    @Test
    fun anIndoorMachineNeverExpectsTheWearerToTravel() {
        // A rowing erg and an elliptical keep the body in one place, so demanding GPS movement of them would
        // flag a real session as stuck. What does move is the wrist, once per stroke, so the watch's own counter
        // is the liveness signal - and it survives a phone left on a shelf and a heart rate still climbing.
        for (machine in listOf(0x29 /* Rower */, 0x1F /* Elliptical */)) {
            val expected = SportSignature.expected(machine)
            assertTrue("sport $machine must not expect GPS", Indicator.GPS !in expected)
            assertTrue("sport $machine must accept the wrist counter", Indicator.STEPS in expected)
            assertTrue("sport $machine must accept heart rate", Indicator.HR in expected)
        }
    }

    @Test
    fun aQuietErgSessionIsNotCalledStuck() {
        // The failure this class exists to prevent: rowing gently with the phone on a shelf. GPS is stationary and
        // the phone is still, but the stroke counter keeps rising, so the session is live.
        val verdict = StuckModeDetector.evaluate(
            expected = SportSignature.expected(0x29),
            states = mapOf(
                Indicator.GPS to SignalState.INACTIVE,
                Indicator.MOTION to SignalState.INACTIVE,
                Indicator.HR to SignalState.INACTIVE,
                Indicator.STEPS to SignalState.ACTIVE,      // strokes
            ),
            windowCovered = true, night = false, hrSleeping = false,
        )
        assertTrue(verdict.reason, verdict.live)
        assertTrue(verdict.reason, !verdict.likelyStuck)
    }

    @Test
    fun spinningStillDoesNotExpectTheWristToMove() {
        // Hands on the bars: the counter does not rise, so it must not be required as evidence of life.
        assertTrue(Indicator.STEPS !in SportSignature.expected(0x12))
        assertTrue(Indicator.GPS !in SportSignature.expected(0x12))
    }
}
