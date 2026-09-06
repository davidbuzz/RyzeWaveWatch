package au.buzz.ryzewave.ble

import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.SyncResult
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WatchStatus
import au.buzz.ryzewave.core.WorkoutControlAction
import au.buzz.ryzewave.protocol.Packet
import au.buzz.ryzewave.protocol.Protocol
import au.buzz.ryzewave.protocol.SportTypes
import au.buzz.ryzewave.protocol.SportState
import au.buzz.ryzewave.protocol.Spo2Phase
import au.buzz.ryzewave.protocol.encFetchHr24Since
import au.buzz.ryzewave.protocol.encUserInfo
import au.buzz.ryzewave.protocol.toEvent
import au.buzz.ryzewave.protocol.toHrSample
import au.buzz.ryzewave.protocol.toSleepStage
import au.buzz.ryzewave.protocol.toSpo2Sample
import au.buzz.ryzewave.protocol.toStepsHour
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * [WatchApi] on top of a [WatchLink], persisting through the [HealthRepository]. No Android imports: the
 * behaviour is unit tested with a fake link replaying the hex captures.
 *
 * Behaviour (docs/APP.md "Behaviour that must match the watch"):
 *  - on every [LinkState.Ready] (explicit connect or automatic reconnect) and when [autoSetupOnConnect] is set:
 *    `A1` version, `A2` battery, [applySettings] (`A3` time → `A9` profile → `F7 01/02` → `34 03` / `34 04`) and
 *    [syncAll], serialised by [syncMutex] with the calls the service makes every 30 min;
 *  - [syncAll]: `B2 FA`, `F7 FA <since6>` (since = last HR sync − 2 h so the partially filled 2-hour record is
 *    refreshed; zeros = everything on the first sync), `34 FA`, `31 01` (+ `32` stages on 34F2); everything is
 *    upserted (idempotent) and the cursors are stored per kind (`SYNC_STEPS` …) via [HealthRepository];
 *  - live HR: `D6 02`, 1.5 s, `E5 11`; stop `E5 00`; `E5 11 00 <hr>` → [liveHr] (LIVE, persisted every 10 s);
 *  - SpO2 spot test: `34 11`, the `34 00 FF FF` within 3 s is ignored, `34 00 00 <pct>` ends it, 90 s cap;
 *  - workouts: `FD 11 <type> 01` / `FD 22` / `FD 33` / `FD 00 <type> 01` each wait for the watch's echo,
 *    `FD 44 …` is pushed and its echo awaited for 2 s (a missing echo is not an error); `FD 01 <hr> …` → [liveHr]
 *    (WORKOUT, persisted by the workout controller);
 *  - pushes at any time: `F7 03` (AUTO HR sample → repository), `F7 04` ([WatchEvent.HrSummary]), `34 00 00 xx`
 *    (SpO2 result → repository + [WatchEvent.Spo2Result]), `B1` (→ repository + [WatchEvent.RealtimeSteps]),
 *    `A2 <pct> [01]` (status battery / charging), `D1 0A 01/00` ([WatchEvent.FindPhone] start / stop). History packets nobody
 *    asked for (`F7`/`B2`/`34 FA` records and `31`/`32` sleep packets streamed in answer to Ryze Fit's fetch when
 *    it shares the link) are persisted too — they are valid watch data whichever app asked, and the upserts are
 *    idempotent. Anything else unrequested is only surfaced as [WatchEvent.Raw].
 *  - the connect-time setup is coalesced: a Ready that arrives while a setup is queued or running schedules at
 *    most one more run, so a flapping link does not pile up full syncs;
 *  - a link that is Ready but whose writes fail "not connected" (the GATT dropped without a callback) is reset
 *    through [WatchLink.resetLink] at the end of the sync so the transport reconnects instead of staying stuck.
 */
