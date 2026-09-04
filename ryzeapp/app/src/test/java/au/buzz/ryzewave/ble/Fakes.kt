package au.buzz.ryzewave.ble

import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.protocol.Features
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory transport for the API tests: records every write and answers through [responder] (synchronously,
 * from inside `write`, exactly as if the notification raced the write callback) or through [rx] from the test.
 */
class FakeWatchLink(private val clock: () -> Long = System::currentTimeMillis) : BaseWatchLink() {

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected(0))
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    override var features: Features? = Features.parseHex(FEATURES_HEX)

    /** Everything written, as (channel, hex); copy-on-write because tests read it from another thread. */
    val tx = CopyOnWriteArrayList<Pair<WatchChannel, String>>()

    private val handlers = HashMap<String, (String, WatchChannel) -> Unit>()
    private val prefixHandlers = ArrayList<Pair<String, (String, WatchChannel) -> Unit>>()

    /** Called for each write; reply with [rx]. Defaults to the scripts registered with [on] / [onPrefix]. */
    var responder: (String, WatchChannel) -> Unit = { hex, channel -> scripted(hex, channel) }

    private fun scripted(hex: String, channel: WatchChannel) {
        val exact = handlers[hex]
        if (exact != null) {
            exact(hex, channel)
            return
        }
        val prefixed = prefixHandlers.firstOrNull { hex.startsWith(it.first) }
        prefixed?.second?.invoke(hex, channel)
    }

    var failWrites = false
    private var generation = 0

    override suspend fun connect(mac: String) {
        _state.value = LinkState.Ready(mac, 247, false, ++generation)
    }

    override suspend fun disconnect() {
        failWaiters(GattException("disconnected"))
        _state.value = LinkState.Disconnected(0, "disconnected")
    }

    override suspend fun write(cmd: ByteArray, channel: WatchChannel) {
        if (failWrites || !isReady) throw GattException("write ${channel.writeName}: not connected")
        val hex = cmd.toHex()
        tx += channel to hex
        responder(hex, channel)
    }

    /** Exact-match reply script: when [txHex] is written, deliver [replies] (hex, CMD channel unless prefixed with "data:"). */
    fun on(txHex: String, vararg replies: String) {
        val h: (String, WatchChannel) -> Unit = { _, _ -> deliver(replies) }
        handlers[txHex.lowercase()] = h
    }

    /** Prefix-match reply script (e.g. "f7fa" for any since-stamp). */
    fun onPrefix(prefix: String, vararg replies: String) {
        val h: (String, WatchChannel) -> Unit = { _, _ -> deliver(replies) }
        prefixHandlers += Pair(prefix.lowercase(), h)
    }

    private fun deliver(replies: Array<out String>) {
        for (r in replies) {
            if (r.startsWith("data:")) rx(r.removePrefix("data:"), WatchChannel.DATA) else rx(r)
        }
    }

    /** A notification from the watch. */
    fun rx(hex: String, channel: WatchChannel = WatchChannel.CMD): RawPacket = dispatch(channel, hex.hexToBytes(), clock())

    fun dropLink(status: Int = 8) {
        failWaiters(GattException("link lost (status $status)"))
        _state.value = LinkState.Disconnected(status, "link lost (status $status)")
    }

    /** Reasons passed to [resetLink] (the API layer found the link dead). */
    val resets = CopyOnWriteArrayList<String>()

    override suspend fun resetLink(reason: String) {
        resets += reason
        failWaiters(GattException("link reset: $reason"))
        _state.value = LinkState.Disconnected(8, reason)
    }

    fun txHex(): List<String> = tx.map { it.second }

    companion object {
        const val FEATURES_HEX = "080A642A210C3943756EDFFED921005D784BA1D4"
    }
}

class FakeRepo : HealthRepository {
    val steps = LinkedHashMap<Long, StepsHour>()
    val hr = LinkedHashMap<Pair<Long, String>, HrSample>()
    val spo2 = LinkedHashMap<Pair<Long, String>, Spo2Sample>()
    val sleep = LinkedHashMap<Long, SleepStage>()
    val cursors = HashMap<String, Long>()
    val workouts = ArrayList<Workout>()
    var failUpserts = false

    override suspend fun upsertSteps(hours: List<StepsHour>) {
        if (failUpserts) throw IllegalStateException("db down")
        for (h in hours) steps[h.hourStart] = h
    }

