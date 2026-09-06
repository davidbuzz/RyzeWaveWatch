package au.buzz.ryzewave.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The one Preferences DataStore of the app: file `settings.preferences_pb` in the app's datastore dir. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * [SettingsStore] backed by Preferences DataStore. Flows emit the defaults of the core models until a value has
 * been written, and swallow read IO errors by emitting defaults (a corrupt file is not fatal for the UI).
 * Construct with a [Context] in the app; the primary constructor takes any [DataStore] for tests.
 */
class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    private val prefs: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val watchMac: Flow<String?> =
        prefs.map { SettingsKeys.readWatchMac(it) }.distinctUntilChanged()

    override val profile: Flow<UserProfile> =
        prefs.map { SettingsKeys.readProfile(it) }.distinctUntilChanged()

    override val sampling: Flow<SamplingSettings> =
        prefs.map { SettingsKeys.readSampling(it) }.distinctUntilChanged()

    override val stride: Flow<StrideSettings> =
        prefs.map { SettingsKeys.readStride(it) }.distinctUntilChanged()

    override val healthConnectEnabled: Flow<Boolean> =
        prefs.map { SettingsKeys.readHealthConnectEnabled(it) }.distinctUntilChanged()

    override val autoConnect: Flow<Boolean> =
        prefs.map { it[SettingsKeys.AUTO_CONNECT] ?: true }.distinctUntilChanged()

    override suspend fun setWatchMac(mac: String?) {
        dataStore.edit { SettingsKeys.writeWatchMac(it, mac) }
    }

    override suspend fun setProfile(p: UserProfile) {
        dataStore.edit { SettingsKeys.writeProfile(it, p) }
    }

    override suspend fun setSampling(s: SamplingSettings) {
        dataStore.edit { SettingsKeys.writeSampling(it, s) }
    }

    override suspend fun setStride(s: StrideSettings) {
        dataStore.edit { SettingsKeys.writeStride(it, s) }
    }

    override suspend fun setHealthConnectEnabled(on: Boolean) {
        dataStore.edit { SettingsKeys.writeHealthConnectEnabled(it, on) }
    }

    override suspend fun setAutoConnect(on: Boolean) {
        dataStore.edit { it[SettingsKeys.AUTO_CONNECT] = on }
    }

    override val notificationsEnabled: Flow<Boolean> =
        prefs.map { it[SettingsKeys.NOTIFICATIONS_ENABLED] ?: false }.distinctUntilChanged()

    override val allowedPackages: Flow<Set<String>> =
        prefs.map { it[SettingsKeys.NOTIFICATION_PACKAGES] ?: emptySet() }.distinctUntilChanged()

    override val forwardAllNotifications: Flow<Boolean> =
        prefs.map { it[SettingsKeys.NOTIFICATIONS_FORWARD_ALL] ?: false }.distinctUntilChanged()

    override suspend fun setNotificationsEnabled(on: Boolean) {
        dataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = on }
    }

    override suspend fun setAllowedPackages(packages: Set<String>) {
        dataStore.edit { m ->
            val clean = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (clean.isEmpty()) m.remove(SettingsKeys.NOTIFICATION_PACKAGES) else m[SettingsKeys.NOTIFICATION_PACKAGES] = clean
        }
    }

    override suspend fun setForwardAllNotifications(on: Boolean) {
        dataStore.edit { it[SettingsKeys.NOTIFICATIONS_FORWARD_ALL] = on }
    }

    override val workoutSportType: Flow<Int> =
        prefs.map { SettingsKeys.readWorkoutSportType(it) }.distinctUntilChanged()

    override suspend fun setWorkoutSportType(type: Int) {
        dataStore.edit { SettingsKeys.writeWorkoutSportType(it, type) }
    }

    override val stuckDetectorEnabled: Flow<Boolean> =
        prefs.map { it[SettingsKeys.STUCK_DETECTOR_ENABLED] ?: true }.distinctUntilChanged()

    override val stuckAutoStopAppWorkouts: Flow<Boolean> =
        prefs.map { it[SettingsKeys.STUCK_AUTO_STOP_APP] ?: false }.distinctUntilChanged()

    override suspend fun setStuckDetectorEnabled(on: Boolean) {
        dataStore.edit { it[SettingsKeys.STUCK_DETECTOR_ENABLED] = on }
    }

    override suspend fun setStuckAutoStopAppWorkouts(on: Boolean) {
        dataStore.edit { it[SettingsKeys.STUCK_AUTO_STOP_APP] = on }
    }
}

/**
 * Preference keys and the pure (de)serialisation of the core settings models. Kept free of Android so the
 * mapping is unit-testable; [DataStoreSettingsStore] is a thin wrapper around these.
 */
object SettingsKeys {
    val WATCH_MAC = stringPreferencesKey("watch_mac")