class WatchApiImpl(
    private val link: WatchLink,
    private val repo: HealthRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val autoSetupOnConnect: Boolean = true,
    private val liveHrWarmupMs: Long = LIVE_HR_WARMUP_MS,
    private val fetchTimeoutMs: Long = WatchLink.FETCH_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val log: (String, Throwable?) -> Unit = { msg, t -> println("WatchApi: $msg${if (t != null) " ($t)" else ""}") },
) : WatchApi {

    private val _status = MutableStateFlow(WatchStatus())
    override val status: StateFlow<WatchStatus> = _status.asStateFlow()

    private val _liveHr = MutableSharedFlow<HrSample>(replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val liveHr: SharedFlow<HrSample> = _liveHr

    private val _events = MutableSharedFlow<WatchEvent>(replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<WatchEvent> = _events

    /** Serialises [applySettings] / [syncAll] / the connect-time setup (they share opcodes). */
    private val syncMutex = Mutex()

    @Volatile private var sportType = 1
    @Volatile private var spo2TestStartedAt = 0L
    @Volatile private var lastLivePersist = 0L

    /**
     * Sport-control commands the app has just sent, with the time they went out. The watch echoes every control
     * it receives, so a `FD 22`/`FD 33`/`FD 00` that matches a recent send (within [ECHO_WINDOW_MS]) is our own
     * echo and is ignored; any other one is a press on the watch and becomes a [WatchEvent.WorkoutControl].
     */
    private data class ExpectedEcho(val state: Int, val at: Long)
    private val expectedEchoes = java.util.concurrent.CopyOnWriteArrayList<ExpectedEcho>()

    /** Set when a write fails "not connected" while the link claims to be Ready; consumed by [syncAllLocked]. */
    @Volatile private var linkSuspect: String? = null

    /** A connect-time setup is queued or running; cleared once it holds [syncMutex]. */
    private val setupPending = AtomicBoolean(false)

    /** Session date of the last `31 01` seen outside a fetch, for unconsumed `32` stage packets. */
    @Volatile private var pushedSleepDate: LocalDate? = null

    init {
        scope.launch { link.packets.collect { onPacket(it) } }
        scope.launch { link.state.collect { onLinkState(it) } }
        scope.launch {
            try {
                repo.lastSyncTime(SYNC_ALL)?.let { t -> _status.update { it.copy(lastSyncTime = t) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("could not read the last sync time", e)
            }
        }
    }

    // ------------------------------------------------------------------ connection

    override suspend fun connect(mac: String) {
        _status.update { it.copy(mac = mac, message = null) }
        link.connect(mac)
    }

    override suspend fun disconnect() {
        link.disconnect()
    }

    private fun onLinkState(s: LinkState) {
        when (s) {
            is LinkState.Disconnected -> {
                _status.update {
                    it.copy(
                        state = if (s.status == 0) ConnectionState.DISCONNECTED else ConnectionState.ERROR,
                        message = s.message,
                        charging = false,
                    )
                }
            }
            is LinkState.Connecting -> _status.update {
                it.copy(
                    state = ConnectionState.CONNECTING,
                    mac = s.mac,
                    message = if (s.attempt > 1) "reconnecting (attempt ${s.attempt})" else "connecting",
                )
            }
            is LinkState.Ready -> {
                _status.update { it.copy(state = ConnectionState.CONNECTED, mac = s.mac, message = null) }
                if (autoSetupOnConnect && setupPending.compareAndSet(false, true)) {
                    scope.launch {
                        try {
                            setupAndSyncInternal(coalesced = true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("connect-time setup failed", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * The connect burst: `A1`, `A2`, [applySettings] with the stored profile / sampling settings, then [syncAll].
     * Also usable by the service for its periodic refresh.
     */
    suspend fun setupAndSync(): SyncResult = setupAndSyncInternal(coalesced = false)

    private suspend fun setupAndSyncInternal(coalesced: Boolean): SyncResult = syncMutex.withLock {
        // From here on a new Ready schedules one more run; earlier ones were folded into this one.
        if (coalesced) setupPending.set(false)
        if (!link.isReady) return SyncResult(0, 0, 0, 0, "not connected")
        attempt("version") { readVersion() }
        attempt("battery") { readBatteryLocked() }
        val profile = attempt("profile") { settings.profile.first() } ?: UserProfile()
        val sampling = attempt("sampling") { settings.sampling.first() } ?: SamplingSettings()
        attempt("applySettings") { applySettingsLocked(profile, sampling) }
        syncAllLocked()
    }

    // ------------------------------------------------------------------ settings

    override suspend fun applySettings(profile: UserProfile, sampling: SamplingSettings) = syncMutex.withLock {
        applySettingsLocked(profile, sampling)
    }

    private suspend fun applySettingsLocked(profile: UserProfile, sampling: SamplingSettings) {
        requireReady()
        val interval = sampling.spo2IntervalMin.coerceIn(1, 0xFFFF)
        ack(Protocol.encSetTime(LocalDateTime.now(zone)), Matchers.opcodeIs(Protocol.CMD_TIME), "A3 set time")
        ack(Protocol.encUserInfo(profile, raiseWrist = sampling.raiseWristWake), Matchers.opcodeIs(Protocol.CMD_USER_INFO), "A9 user info")
        ack(
            Protocol.encHrContinuous(sampling.continuousHr),
            Matchers.opcodeAndSub(Protocol.CMD_HR24, if (sampling.continuousHr) 0x01 else 0x02),
            "F7 continuous HR",
        )
        ack(Protocol.encSpo2Auto(sampling.spo2AutoEnabled, interval), Matchers.opcodeAndSub(Protocol.CMD_SPO2, 0x03), "34 03 SpO2 auto")
        ack(Protocol.encSpo2Period(sampling.spo2AutoEnabled), Matchers.opcodeAndSub(Protocol.CMD_SPO2, 0x04), "34 04 SpO2 period")
        log("settings applied: continuousHr=${sampling.continuousHr} spo2Auto=${sampling.spo2AutoEnabled}/${interval} min goal=${profile.stepGoal}", null)
    }

    /** Writes [cmd] and waits for its echo; a missing echo while the link is still up is logged, not fatal. */
    private suspend fun ack(cmd: ByteArray, pred: (ByteArray) -> Boolean, what: String) {
        try {
            link.request(cmd, pred, ACK_TIMEOUT_MS)
        } catch (e: GattException) {
            if (!link.isReady) throw e
            if (!e.timeout) {
                linkSuspect = e.message ?: "write failed"     // Ready but the write itself failed: the GATT is gone
                throw e
            }
            log("$what: no echo (${e.message})", null)
        }
    }

    // ------------------------------------------------------------------ sync

    override suspend fun syncAll(): SyncResult = syncMutex.withLock { syncAllLocked() }

    private suspend fun syncAllLocked(): SyncResult {
        if (!link.isReady) return SyncResult(0, 0, 0, 0, "not connected")
        _status.update { it.copy(state = ConnectionState.SYNCING, message = "syncing") }
        linkSuspect = null
        val errors = ArrayList<String>()
        val now = clock()
        var stepsN = 0
        var hrN = 0
        var spo2N = 0
        var sleepN = 0
        var error: String? = "cancelled"
        try {
            // steps: B2 FA -> 18-byte B2 records -> B2 FD xx
            val steps = fetch(Protocol.encFetchSteps(), Protocol.CMD_STEPS, Protocol::isStepsEnd)
            stepsN = persist("steps", errors) {
                val hours = steps.packets.filter { it.size == 18 }.mapNotNull { p -> decode(p) { Protocol.decStepsRecord(it).toStepsHour(zone) } }
                repo.upsertSteps(hours)
                hours.size
            }
            if (steps.error != null) errors += "steps: ${steps.error}" else cursor(SYNC_STEPS, now)

            // HR: F7 FA <since6> -> 18-byte F7 records -> F7 FD xx
            val hrCursor = attempt("hr cursor") { repo.lastSyncTime(SYNC_HR) }
            val hrSince = hrCursor?.let { it - HR_RESYNC_OVERLAP_MS }
            val withTs = link.features?.syncTimestamp ?: true
            val hr = fetch(Protocol.encFetchHr24Since(hrSince, withTs, zone), Protocol.CMD_HR24, Protocol::isHr24End)
            var newestHr: Long? = null
            hrN = persist("hr", errors) {
                val samples = ArrayList<HrSample>()
                for (p in hr.packets) {
                    if (p.size != 18) continue
                    decode(p) { Protocol.decHr24Record(it) }?.let { recs -> samples += recs.map { it.toHrSample(SampleSource.HISTORY, zone) } }
                }
                repo.upsertHr(samples)
                newestHr = samples.maxOfOrNull { it.time }
                samples.size
            }
            if (hr.error != null) {
                errors += "hr: ${hr.error}"
            } else {
                // The cursor only moves as far as the data this fetch actually delivered: if another client's
                // stream (Ryze Fit sharing the link) was consumed instead of ours, the next since-stamp still
                // covers what we have not seen. Nothing delivered = nothing newer = the cursor stays.
                newestHr?.let { cursor(SYNC_HR, min(it, now)) }
            }

            // SpO2: 34 FA -> 20-byte 34 FA records -> 34 FA FD xx
            val spo2 = fetch(Protocol.encFetchSpo2(), Protocol.CMD_SPO2, Protocol::isSpo2End)
            spo2N = persist("spo2", errors) {
                val samples = ArrayList<Spo2Sample>()
                for (p in spo2.packets) {
                    if (p.size != 20 || Protocol.sub(p) != Protocol.FETCH_START) continue
                    decode(p) { Protocol.decSpo2Record(it) }?.let { recs -> samples += recs.map { it.toSpo2Sample(SampleSource.HISTORY, zone) } }
                }
                repo.upsertSpo2(samples)
                samples.size
            }
            if (spo2.error != null) errors += "spo2: ${spo2.error}" else cursor(SYNC_SPO2, now)

            // sleep: 31 01 -> 31 01 date n (33F2), 32 stages (34F2), 31 02 (33F2)
            val sleep = fetch(
                Protocol.encFetchSleep(), Protocol.CMD_SLEEP_INFO, Protocol::isSleepEnd,
                extraChannel = WatchChannel.DATA, extraOpcode = Protocol.CMD_SLEEP_STAGES,
            )
            sleepN = persist("sleep", errors) {
                val stages = decodeSleep(sleep.packets)
                repo.upsertSleep(stages)
                stages.size
            }
            if (sleep.error != null) errors += "sleep: ${sleep.error}" else cursor(SYNC_SLEEP, now)

            error = errors.joinToString("; ").ifEmpty { null }
            if (error == null) cursor(SYNC_ALL, now)
        } finally {
            // Always leave SYNCING, also when the caller's scope is cancelled mid-fetch.
            val finalError = error
            _status.update {
                it.copy(
                    state = if (link.isReady) ConnectionState.CONNECTED else it.state,
                    lastSyncTime = if (finalError == null) now else it.lastSyncTime,
                    message = finalError?.let { e -> "sync: $e" },
                )
            }
            log("sync: steps=$stepsN hr=$hrN spo2=$spo2N sleep=$sleepN${finalError?.let { " error=$it" } ?: ""}", null)
            val suspect = linkSuspect
            if (suspect != null && link.isReady) {
                linkSuspect = null
                log("link is Ready but writes fail ($suspect): resetting it", null)
                attempt("reset link") { link.resetLink("dead link: $suspect") }
            }
        }
        return SyncResult(stepsN, hrN, spo2N, sleepN, error)
    }

    private class Fetch(val packets: List<ByteArray>, val error: String?)

    private suspend fun fetch(
        cmd: ByteArray,
        opcode: Int,
        isEnd: (ByteArray) -> Boolean,
        extraChannel: WatchChannel? = null,
        extraOpcode: Int? = null,
    ): Fetch = try {
        Fetch(link.collect(cmd, opcode, isEnd, fetchTimeoutMs, WatchChannel.CMD, extraChannel, extraOpcode), null)
    } catch (e: GattException) {
        log("fetch ${cmd.toHex()} failed after ${e.partial.size} packets: ${e.message}", null)
        if (!e.timeout && link.isReady) linkSuspect = e.message ?: "write failed"
        Fetch(e.partial, e.message ?: "fetch failed")
    }

    private suspend fun persist(what: String, errors: MutableList<String>, block: suspend () -> Int): Int = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("persisting $what failed", e)
        errors += "$what: ${e.message ?: e.javaClass.simpleName}"
        0
    }

    private suspend fun cursor(kind: String, time: Long) {
        attempt("cursor $kind") { repo.setLastSyncTime(kind, time) }
    }

    private fun decodeSleep(packets: List<ByteArray>): List<SleepStage> {
        val out = ArrayList<SleepStage>()
        var session: LocalDate? = null
        for (p in packets) {
            when (Protocol.opcode(p)) {
                Protocol.CMD_SLEEP_INFO -> {
                    val info = decode(p) { Protocol.decSleepInfo(it) }
                    if (info != null) session = info.date
                }
                Protocol.CMD_SLEEP_STAGES -> {
                    val date = session ?: continue
                    decode(p) { Protocol.decSleepStages(date, it) }?.let { recs -> out += recs.map { it.toSleepStage(zone) } }
                }
            }
        }
        return out
    }

    private inline fun <T> decode(p: ByteArray, block: (ByteArray) -> T): T? = try {
        block(p)
    } catch (e: RuntimeException) {
        log("cannot decode ${p.toHex()}: ${e.message}", null)
        null
    }

    // ------------------------------------------------------------------ live HR

    override suspend fun startLiveHr() {
        requireReady()
        link.write(Protocol.encHrMode(dynamic = true))   // D6 02: dynamic mode, as the vendor's HR screen
        delay(liveHrWarmupMs)                             // 1.5 s
        link.write(Protocol.encRtHr(true))                // E5 11: E5 11 00 <hr> per second follows
    }

    override suspend fun stopLiveHr() {
        if (!link.isReady) return
        link.write(Protocol.encRtHr(false))               // E5 00
    }

    // ------------------------------------------------------------------ SpO2 spot test

    override suspend fun spo2SpotTest(): Int? {
        if (!link.isReady) return null
        val t0 = clock()
        spo2TestStartedAt = t0
        try {
            val final = link.request(
                Protocol.encSpo2Test(true),
                { p -> Matchers.isSpo2Final(p, clock() - t0) },
                SPO2_TIMEOUT_MS,
            )
            val pct = Matchers.spo2Percent(final)
            log("SpO2 spot test: ${pct?.let { "$it %" } ?: "failed (${final.toHex()})"} after ${(clock() - t0) / 1000} s", null)
            return pct
        } catch (e: GattException) {
            log("SpO2 spot test: ${e.message}", null)
            if (link.isReady && e.timeout) attempt("spo2 stop") { link.write(Protocol.encSpo2Test(false)) }
            return null
        }
    }

    // ------------------------------------------------------------------ workouts

    override suspend fun startWorkout(sportType: Int) {
        requireReady()
        this.sportType = sportType
        expectEcho(Protocol.SPORT_START)
        link.request(
            Protocol.encSportControl(Protocol.SPORT_START, sportType, 1),
            Matchers.isSportEcho(Protocol.SPORT_START), CONTROL_TIMEOUT_MS,
        )
        // Then ask the watch what it actually did. Best effort: a missing answer is logged, never fatal.
        val state = runCatching { queryWorkout() }.getOrNull()
        when {
            state == null -> log("watch did not answer the sport query after start (type $sportType)", null)
            state.state != 0 && state.sportType == sportType ->
                log("watch confirms sport $sportType open (${SportTypes.name(sportType)})", null)
            else -> log("watch reports state=${state.state} type=${state.sportType} after starting $sportType", null)
        }
    }

    override suspend fun queryWorkout(): SportState? {
        requireReady()
        // Short timeout: this is a confirmation, not a control, and must never hold up a start on a watch that ignores it.
        val reply = link.request(Protocol.encSportQuery(), { Protocol.decSportState(it) != null }, QUERY_TIMEOUT_MS)
        return Protocol.decSportState(reply)
    }

    override suspend fun updateWorkout(durationSeconds: Int, distanceMeters: Double, paceSecPerKm: Double, calories: Int) {
        requireReady()
        val cmd = Protocol.encSportUpdate(sportType, durationSeconds, calories, distanceMeters, paceSecPerKm)
        try {
            link.request(cmd, Matchers.isSportEcho(Protocol.SPORT_UPDATE), UPDATE_ECHO_TIMEOUT_MS)
        } catch (e: GattException) {
            if (!link.isReady || !e.timeout) throw e
            log("FD 44 update not echoed within $UPDATE_ECHO_TIMEOUT_MS ms", null)
        }
    }

    override suspend fun pauseWorkout() {
        requireReady()
        expectEcho(Protocol.SPORT_PAUSE)
        link.request(Protocol.encSportControl(Protocol.SPORT_PAUSE, sportType, 1), Matchers.isSportEcho(Protocol.SPORT_PAUSE), CONTROL_TIMEOUT_MS)
    }

    override suspend fun resumeWorkout() {
        requireReady()
        expectEcho(Protocol.SPORT_RESUME)
        link.request(Protocol.encSportControl(Protocol.SPORT_RESUME, sportType, 1), Matchers.isSportEcho(Protocol.SPORT_RESUME), CONTROL_TIMEOUT_MS)
    }

    override suspend fun stopWorkout() {
        requireReady()
        expectEcho(Protocol.SPORT_STOP)
        link.request(Protocol.encSportControl(Protocol.SPORT_STOP, sportType, 1), Matchers.isSportEcho(Protocol.SPORT_STOP), CONTROL_TIMEOUT_MS)
    }

    /** Records that the app just sent a control [state], so its echo can be told apart from a watch button press. */
    private fun expectEcho(state: Int) {
        val now = clock()
        expectedEchoes.removeAll { now - it.at > ECHO_WINDOW_MS }
        expectedEchoes.add(ExpectedEcho(state, now))
    }

    /** Consumes (and returns true for) a pending expected echo of [state] within [ECHO_WINDOW_MS]; prunes stale ones. */
    private fun consumeExpectedEcho(state: Int, now: Long): Boolean {
        val match = expectedEchoes.firstOrNull { it.state == state && now - it.at <= ECHO_WINDOW_MS }
        expectedEchoes.removeAll { now - it.at > ECHO_WINDOW_MS || it === match }
        return match != null
    }

    /**
     * Debug/test hook: publishes [e] on [events] as if it had come from the watch (used by the debug broadcast
     * receiver to exercise the workout state machine, and by unit tests). Does not touch the GATT link.
     */
    fun injectEvent(e: WatchEvent) {
        _events.tryEmit(e)
    }

    /** Debug/test hook: publishes a live heart-rate sample on [liveHr] as if the watch had streamed it. */
    fun injectHr(bpm: Int) {
        _liveHr.tryEmit(HrSample(System.currentTimeMillis(), bpm, SampleSource.WORKOUT))
    }

    // ------------------------------------------------------------------ notifications

    /** One notification burst at a time: chunks of a second message must not interleave with the first. */
    private val notifyMutex = Mutex()

    /**
     * `C5 00 <type> <total> …`, `C5 <idx> …` each awaited by its `C5 <idx>` ack (3 s), then `C5 FD` awaited by
     * `C5 FD <type> <total>` (verified 2026-09-05 07:39: 40-char generic in 5 chunks, 120-char SMS in 15).
     * Skipped (false) when the link is down or the text sanitises to nothing; a missing ack throws [GattException].
     */
    override suspend fun sendNotification(type: Int, text: String): Boolean {
        if (!link.isReady) {
            log("notification skipped: watch not connected", null)
            return false
        }
        val chunks = Protocol.encNotification(type, text)
        if (chunks.isEmpty()) {
            log("notification skipped: nothing left to send after sanitising", null)
            return false
        }
        notifyMutex.withLock {
            requireReady()
            for ((idx, chunk) in chunks.withIndex()) {
                link.request(chunk, Matchers.isNotifyAck(idx), NOTIFY_ACK_TIMEOUT_MS)
            }
            link.request(Protocol.encNotificationEnd(), Matchers::isNotifyEnd, NOTIFY_ACK_TIMEOUT_MS)
        }
        log("notification sent: type $type, ${chunks.size} chunks", null)
        return true
    }

    // ------------------------------------------------------------------ misc

    override suspend fun findWatch() {
        requireReady()
        link.write(Protocol.encFindWatch())
    }

    override suspend fun readBattery(): Int? {
        if (!link.isReady) return null
        return try {
            readBatteryLocked()
        } catch (e: GattException) {
            log("battery: ${e.message}", null)
            null
        }
    }

    private suspend fun readBatteryLocked(): Int? {
        val reply = link.request(Protocol.encBattery(), Protocol.CMD_BATTERY, WatchLink.DEFAULT_TIMEOUT_MS)
        val b = decode(reply) { Protocol.decBattery(it) } ?: return null
        _status.update { it.copy(batteryPercent = b.percent, charging = b.charging) }
        return b.percent
    }

    private suspend fun readVersion(): String? {
        val reply = link.request(Protocol.encVersion(), Protocol.CMD_VERSION, WatchLink.DEFAULT_TIMEOUT_MS)
        val v = Protocol.decVersion(reply).ifEmpty { return null }
        _status.update { it.copy(firmware = v) }
        return v
    }

    private fun requireReady() {
        if (!link.isReady) throw GattException("watch not connected")
    }

    /** Runs [block], logging (not propagating) anything but cancellation. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$what failed: ${e.message ?: e.javaClass.simpleName}", if (e is GattException) null else e)
        null
    }

    // ------------------------------------------------------------------ incoming packets (pushes + replies)

    private suspend fun onPacket(raw: RawPacket) {
        val now = raw.time
        when (val p = Packet.parse(raw.data)) {
            is Packet.LiveHr -> {
                if (p.bpm > 0) {
                    val s = HrSample(now, p.bpm, SampleSource.LIVE)
                    _liveHr.tryEmit(s)
                    if (now - lastLivePersist >= LIVE_HR_PERSIST_EVERY_MS) {
                        lastLivePersist = now
                        attempt("persist live hr") { repo.upsertHr(listOf(s)) }
                    }
                }
            }
            is Packet.SportRt -> {
                if (p.hr > 0) _liveHr.tryEmit(HrSample(now, p.hr, SampleSource.WORKOUT))
                // Session steps (and the other live fields) so the workout controller can keep the watch's count.
                _events.tryEmit(WatchEvent.WorkoutRealtime(p.sportType, p.steps, p.calories, p.distanceMeters))
            }
            is Packet.SportControlEcho -> onSportControlEcho(p, raw.consumed, now)
            is Packet.HrAutoSample -> attempt("persist auto hr") { repo.upsertHr(listOf(p.toHrSample(zone))) }
            is Packet.HrSummary -> _events.tryEmit(p.toEvent(zone))
            is Packet.Spo2Test -> onSpo2Packet(p, now)
            is Packet.Steps -> if (p.realtime) {
                val hour = p.record.toStepsHour(zone)
                attempt("persist realtime steps") {
                    if (realtimeStepsSupersedeStored(hour)) repo.upsertSteps(listOf(hour))
                    else log("ignored realtime steps ${hour.total} below the stored total for hour ${hour.hourStart}", null)
                }
                _events.tryEmit(p.toEvent(zone))
            } else if (!raw.consumed) {
                attempt("persist foreign steps record") { repo.upsertSteps(listOf(p.record.toStepsHour(zone))) }
            }
            is Packet.Hr24 -> if (!raw.consumed && p.samples.isNotEmpty()) {
                attempt("persist foreign hr record") { repo.upsertHr(p.samples.map { it.toHrSample(SampleSource.HISTORY, zone) }) }
            }
            is Packet.Spo2History -> if (!raw.consumed && p.samples.isNotEmpty()) {
                attempt("persist foreign spo2 record") { repo.upsertSpo2(p.samples.map { it.toSpo2Sample(SampleSource.HISTORY, zone) }) }
            }
            is Packet.SleepInfo -> if (!raw.consumed) pushedSleepDate = p.sessionDate
            is Packet.SleepStages -> if (!raw.consumed) {
                val date = pushedSleepDate
                if (date != null) {
                    decode(raw.data) { Protocol.decSleepStages(date, it) }?.let { recs ->
                        attempt("persist foreign sleep stages") { repo.upsertSleep(recs.map { it.toSleepStage(zone) }) }
                    }
                }
            }
            is Packet.Battery -> _status.update { it.copy(batteryPercent = p.percent, charging = p.charging) }
            is Packet.Version -> if (!p.dsp && p.version.isNotEmpty()) _status.update { it.copy(firmware = p.version) }
            is Packet.FindPhone -> _events.tryEmit(WatchEvent.FindPhone(p.start))
            is Packet.Unknown -> if (!raw.consumed) _events.tryEmit(WatchEvent.Raw(raw.channel.notifyName, p.raw))
            else -> Unit
        }
    }

    /**
     * A realtime `B1` push is only applied when it does not lower the hour's stored total. The watch sends a
     * `B1` with total 0 for the hour that has just *finished* right after the hour boundary
     * (captures/bridge_passive_20260904_195347.txt lines 1722/1725: 20:55 `B1` hour 20 = 279 steps, 21:00:01
     * `B1` hour 20 = 0); applying it would drop the day's steps and distance until the next `B2 FA` sync
     * restored the hour. The history (`B2`) records from [syncAll] stay authoritative: they bypass this check.
     */
    private suspend fun realtimeStepsSupersedeStored(hour: StepsHour): Boolean {
        val dayStart = Instant.ofEpochMilli(hour.hourStart).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val stored = repo.stepsForDay(dayStart).first().firstOrNull { it.hourStart == hour.hourStart } ?: return true
        return hour.total >= stored.total
    }

    /**
     * A `FD <state> <type> <ivl>` control echo. Distinguishes the echo of a command the app just sent (the link
     * consumed it for a pending request, or it matches a recent [expectEcho]) from a press on the watch. A
     * watch-originated pause/resume/stop is surfaced as [WatchEvent.WorkoutControl] so the controller applies it
     * on the same path as the app buttons — the controller does NOT send the control back, so there is no loop.
     * `FD 44` (metrics-push) echoes are not controls and are dropped.
     */
    private fun onSportControlEcho(p: Packet.SportControlEcho, consumed: Boolean, now: Long) {
        if (p.state == Protocol.SPORT_UPDATE) return
        val expected = consumeExpectedEcho(p.state, now)
        if (consumed || expected) return       // our own command's echo
        val action = when (p.state) {
            Protocol.SPORT_PAUSE -> WorkoutControlAction.PAUSE
            Protocol.SPORT_RESUME -> WorkoutControlAction.RESUME
            Protocol.SPORT_STOP -> WorkoutControlAction.STOP
            Protocol.SPORT_START -> WorkoutControlAction.START
            else -> return
        }
        log("watch-originated workout control $action (${p.raw})", null)
        _events.tryEmit(WatchEvent.WorkoutControl(action))
    }

    private suspend fun onSpo2Packet(p: Packet.Spo2Test, now: Long) {
        val r = p.result
        if (r.phase != Spo2Phase.FINAL) return
        val started = spo2TestStartedAt
        // a spot test we started is "ours" for its whole 90 s window (+ slack for a late result); later = auto push
        val testing = started != 0L && now - started in 0..(SPO2_TIMEOUT_MS + 5_000L)
        if (r.spo2 == null && testing && now - started < Matchers.SPO2_BOGUS_WINDOW_MS) return  // the early 34 00 FF FF
        val pct = r.spo2
        if (pct != null && pct in 1..100) {
            attempt("persist spo2") {
                repo.upsertSpo2(listOf(Spo2Sample(now, pct, if (testing) SampleSource.LIVE else SampleSource.AUTO)))
            }
        }
        _events.tryEmit(WatchEvent.Spo2Result(now, pct))
    }

    companion object {
        /** Sync-cursor kinds stored through [HealthRepository.setLastSyncTime]. */
        const val SYNC_ALL = "watch"
        const val SYNC_STEPS = "steps"
        const val SYNC_HR = "hr"
        const val SYNC_SPO2 = "spo2"
        const val SYNC_SLEEP = "sleep"

        const val LIVE_HR_WARMUP_MS = 1_500L
        const val LIVE_HR_PERSIST_EVERY_MS = 10_000L
        const val HR_RESYNC_OVERLAP_MS = 2 * 60 * 60_000L
        const val ACK_TIMEOUT_MS = 5_000L
        /** `FD AA` confirmation after a start: 2 s, never a reason to delay the workout. */
        const val QUERY_TIMEOUT_MS = 2_000L
        const val CONTROL_TIMEOUT_MS = 8_000L
        const val UPDATE_ECHO_TIMEOUT_MS = 2_000L

        /** How long after sending a control command its echo is still recognised as ours (not a watch press). */
        const val ECHO_WINDOW_MS = 2_000L
        const val NOTIFY_ACK_TIMEOUT_MS = 3_000L
        const val SPO2_TIMEOUT_MS = 90_000L
    }
}
