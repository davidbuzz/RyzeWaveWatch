package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.Breadcrumb
import au.buzz.ryzewave.core.TrackPoint

/**
 * Rebuilds a workout's track and distance from the breadcrumb points inside its time window, through the same
 * [DefaultGpsDistanceTracker] rules a live workout uses, so the numbers are comparable. Pure; unit-tested.
 */
object BreadcrumbReconstruction {
    data class Result(val points: List<TrackPoint>, val distanceMeters: Double, val accepted: Int)

    /** Null when fewer than two breadcrumbs fall inside [start, end]. */
    fun forWorkout(workoutId: Long, start: Long, end: Long, crumbs: List<Breadcrumb>): Result? {
        val inWindow = crumbs.filter { it.time in start..end }.sortedBy { it.time }.distinctBy { it.time }
        if (inWindow.size < 2) return null
        val tracker = DefaultGpsDistanceTracker()
        val points = ArrayList<TrackPoint>(inWindow.size)
        var accepted = 0
        for (c in inWindow) {
            val ok = tracker.addFix(c.time, c.lat, c.lon, c.accuracyM, c.speedMps)
            if (ok) accepted++
            points += TrackPoint(workoutId, c.time, c.lat, c.lon, c.accuracyM, c.speedMps, c.altitudeM, ok, tracker.distanceMeters)
        }
        tracker.markGap()
        return Result(points, tracker.distanceMeters, accepted)
    }
}
