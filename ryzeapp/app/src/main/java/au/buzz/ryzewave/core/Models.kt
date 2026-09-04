package au.buzz.ryzewave.core

/** Shared data model. Times are epoch milliseconds in the phone's local clock unless stated otherwise. */

enum class SampleSource { HISTORY, LIVE, WORKOUT, AUTO }

/** One hour of steps as reported by the watch (`B2` record). [hourStart] is the start of the hour. */
data class StepsHour(val hourStart: Long, val total: Int, val walk: Int, val run: Int)

data class HrSample(val time: Long, val bpm: Int, val source: SampleSource = SampleSource.HISTORY)

data class Spo2Sample(val time: Long, val percent: Int, val source: SampleSource = SampleSource.HISTORY)

/** Watch sleep stage codes as delivered (1..4); [minutes] is the stage duration. */
data class SleepStage(val start: Long, val stage: Int, val minutes: Int)

data class DailySummary(
    val dayStart: Long,
    val steps: Int,
    val walkSteps: Int,
    val runSteps: Int,
    val distanceMeters: Double,      // from the stride model, see DistanceModel
    val lastHr: HrSample?,
    val lastSpo2: Spo2Sample?,
    val minHr: Int?, val maxHr: Int?, val avgHr: Int?,
)

data class Workout(
    val id: Long = 0,
    val start: Long,
    val end: Long?,
    val sportType: Int,               // watch sport type; 1 = the default outdoor mode
    val distanceMeters: Double,
    val durationSeconds: Int,
    val avgHr: Int?,
    val maxHr: Int?,
    val calories: Int,
)

data class TrackPoint(
    val workoutId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val altitudeM: Double?,
    val accepted: Boolean,           // false = rejected by the distance filter but kept for re-processing
    /**
     * The tracker's distance (m) after this fix, so the stored track reproduces the workout's distance exactly
     * — including a pause, where the first fix after resuming re-anchors without adding the walk in between.
     * Null on rows written before it existed; the charts then fall back to summing the accepted hops.
     */
    val cumulativeM: Double? = null,
)

data class UserProfile(
    val heightCm: Int = 175,
    val weightKg: Int = 75,
    val age: Int = 40,
    val male: Boolean = true,
    val stepGoal: Int = 8000,
)

data class SamplingSettings(
    val continuousHr: Boolean = true,      // F7 01 / F7 02 (10-minute bins)
    val spo2AutoEnabled: Boolean = true,   // 34 03
    val spo2IntervalMin: Int = 10,
    val raiseWristWake: Boolean = true,
)

/** Stride model settings (metres per step); null = derive from height, see DistanceModel. */
data class StrideSettings(val walkStrideM: Double? = null, val runStrideM: Double? = null)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, SYNCING, ERROR }

data class WatchStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val mac: String? = null,
    val firmware: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val lastSyncTime: Long? = null,
    val message: String? = null,
)

data class SyncResult(val steps: Int, val hr: Int, val spo2: Int, val sleep: Int, val error: String? = null)
