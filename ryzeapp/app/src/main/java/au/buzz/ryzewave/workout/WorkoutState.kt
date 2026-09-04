package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import java.util.Locale

/** Lifecycle of the current workout. STOPPED is also the idle state before anything has been started. */
enum class WorkoutPhase { RUNNING, PAUSED, STOPPED }

/**
 * Live view of the workout, published by [WorkoutController.state] once per second and on every GPS fix /
 * HR sample. After [WorkoutController.stop] the phase is STOPPED and the final numbers stay in place so the
 * UI can show a summary ([workoutId] points at the stored row).
 */
data class WorkoutState(
    val state: WorkoutPhase = WorkoutPhase.STOPPED,
    val workoutId: Long? = null,
    val sportType: Int = 1,
    val startTime: Long? = null,
    /** Active seconds (pauses excluded). */
    val elapsedSeconds: Int = 0,
    val distanceMeters: Double = 0.0,
    /** Rolling-window pace in seconds per km; 0.0 = unknown / not moving. */
    val paceSecPerKm: Double = 0.0,
    val speedMps: Double = 0.0,
    /** MET-based kcal estimate (speed × weight). */
    val calories: Int = 0,
    val lastHr: Int? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    /** All HR samples received since start (source WORKOUT), for the live chart. */
    val hrSamples: List<HrSample> = emptyList(),
    /** Accuracy of the most recent fix in metres; null until the first fix. */
    val gpsAccuracyM: Float? = null,
    /** False when the location provider says GPS is unavailable (or before the first fix). */
    val gpsAvailable: Boolean = false,
    val trackPointCount: Int = 0,
    val acceptedPointCount: Int = 0,
    /** Last watch/database problem, cleared on the next successful watch call. The workout keeps running. */
    val error: String? = null,
    /**
     * Problem reported by the host service (missing location permission, GPS unavailable, foreground start
     * refused). Independent of [error]: it stays until the service clears it, so a watch echo cannot wipe it.
     */
    val hostError: String? = null,
    /** Bumped on every [error] / [hostError] change, so a UI transition can notice a fresh failure. */
    val errorSeq: Int = 0,
) {
    /** Everything worth showing, host problems first. */
    val message: String? get() = listOfNotNull(hostError, error).joinToString(" · ").ifEmpty { null }

    val isActive: Boolean get() = state != WorkoutPhase.STOPPED
    val isRunning: Boolean get() = state == WorkoutPhase.RUNNING

    /** Average pace over the whole workout (s/km), 0.0 when there is no distance yet. */
    val averagePaceSecPerKm: Double
        get() = if (distanceMeters > 0.0 && elapsedSeconds > 0) elapsedSeconds / distanceMeters * 1000.0 else 0.0
}

/** Text helpers shared by the notification and the UI. Pure Kotlin. */
object WorkoutFormat {
    fun elapsed(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }

    /** "5:12 /km", or "--:-- /km" when unknown or slower than 99:59. */
    fun pace(secPerKm: Double): String {
        if (secPerKm <= 0.0 || !secPerKm.isFinite() || secPerKm > 5999.0) return "--:-- /km"
        val total = secPerKm.toInt()
        return String.format(Locale.ROOT, "%d:%02d /km", total / 60, total % 60)
    }

    fun distance(meters: Double): String =
        if (meters < 1000.0) String.format(Locale.ROOT, "%d m", meters.toInt())
        else String.format(Locale.ROOT, "%.2f km", meters / 1000.0)

    fun speed(mps: Double): String = String.format(Locale.ROOT, "%.1f km/h", mps * 3.6)

    fun gps(accuracyM: Float?, available: Boolean): String = when {
        accuracyM == null -> if (available) "GPS searching" else "no GPS"
        else -> String.format(Locale.ROOT, "GPS ±%d m", accuracyM.toInt())
    }

    /** One-line summary for the notification: "12:34 · 1.23 km · 5:12 /km · HR 132 · GPS ±8 m". */
    fun summary(st: WorkoutState): String = buildString {
        append(elapsed(st.elapsedSeconds)).append(" · ").append(distance(st.distanceMeters))
        append(" · ").append(pace(st.paceSecPerKm))
        st.lastHr?.let { append(" · HR ").append(it) }
        append(" · ").append(gps(st.gpsAccuracyM, st.gpsAvailable))
    }

    /** Expanded notification text, one metric group per line. */
    fun detail(st: WorkoutState): String = listOf(
        "Time ${elapsed(st.elapsedSeconds)}   Distance ${distance(st.distanceMeters)}",
        "Pace ${pace(st.paceSecPerKm)}   Speed ${speed(st.speedMps)}",
        "HR ${st.lastHr ?: "--"} (avg ${st.avgHr ?: "--"}, max ${st.maxHr ?: "--"})   ${st.calories} kcal",
        "${gps(st.gpsAccuracyM, st.gpsAvailable)} (${st.acceptedPointCount}/${st.trackPointCount} fixes used)",
    ).joinToString("\n")
}