    override suspend fun upsertHr(samples: List<HrSample>) {
        if (failUpserts) throw IllegalStateException("db down")
        for (s in samples) hr[s.time to s.source.name] = s
    }

    override suspend fun upsertSpo2(samples: List<Spo2Sample>) {
        if (failUpserts) throw IllegalStateException("db down")
        for (s in samples) spo2[s.time to s.source.name] = s
    }

    override suspend fun upsertSleep(stages: List<SleepStage>) {
        if (failUpserts) throw IllegalStateException("db down")
        for (s in stages) sleep[s.start] = s
    }

    override suspend fun insertWorkout(workout: Workout): Long {
        workouts += workout.copy(id = (workouts.size + 1).toLong())
        return workouts.size.toLong()
    }

    override suspend fun updateWorkout(workout: Workout) {
        val i = workouts.indexOfFirst { it.id == workout.id }
        if (i >= 0) workouts[i] = workout
    }

    override suspend fun insertTrackPoints(points: List<TrackPoint>) = Unit

    override fun dailySummary(dayStart: Long): Flow<DailySummary> =
        flowOf(DailySummary(dayStart, 0, 0, 0, 0.0, null, null, null, null, null))

    override fun stepsForDay(dayStart: Long): Flow<List<StepsHour>> =
        flowOf(steps.values.filter { it.hourStart >= dayStart && it.hourStart < dayStart + 25L * 3_600_000L }.sortedBy { it.hourStart })
    override fun hrBetween(from: Long, to: Long): Flow<List<HrSample>> = flowOf(emptyList())
    override fun spo2Between(from: Long, to: Long): Flow<List<Spo2Sample>> = flowOf(emptyList())
    override fun sleepForNight(dayStart: Long): Flow<List<SleepStage>> = flowOf(emptyList())
    override fun workouts(): Flow<List<Workout>> = flowOf(workouts.toList())
    override fun workout(id: Long): Flow<Workout?> = flowOf(workouts.firstOrNull { it.id == id })
    override fun trackPoints(workoutId: Long): Flow<List<TrackPoint>> = flowOf(emptyList())
    override suspend fun dailySummaries(days: Int): List<DailySummary> = emptyList()

    override suspend fun lastSyncTime(kind: String): Long? = cursors[kind]
    override suspend fun setLastSyncTime(kind: String, time: Long) {
        cursors[kind] = time
    }

    override suspend fun hrSince(time: Long): List<HrSample> = hr.values.filter { it.time >= time }
    override suspend fun spo2Since(time: Long): List<Spo2Sample> = spo2.values.filter { it.time >= time }
    override suspend fun stepsSince(time: Long): List<StepsHour> = steps.values.filter { it.hourStart >= time }
    override suspend fun sleepSince(time: Long): List<SleepStage> = sleep.values.filter { it.start >= time }
    override suspend fun workoutsSince(time: Long): List<Workout> = workouts.filter { it.start >= time }
}

class FakeSettings : SettingsStore {
    private val _mac = MutableStateFlow<String?>("78:02:B7:37:91:E5")
    private val _profile = MutableStateFlow(UserProfile(heightCm = 175, weightKg = 75, age = 40, male = true, stepGoal = 8000))
    private val _sampling = MutableStateFlow(SamplingSettings())
    private val _stride = MutableStateFlow(StrideSettings())
    private val _hc = MutableStateFlow(false)

    override val watchMac: Flow<String?> = _mac
    override val profile: Flow<UserProfile> = _profile
    override val sampling: Flow<SamplingSettings> = _sampling
    override val stride: Flow<StrideSettings> = _stride
    override val healthConnectEnabled: Flow<Boolean> = _hc

    override suspend fun setWatchMac(mac: String?) { _mac.value = mac }
    override suspend fun setProfile(p: UserProfile) { _profile.value = p }
    override suspend fun setSampling(s: SamplingSettings) { _sampling.value = s }
    override suspend fun setStride(s: StrideSettings) { _stride.value = s }
    override suspend fun setHealthConnectEnabled(on: Boolean) { _hc.value = on }
}

/** Polls [cond] for up to [timeoutMs]; the packet collector runs on its own coroutine. */
fun eventually(timeoutMs: Long = 2_000L, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(5)
    }
    return cond()
}
