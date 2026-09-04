package au.buzz.ryzewave.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the two verified notification bursts from the phone bridge (2026-09-05 07:39-07:40):
 * captures/bridge_20260905_073950.txt (40-char generic, type 4, 5 chunks, acked `C5 FD 04 50`) and
 * captures/bridge_20260905_074036.txt (120-char SMS, type 3, 15 chunks, acked `C5 FD 03 F0`).
 */
class NotificationEncoderTest {

    private fun ByteArray.hex(): String = Protocol.hex(this)

    @Test
    fun genericFortyCharMessageMatchesTheCapture() {
        val chunks = Protocol.encNotification(NotificationType.GENERIC, "Buzz's Ryze Wave: hello from the new app")
        assertEquals(5, chunks.size)
        assertEquals("c500045000420075007a007a0027007300200052", chunks[0].hex())
        assertEquals("c5010079007a006500200057006100760065", chunks[1].hex())
        assertEquals("c502003a002000680065006c006c006f0020", chunks[2].hex())
        assertEquals("c50300660072006f006d0020007400680065", chunks[3].hex())
        assertEquals("c5040020006e006500770020006100700070", chunks[4].hex())
        assertEquals("c5fd", Protocol.encNotificationEnd().hex())
    }

    @Test
    fun smsOf120CharsIs15ChunksWithTotalF0() {
        val text = "SMS Jane: Running late, the 120-character limit test is now being sent to your wrist to see how the watch wraps long tex"
        assertEquals(120, text.length)
        val chunks = Protocol.encNotification(NotificationType.SMS, text)
        assertEquals(15, chunks.size)
        assertEquals("c50003f00053004d00530020004a0061006e0065", chunks[0].hex())
        assertEquals("c501003a002000520075006e006e0069006e", chunks[1].hex())
        assertEquals("c50e006c006f006e00670020007400650078", chunks[14].hex())
        for ((i, c) in chunks.withIndex()) {
            assertEquals(0xC5, c[0].toInt() and 0xFF)
            assertEquals(i, c[1].toInt() and 0xFF)
            assertEquals(if (i == 0) 20 else 18, c.size)
        }
        assertEquals(0xF0, chunks[0][3].toInt() and 0xFF)
    }

    @Test
    fun shortAndUnevenTextsGetAShortLastChunk() {
        val one = Protocol.encNotification(4, "Hi")
        assertEquals(1, one.size)
        assertEquals("c50004040048" + "0069", one[0].hex())
        val nine = Protocol.encNotification(4, "123456789")
        assertEquals(2, nine.size)
        assertEquals(20, nine[0].size)
        assertEquals("c5010039", nine[1].hex())
    }

    @Test
    fun textIsCutAt127CharsSoTheTotalFitsInOneByte() {
        val long = "x".repeat(300)
        val chunks = Protocol.encNotification(4, long)
        assertEquals(254, chunks[0][3].toInt() and 0xFF)
        assertEquals(16, chunks.size)                       // 254 / 16 = 15.9
        assertEquals(15, chunks.last()[1].toInt() and 0xFF)   // index never reaches FD
        assertEquals(2 + 14, chunks.last().size)
        val total = chunks.sumOf { it.size - (if (it[1].toInt() == 0) 4 else 2) }
        assertEquals(254, total)
    }

    @Test
    fun emojiAndControlCharactersAreStrippedBeforeEncoding() {
        assertEquals("Hi there", NotificationText.sanitize("Hi 😀 there ❤️"))
        assertEquals("line one line two", NotificationText.sanitize("line one\nline two\t"))
        assertEquals("café über 中文", NotificationText.sanitize("  café  über 中文 "))
        assertEquals("", NotificationText.sanitize("😀👍"))
        assertTrue(Protocol.encNotification(4, "😀").isEmpty())
        assertTrue(Protocol.encNotification(4, "   ").isEmpty())
        assertEquals("c500040400480069", Protocol.encNotification(4, "H​i😀")[0].hex())
    }

    @Test
    fun callTypeIsRefused() {
        try {
            Protocol.encNotification(NotificationType.CALL, "x")
            throw AssertionError("type 0 must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("call"))
        }
    }

    @Test
    fun packageToTypeMapFollowsSection6() {
        assertEquals(3, NotificationType.forPackage("com.google.android.apps.messaging"))
        assertEquals(3, NotificationType.forPackage("com.android.mms"))
        assertEquals(7, NotificationType.forPackage("com.whatsapp"))
        assertEquals(24, NotificationType.forPackage("org.telegram.messenger"))
        assertEquals(5, NotificationType.forPackage("com.facebook.katana"))
        assertEquals(9, NotificationType.forPackage("com.facebook.orca"))
        assertEquals(13, NotificationType.forPackage("com.instagram.android"))
        assertEquals(19, NotificationType.forPackage("com.google.android.gm"))
        assertEquals(19, NotificationType.forPackage("com.microsoft.office.outlook"))
        assertEquals(19, NotificationType.forPackage("com.android.email"))
        assertEquals(19, NotificationType.forPackage("org.example.fastmail"))
        assertEquals(4, NotificationType.forPackage("com.android.shell"))
        assertEquals(4, NotificationType.forPackage("android"))
        assertEquals(4, NotificationType.forPackage(""))
        assertTrue(NotificationType.forPackage("com.android.dialer") != NotificationType.CALL)
    }
}
