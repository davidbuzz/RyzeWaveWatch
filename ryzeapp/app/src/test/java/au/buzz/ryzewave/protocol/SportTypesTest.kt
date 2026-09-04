package au.buzz.ryzewave.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportTypesTest {
    @Test
    fun seventySportsWithTheWatchMenuNames() {
        assertEquals(70, SportTypes.NAMES.size)
        assertEquals("Outdoor Running", SportTypes.name(0x01))
        assertEquals("Outdoor Walking", SportTypes.name(0x23))
        assertEquals("Marathon", SportTypes.name(0x73))
        assertEquals("Cycling", SportTypes.name(0x02))
        assertEquals("Sport 3", SportTypes.name(0x03))     // a gap in the id space
        assertEquals("Sport 200", SportTypes.name(200))
        assertEquals(1, SportTypes.DEFAULT)
    }

    @Test
    fun popularListIsTenKnownSportsInPickerOrder() {
        assertEquals(listOf(0x01, 0x23, 0x09, 0x02, 0x08, 0x24, 0x15, 0x1B, 0x04, 0x19), SportTypes.POPULAR)
        assertEquals(
            listOf("Outdoor Running", "Outdoor Walking", "Walking", "Cycling", "Hiking", "Trail Running", "Treadmill",
                "Indoor Running", "Swimming", "Free Training"),
            SportTypes.POPULAR.map { SportTypes.name(it) },
        )
        assertTrue(SportTypes.POPULAR.all { it in SportTypes.NAMES })
    }

    @Test
    fun byNameCoversEverySportSortedAlphabetically() {
        assertEquals(70, SportTypes.byName.size)
        assertEquals(SportTypes.NAMES.keys, SportTypes.byName.map { it.first }.toSet())
        val names = SportTypes.byName.map { it.second.lowercase() }
        assertEquals(names.sorted(), names)
        assertEquals("Aerobic Combo", SportTypes.byName.first().second)
    }

    @Test
    fun gpsSportsAndTheTypeOneTieBreaker() {
        assertEquals(setOf(0x01, 0x02, 0x08, 0x09, 0x23, 0x24), SportTypes.GPS_SPORTS)
        assertTrue(SportTypes.isGps(0x23))
        assertFalse(SportTypes.isGps(0x04))
        assertEquals(0x23, SportTypes.effectiveId(0x01, 1.5))     // a walk recorded as type 1
        assertEquals(0x01, SportTypes.effectiveId(0x01, 2.5))
        assertEquals(0x01, SportTypes.effectiveId(0x01, 2.0))
        assertEquals(0x02, SportTypes.effectiveId(0x02, 0.5))     // only type 1 is ambiguous
        assertEquals(0x23, SportTypes.effectiveId(0x23, 3.0))
    }
}
