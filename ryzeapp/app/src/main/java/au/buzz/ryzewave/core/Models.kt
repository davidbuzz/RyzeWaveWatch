package au.buzz.ryzewave.core

/** Shared data model. Times are epoch milliseconds in the phone's local clock unless stated otherwise. */

enum class SampleSource { HISTORY, LIVE, WORKOUT, AUTO }

/** One hour of steps as reported by the watch (`B2` record). [hourStart] is the start of the hour. */
data class StepsHour(val hourStart: Long, val total: Int, val walk: Int, val run: Int)

data class HrSample(val time: Long, val bpm: Int, val source: SampleSource = SampleSource.HISTORY)

data class Spo2Sample(val time: Long, val percent: Int, val source: SampleSource = SampleSource.HISTORY)

/**
 * A sleep stage over [minutes] starting at [start]. [stage] is the watch's own code (1 = deep, 2 = light,
 * 3 = REM, 4 = awake) or the app-generated [GENERIC_ASLEEP] (5) — "asleep, stage unknown" — which the honest
 * reconstruction uses to fill a sleep window the watch did not stage. Generic-asleep counts as asleep
 * everywhere (never as awake) but is never split into deep/light/REM.
 */
data class SleepStage(val start: Long, val stage: Int, val minutes: Int) {
    companion object {
        const val DEEP = 1
        const val LIGHT = 2
        const val REM = 3
        const val AWAKE = 4
        /** App-generated: asleep, real stage unknown (not from the watch). Maps to Health Connect generic "sleeping". */
        const val GENERIC_ASLEEP = 5
    }
}

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
    /**
     * Steps for this workout as counted by the watch (max of the session-step field in the realtime `FD 01`
     * pushes). Null on rows recorded before per-workout steps existed. Preferred for stride calibration.
     */
    val steps: Int? = null,
    /** Steps for this workout from the phone's `TYPE_STEP_COUNTER` (paused fixes excluded); null when unavailable. */
    val phoneSteps: Int? = null,
    /**
     * Health Connect exercise type the user chose for this session ([ExerciseSessionRecord.EXERCISE_TYPE_*]).
     * When set it overrides the speed/sport-id heuristic in the export; null = use the heuristic.
     */
    val exerciseTypeOverride: Int? = null,
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
    /**
     * True for a fix received while the workout was PAUSED: stored so the track stays continuous (a missed
     * resume never loses the route) but never counted towards distance ([accepted] is always false for these).
     */
    val paused: Boolean = false,
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

/**
 * One point of the always-on GPS breadcrumb (opt-in, Settings): a fix the phone took on its own while it detected
 * movement, outside any workout, so a workout whose own tracking failed can be reconstructed from it.
 * [activity] is the phone's detected activity name ("WALKING", "RUNNING", "ON_BICYCLE", …) if known.
 */
data class Breadcrumb(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val altitudeM: Double? = null,
    val activity: String? = null,
)
