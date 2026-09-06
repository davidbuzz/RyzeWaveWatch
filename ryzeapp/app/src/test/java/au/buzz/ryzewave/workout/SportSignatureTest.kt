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
        assertEquals(SportClass.RIDE, SportSignature.classOf(0x29))                // Rower (on water)
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
}
