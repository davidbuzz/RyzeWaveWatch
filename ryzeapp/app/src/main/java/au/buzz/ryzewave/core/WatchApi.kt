package au.buzz.ryzewave.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the app can ask the watch to do. Implemented by the BLE layer (`ble.WatchService` +
 * `ble.WatchGatt`) and exposed through `App.graph.watch`. All suspend functions run on the BLE
 * queue: one GATT operation at a time, replies matched by opcode as in ../../../../../docs/PROTOCOL.md.
 */
interface WatchApi {
    val status: StateFlow<WatchStatus>

    /** Live heart-rate samples from `E5 11 00 <hr>` (dynamic mode) and `FD 01 <hr>` (workout). */
    val liveHr: SharedFlow<HrSample>

    /** Unsolicited packets the UI may want to react to (SpO2 result pushes, HR summaries, find-phone…). */
    val events: SharedFlow<WatchEvent>

    suspend fun connect(mac: String)
    suspend fun disconnect()

    /** Set time, user profile, goals and sampling settings — done on every connect (a factory-reset watch has none). */
    suspend fun applySettings(profile: UserProfile, sampling: SamplingSettings)

    /** Fetch steps, HR, SpO2 and sleep history since the last sync and persist them through the repository. */
    suspend fun syncAll(): SyncResult

    suspend fun startLiveHr()
    suspend fun stopLiveHr()

    /** Runs a spot SpO2 test (about 60 s). Returns the percentage, or null on failure/timeout. */
    suspend fun spo2SpotTest(): Int?

    suspend fun startWorkout(sportType: Int = 1)
    /** Push live metrics to the watch face once per second while a workout runs (`FD 44`). */
    suspend fun updateWorkout(durationSeconds: Int, distanceMeters: Double, paceSecPerKm: Double, calories: Int)
    suspend fun pauseWorkout()
    suspend fun resumeWorkout()
    suspend fun stopWorkout()

    suspend fun findWatch()
    suspend fun readBattery(): Int?
}

sealed class WatchEvent {
    data class Spo2Result(val time: Long, val percent: Int?) : WatchEvent()
    data class HrSummary(val time: Long, val max: Int, val min: Int, val avg: Int) : WatchEvent()
    data class RealtimeSteps(val stepsHour: StepsHour) : WatchEvent()
    data object FindPhone : WatchEvent()
    data class Raw(val channel: String, val hex: String) : WatchEvent()
}
