package au.buzz.ryzewave.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreSettingsStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "settings.preferences_pb")
        }
        store = DataStoreSettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultsBeforeAnyWrite() = runBlocking {
        assertNull(store.watchMac.first())
        assertEquals(UserProfile(), store.profile.first())
        assertEquals(SamplingSettings(), store.sampling.first())
        assertEquals(StrideSettings(), store.stride.first())
        assertFalse(store.healthConnectEnabled.first())
    }

    @Test
    fun notificationSettingsDefaultOffAndRoundTrip() = runBlocking {
        assertFalse(store.notificationsEnabled.first())
        assertEquals(emptySet<String>(), store.allowedPackages.first())
        assertFalse(store.forwardAllNotifications.first())
        store.setNotificationsEnabled(true)
        store.setAllowedPackages(setOf("com.whatsapp", " com.android.shell ", ""))
        store.setForwardAllNotifications(true)
        assertTrue(store.notificationsEnabled.first())
        assertEquals(setOf("com.whatsapp", "com.android.shell"), store.allowedPackages.first())
        assertTrue(store.forwardAllNotifications.first())
        store.setAllowedPackages(emptySet())
        assertEquals(emptySet<String>(), store.allowedPackages.first())
        store.setNotificationsEnabled(false)
        assertFalse(store.notificationsEnabled.first())
    }

    @Test
    fun writesReadBack() = runBlocking {
        store.setWatchMac(" 78:02:b7:37:91:e5 ")
        assertEquals("78:02:B7:37:91:E5", store.watchMac.first())
        store.setWatchMac(null)
        assertNull(store.watchMac.first())

        val profile = UserProfile(heightCm = 175, weightKg = 75, age = 40, male = true, stepGoal = 8000)
        store.setProfile(profile)
        assertEquals(profile, store.profile.first())

        val sampling = SamplingSettings(continuousHr = false, spo2AutoEnabled = true, spo2IntervalMin = 5, raiseWristWake = false)
        store.setSampling(sampling)
        assertEquals(sampling, store.sampling.first())

        store.setStride(StrideSettings(walkStrideM = 0.74, runStrideM = 0.99))
        assertEquals(StrideSettings(0.74, 0.99), store.stride.first())
        store.setStride(StrideSettings(walkStrideM = 0.74, runStrideM = null))
        assertEquals(StrideSettings(0.74, null), store.stride.first())

        store.setHealthConnectEnabled(true)
        assertTrue(store.healthConnectEnabled.first())
    }

    @Test
    fun keysMapPurely() {
        val m = mutablePreferencesOf()
        SettingsKeys.writeStride(m, StrideSettings(walkStrideM = 0.0, runStrideM = 1.1))
        assertEquals(StrideSettings(null, 1.1), SettingsKeys.readStride(m))
        SettingsKeys.writeWatchMac(m, "   ")
        assertNull(SettingsKeys.readWatchMac(m))
        SettingsKeys.writeProfile(m, UserProfile(heightCm = 170))
        assertEquals(170, SettingsKeys.readProfile(m).heightCm)
        assertEquals(UserProfile().stepGoal, SettingsKeys.readProfile(m).stepGoal)
    }
}
