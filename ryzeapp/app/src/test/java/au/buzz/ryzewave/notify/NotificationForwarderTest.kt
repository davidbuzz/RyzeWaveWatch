package au.buzz.ryzewave.notify

import au.buzz.ryzewave.ble.FakeRepo
import au.buzz.ryzewave.ble.FakeSettings
import au.buzz.ryzewave.ble.FakeWatchLink
import au.buzz.ryzewave.ble.WatchApiImpl
import au.buzz.ryzewave.ble.eventually
import au.buzz.ryzewave.core.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The forwarder on top of the real [WatchApiImpl] and the fake link that acks every `C5` chunk like the watch. */
class NotificationForwarderTest {
    private var now = 1_800_000_000_000L
    private lateinit var link: FakeWatchLink
    private lateinit var settings: FakeSettings
    private lateinit var scope: CoroutineScope
    private lateinit var api: WatchApiImpl
    private lateinit var forwarder: NotificationForwarder
    private val logLines = ArrayList<String>()

    @Before
    fun setUp() {
        link = FakeWatchLink { now }
        settings = FakeSettings()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        api = WatchApiImpl(link, FakeRepo(), settings, scope, autoSetupOnConnect = false, clock = { now })
        forwarder = NotificationForwarder(settings, api, scope, "au.buzz.ryzewave", { now }) { m, _ -> logLines += m }
        // the watch acks every chunk with `C5 <idx>` and the end with `C5 FD <type> <total>`
        link.responder = { hex, _ ->
            if (hex.startsWith("c5fd")) link.rx("c5fd0450") else if (hex.startsWith("c5")) link.rx(hex.substring(0, 4))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun post(pkg: String = "com.android.shell", key: String = "0|$pkg|1|tag1|2000", text: String = "Hello from adb") =
        PostedNotification(pkg, key, "Test title", text, "Shell")

    @Test
    fun forwardsAnAllowedAppWhenEnabledAndConnected() = runBlocking {
        link.connect("78:02:B7:37:91:E5")
        assertTrue(eventually { api.status.value.state == ConnectionState.CONNECTED })
        settings.setNotificationsEnabled(true)
        settings.setAllowedPackages(setOf("com.android.shell"))
        assertTrue(eventually { forwarder.enabled.value && forwarder.allowedPackages.value.isNotEmpty() })

        assertTrue(forwarder.offer(post()))
        assertTrue(eventually { forwarder.sentCount == 1 })
        val tx = link.txHex()
        // "Test title: Hello from adb" = 26 chars = 52 bytes = 4 chunks (16+16+16+4) + end
        assertEquals(5, tx.size)
        assertEquals("c5000434" + "00540065007300740020007400690074", tx[0])
        assertEquals("c5fd", tx.last())
        assertEquals("c503" + "00640062", tx[3])                 // last chunk carries the final "db"

        // same key + text again within 10 s: dropped; a new text goes through
        assertFalse(forwarder.offer(post()))
        now += 1_000
        assertTrue(forwarder.offer(post(text = "Second")))
        assertTrue(eventually { forwarder.sentCount == 2 })
        assertEquals(1, forwarder.droppedCount)              // exactly one drop (the duplicate)
    }

    @Test
    fun masterSwitchAllowListAndLinkStateGateTheSend() = runBlocking {
        assertFalse(forwarder.offer(post()))                    // disabled
        assertTrue(logLines.last().endsWith("notifications off"))
        settings.setNotificationsEnabled(true)
        assertTrue(eventually { forwarder.enabled.value })
        assertFalse(forwarder.offer(post()))                    // not in the allow-list
        assertTrue(logLines.last().endsWith("package not allowed"))
        settings.setForwardAllNotifications(true)
        assertTrue(eventually { forwarder.forwardAll.value })
        assertFalse(forwarder.offer(post()))                    // watch not connected: dropped, not queued
        assertTrue(logLines.last().endsWith("watch not connected"))
        assertTrue(link.txHex().isEmpty())
        link.connect("78:02:B7:37:91:E5")
        assertTrue(eventually { api.status.value.state == ConnectionState.CONNECTED })
        assertTrue(forwarder.offer(post(key = "new")))
        assertTrue(eventually { forwarder.sentCount == 1 })
        assertEquals(3, forwarder.droppedCount)               // off, not allowed, not connected
    }

    @Test
    fun testMessageBypassesTheSwitchAndIsTypeFour() = runBlocking {
        link.connect("78:02:B7:37:91:E5")
        assertTrue(forwarder.sendTest())
        val tx = link.txHex()
        assertEquals("c500042c" + "00420075007a007a0027007300200052", tx[0])   // "Buzz's Ryze Wave: test" = 22 chars = 0x2c bytes
        assertEquals("c5fd", tx.last())
        assertEquals(4, tx.size)
        link.disconnect()
        assertFalse(forwarder.sendTest())                       // skipped when not connected
        assertEquals(4, link.txHex().size)
    }

    @Test
    fun missingAckIsReportedNotThrown() = runBlocking {
        link.connect("78:02:B7:37:91:E5")
        link.responder = { _, _ -> }                            // the watch never answers
        val api2 = WatchApiImpl(link, FakeRepo(), settings, scope, autoSetupOnConnect = false, clock = { now })
        val f = NotificationForwarder(settings, api2, scope, "au.buzz.ryzewave", { now }) { m, _ -> logLines += m }
        assertFalse(f.sendTest())
        assertTrue(f.lastError!!.contains("no reply"))
        assertEquals(1, f.droppedCount)
    }
}
