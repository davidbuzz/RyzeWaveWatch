package au.buzz.ryzewave.data

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.StepsHour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [newOrChanged] decides which rows of a re-delivered batch actually get written (and re-stamped). */
class ChangeDetectionTest {
    private val h14 = StepsHour(hourStart = 14L, total = 100, walk = 90, run = 10)
    private val h15 = StepsHour(hourStart = 15L, total = 50, walk = 50, run = 0)

    @Test
    fun everythingIsNewWhenNothingExists() {
        assertEquals(listOf(h14, h15), newOrChanged(listOf(h14, h15), emptyList()) { it.hourStart })
    }

    @Test
    fun identicalRedeliveryIsDropped() {
        assertTrue(newOrChanged(listOf(h14, h15), listOf(h14, h15)) { it.hourStart }.isEmpty())
    }

    @Test
    fun grownHourRowIsKeptOthersDropped() {
        val h15grown = h15.copy(total = 320, walk = 300, run = 20)
        assertEquals(listOf(h15grown), newOrChanged(listOf(h14, h15grown), listOf(h14, h15)) { it.hourStart })
    }

    @Test
    fun newRowsPassAlongsideExisting() {
        val h16 = StepsHour(16L, 5, 5, 0)
        assertEquals(listOf(h16), newOrChanged(listOf(h14, h16), listOf(h14, h15)) { it.hourStart })
    }

    @Test
    fun compositeKeySeparatesSources() {
        val history = HrSample(1_000L, 70, SampleSource.HISTORY)
        val auto = HrSample(1_000L, 70, SampleSource.AUTO)
        val changed = newOrChanged(listOf(history, auto), listOf(history)) { it.time to it.source }
        assertEquals(listOf(auto), changed)
        val sameSlotNewValue = HrSample(1_000L, 72, SampleSource.HISTORY)
        assertEquals(listOf(sameSlotNewValue), newOrChanged(listOf(sameSlotNewValue), listOf(history)) { it.time to it.source })
    }
}