    val HEIGHT_CM = intPreferencesKey("height_cm")
    val WEIGHT_KG = intPreferencesKey("weight_kg")
    val AGE = intPreferencesKey("age")
    val MALE = booleanPreferencesKey("male")
    val STEP_GOAL = intPreferencesKey("step_goal")

    val CONTINUOUS_HR = booleanPreferencesKey("continuous_hr")
    val SPO2_AUTO = booleanPreferencesKey("spo2_auto")
    val SPO2_INTERVAL_MIN = intPreferencesKey("spo2_interval_min")
    val RAISE_WRIST_WAKE = booleanPreferencesKey("raise_wrist_wake")

    val WALK_STRIDE_M = doublePreferencesKey("walk_stride_m")
    val RUN_STRIDE_M = doublePreferencesKey("run_stride_m")

    val HEALTH_CONNECT_ENABLED = booleanPreferencesKey("health_connect_enabled")
    val AUTO_CONNECT = booleanPreferencesKey("auto_connect")

    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val NOTIFICATION_PACKAGES = stringSetPreferencesKey("notification_packages")
    val NOTIFICATIONS_FORWARD_ALL = booleanPreferencesKey("notifications_forward_all")

    val WORKOUT_SPORT_TYPE = intPreferencesKey("workout_sport_type")

    val STUCK_DETECTOR_ENABLED = booleanPreferencesKey("stuck_detector_enabled")
    val STUCK_AUTO_STOP_APP = booleanPreferencesKey("stuck_auto_stop_app_workouts")

    /** Normalised MAC (trimmed, upper case) or null when unset / blank. */
    fun readWatchMac(p: Preferences): String? = p[WATCH_MAC]?.trim()?.takeIf { it.isNotEmpty() }

    fun writeWatchMac(m: MutablePreferences, mac: String?) {
        val value = mac?.trim()?.uppercase()
        if (value.isNullOrEmpty()) m.remove(WATCH_MAC) else m[WATCH_MAC] = value
    }

    fun readProfile(p: Preferences): UserProfile {
        val d = UserProfile()
        return UserProfile(
            heightCm = p[HEIGHT_CM] ?: d.heightCm,
            weightKg = p[WEIGHT_KG] ?: d.weightKg,
            age = p[AGE] ?: d.age,
            male = p[MALE] ?: d.male,
            stepGoal = p[STEP_GOAL] ?: d.stepGoal,
        )
    }

    fun writeProfile(m: MutablePreferences, profile: UserProfile) {
        m[HEIGHT_CM] = profile.heightCm
        m[WEIGHT_KG] = profile.weightKg
        m[AGE] = profile.age
        m[MALE] = profile.male
        m[STEP_GOAL] = profile.stepGoal
    }

    fun readSampling(p: Preferences): SamplingSettings {
        val d = SamplingSettings()
        return SamplingSettings(
            continuousHr = p[CONTINUOUS_HR] ?: d.continuousHr,
            spo2AutoEnabled = p[SPO2_AUTO] ?: d.spo2AutoEnabled,
            spo2IntervalMin = p[SPO2_INTERVAL_MIN] ?: d.spo2IntervalMin,
            raiseWristWake = p[RAISE_WRIST_WAKE] ?: d.raiseWristWake,
        )
    }

    fun writeSampling(m: MutablePreferences, s: SamplingSettings) {
        m[CONTINUOUS_HR] = s.continuousHr
        m[SPO2_AUTO] = s.spo2AutoEnabled
        m[SPO2_INTERVAL_MIN] = s.spo2IntervalMin
        m[RAISE_WRIST_WAKE] = s.raiseWristWake
    }

    /** Absent keys mean "derive from height" (null), see core.DistanceModel. */
    fun readStride(p: Preferences): StrideSettings =
        StrideSettings(walkStrideM = p[WALK_STRIDE_M], runStrideM = p[RUN_STRIDE_M])

    fun writeStride(m: MutablePreferences, s: StrideSettings) {
        val walk = s.walkStrideM
        if (walk == null || walk <= 0.0) m.remove(WALK_STRIDE_M) else m[WALK_STRIDE_M] = walk
        val run = s.runStrideM
        if (run == null || run <= 0.0) m.remove(RUN_STRIDE_M) else m[RUN_STRIDE_M] = run
    }

    fun readHealthConnectEnabled(p: Preferences): Boolean = p[HEALTH_CONNECT_ENABLED] ?: false

    fun writeHealthConnectEnabled(m: MutablePreferences, on: Boolean) {
        m[HEALTH_CONNECT_ENABLED] = on
    }

    /** Sport id for the next workout; absent or out of range = 1 (Outdoor Running). */
    fun readWorkoutSportType(p: Preferences): Int =
        p[WORKOUT_SPORT_TYPE]?.takeIf { it in 1..255 } ?: SettingsStore.DEFAULT_WORKOUT_SPORT_TYPE

    fun writeWorkoutSportType(m: MutablePreferences, type: Int) {
        m[WORKOUT_SPORT_TYPE] = type.coerceIn(1, 255)
    }
}
