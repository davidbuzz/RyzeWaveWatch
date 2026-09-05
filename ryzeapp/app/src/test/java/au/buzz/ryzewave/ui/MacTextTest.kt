package au.buzz.ryzewave.ui

import au.buzz.ryzewave.health.ExportCounts
import au.buzz.ryzewave.health.ExportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacTextTest {
    /** Settings > Watch keeps the raw text while typing; Save (and the "changed" test) use the normalised form. */
    @Test
    fun normaliseTrimsAndUpperCases() {
        assertEquals("78:02:B7:37:91:E5", MacText.normalise(" 78:02:b7:37:91:e5 "))
        assertEquals("AA:BB:CC:DD:EE:FF", MacText.normalise("aa:bb:cc:dd:ee:ff"))
        assertEquals("ABCDEF", MacText.normalise("abcdef"))
        assertEquals("", MacText.normalise("   "))
    }

    @Test
    fun validityIsJudgedOnTheNormalisedText() {
        assertTrue(MacText.isValid("78:02:b7:37:91:e5"))
        assertTrue(MacText.isValid(" 78:02:B7:37:91:E5\n"))
        assertFalse(MacText.isValid("78:02:B7:37:91"))
        assertFalse(MacText.isValid("78-02-B7-37-91-E5"))
        assertFalse(MacText.isValid("abcdef"))
        assertFalse(MacText.isValid(""))
    }

    /** The snackbar after "Export now": a no-op export must not read "Exported 0 records". */
    @Test
    fun exportMessageMentionsUnchangedRecords() {
        val none = ExportResult(ExportResult.Status.NOTHING_TO_EXPORT, skipped = 230)
        assertEquals("Nothing new to export: 230 records already in Health Connect", exportMessage(none))
        assertEquals("Nothing to export yet", exportMessage(ExportResult(ExportResult.Status.NOTHING_TO_EXPORT)))
        assertEquals("Exported 3 records to Health Connect", exportMessage(ExportResult(ExportResult.Status.OK, ExportCounts(distance = 3), inserted = 3, skipped = 227)))
        assertEquals("Exported 3 records to Health Connect, 1 rejected", exportMessage(ExportResult(ExportResult.Status.OK, inserted = 3, failed = 1)))
        assertEquals("Health Connect export failed: no permission", exportMessage(ExportResult(ExportResult.Status.NO_PERMISSION, message = "no permission")))
        assertEquals("Health Connect export failed: UNAVAILABLE", exportMessage(ExportResult(ExportResult.Status.UNAVAILABLE)))
    }
}
