package au.buzz.ryzewave.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationFilterTest {
    private var now = 1_800_000_000_000L
    private val filter = NotificationFilter("au.buzz.ryzewave") { now }
    private val allowed = setOf("com.whatsapp", "com.android.shell")

    private fun n(
        pkg: String = "com.whatsapp", key: String = "0|com.whatsapp|1|null|10123", title: String? = "Jane",
        text: String? = "Running late", label: String? = "WhatsApp", ongoing: Boolean = false, summary: Boolean = false,
    ) = PostedNotification(pkg, key, title, text, label, ongoing, summary)

    @Test
    fun allowedAppGetsTitleColonTextAndItsIconType() {
        val out = filter.decide(n(), allowed, forwardAll = false)
        assertEquals(OutgoingNotification(7, "Jane: Running late", "com.whatsapp"), out)
    }

    @Test
    fun titleOnlyAndTextOnlyUseTheAppLabel() {
        assertEquals("WhatsApp: Jane", NotificationFilter.messageText(n(text = null)))
        assertEquals("WhatsApp: Running late", NotificationFilter.messageText(n(title = "  ")))
        assertEquals("shell: Hello", NotificationFilter.messageText(n(pkg = "com.android.shell", title = null, text = "Hello", label = null)))
        assertNull(NotificationFilter.messageText(n(title = null, text = "")))
        assertNull(filter.decide(n(title = null, text = null), allowed, false))
        assertEquals("no text", filter.lastDropReason)
    }

    @Test
    fun ongoingSummaryOwnAndUnlistedAreDropped() {
        assertNull(filter.decide(n(ongoing = true), allowed, false))
        assertEquals("ongoing", filter.lastDropReason)
        assertNull(filter.decide(n(summary = true), allowed, false))
        assertEquals("group summary", filter.lastDropReason)
        assertNull(filter.decide(n(pkg = "au.buzz.ryzewave"), setOf("au.buzz.ryzewave"), true))
        assertEquals("own notification", filter.lastDropReason)
        assertNull(filter.decide(n(pkg = "com.example.other"), allowed, false))
        assertEquals("package not allowed", filter.lastDropReason)
        assertNotNull(filter.decide(n(pkg = "com.example.other"), allowed, forwardAll = true))
        assertEquals(4, filter.decide(n(pkg = "com.example.other2", key = "0|com.example.other2|7|null|10200"), allowed, true)!!.type)
    }

    @Test
    fun duplicateKeyAndTextWithinTenSecondsIsDropped() {
        assertNotNull(filter.decide(n(), allowed, false))
        now += 5_000
        assertNull(filter.decide(n(), allowed, false))
        assertEquals("duplicate within 10 s", filter.lastDropReason)
        assertNotNull(filter.decide(n(text = "Now really late"), allowed, false))   // same key, new text
        assertNotNull(filter.decide(n(key = "other"), allowed, false))               // other key, same text
        now += 6_000                                                                 // 11 s after the first
        assertNotNull(filter.decide(n(), allowed, false))
    }
}
