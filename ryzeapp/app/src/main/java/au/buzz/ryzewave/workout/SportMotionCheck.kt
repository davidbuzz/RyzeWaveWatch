package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.protocol.SportTypes
import au.buzz.ryzewave.ui.TrackGeometry
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Does the GPS agree with the sport the wearer declared?
 *
 * A fix that shows the wearer went nowhere is a measurement, not an absence of one. For a rowing machine or a spin
 * bike it *confirms* the declared sport: the body stayed inside a few metres for the whole session, which is what
 * that sport looks like. For Outdoor Running the same reading is a contradiction — a treadmill, or a watch left in
 * sport mode on a table. And a "Spinning" session that travelled two kilometres at road speed was a bike ride that
 * got the wrong label. Without GPS, "no distance" could be any of those; with it they are distinguishable.
 *
 * Pure Kotlin: takes the stored track and the sport id, returns a verdict and a sentence for the detail screen.
 */
object SportMotionCheck {

    /** What a sport implies about travel, derived from the same tables the stride gate and the detector use. */
    enum class Expectation { STAYS_PUT, COVERS_GROUND, EITHER }

    enum class Verdict {
        /** Too few usable fixes to say anything. */
        NO_GPS,

        /** Declared a stationary sport and the GPS agrees: the whole session sat inside a small circle. */
        CONFIRMED_STATIONARY,

        /** Declared a travelling sport and the GPS agrees: real ground was covered. */
        CONFIRMED_MOVING,

        /** Declared a stationary sport but the fixes travelled: the label is probably wrong (or it was a car). */
        MISLABELLED_MOVED,

        /** Declared a travelling sport but never left a small circle: treadmill, or a session that was never really happening. */
        MISLABELLED_STILL,

        /** GPS present but the sport implies nothing, or the session was too short or too ambiguous to call. */
        INCONCLUSIVE,
    }

    data class Result(
        val verdict: Verdict,
        val expectation: Expectation,
        /** Radius (m) of the smallest circle around the centroid that holds every usable fix. */
        val radiusM: Double,
        /** Distance covered (m) as the tracker recorded it (0 when unknown). */
        val distanceM: Double,
        /** Usable fixes the verdict rests on. */
        val fixes: Int,
        /** One sentence for the workout detail screen. */
        val message: String,
    )

    /** Fixes worse than this are ignored; a stationary session under trees can still measure. */
    const val MAX_ACCURACY_M = 30f

    /** Fewer usable fixes than this and nothing is claimed. */
    const val MIN_FIXES = 20

    /** A session this short is not judged: warm-up on the spot before a run is normal. */
    const val MIN_DURATION_MS = 4 * 60_000L

    /** "Stayed put" means every usable fix sits inside this radius of the centroid. */
    const val STATIONARY_RADIUS_M = 25.0

    /** "Covered ground" means at least this much recorded distance, or a spread well beyond a court. */
    const val MOVED_DISTANCE_M = 150.0
    const val MOVED_RADIUS_M = 60.0

    /** What each sport implies, from [SportSignature] and [StrideCalibration] so the three tables cannot disagree. */
    fun expectation(sportType: Int?): Expectation {
        if (sportType == null) return Expectation.EITHER
        // A court is a court whether it is indoors or out: tennis is filed as a travelling sport for the stuck
        // detector (outdoor courts, so GPS may show life), but it never covers ground in the sense meant here.
        if (StrideCalibration.sportGait(sportType) == SportGait.COURT) return Expectation.STAYS_PUT
        return when (SportSignature.classOf(sportType)) {
            SportClass.STATIONARY_MACHINE, SportClass.STATIONARY_CARDIO, SportClass.FLOOR_WORK, SportClass.STRENGTH -> Expectation.STAYS_PUT
            SportClass.INDOOR_STEPS -> when (StrideCalibration.sportGait(sportType)) {
                // Court sports and floor classes stay within a room; a treadmill stays within a metre.
                SportGait.COURT, SportGait.REPS -> Expectation.STAYS_PUT
                else -> if (sportType == 0x15 || sportType == 0x1B) Expectation.STAYS_PUT else Expectation.EITHER
            }
            SportClass.MOVEMENT, SportClass.RIDE -> when (sportType) {
                // Field-and-bat sports and long jump cover almost no ground; do not demand travel of them.
                0x0C, 0x5A, 0x72 -> Expectation.EITHER
                else -> Expectation.COVERS_GROUND
            }
            SportClass.UNMONITORED, SportClass.UNKNOWN -> Expectation.EITHER
        }
    }

    fun check(sportType: Int?, points: List<TrackPoint>, durationMs: Long, distanceM: Double): Result {
        val expectation = expectation(sportType)
        val name = sportType?.let { SportTypes.name(it) } ?: "this workout"
        val usable = points.filter { !it.paused && it.accuracyM <= MAX_ACCURACY_M && it.lat.isFinite() && it.lon.isFinite() }
        if (usable.size < MIN_FIXES) {
            return Result(Verdict.NO_GPS, expectation, 0.0, distanceM, usable.size, "No usable GPS, so nothing to compare the sport against")
        }
        val radius = radiusM(usable)
        val moved = distanceM >= MOVED_DISTANCE_M || radius >= MOVED_RADIUS_M
        val still = radius <= STATIONARY_RADIUS_M
        val longEnough = durationMs >= MIN_DURATION_MS
        val where = "inside a ${radius.toInt().coerceAtLeast(1)} m circle"

        val (verdict, message) = when (expectation) {
            Expectation.STAYS_PUT -> when {
                moved -> Verdict.MISLABELLED_MOVED to
                    "GPS shows ${fmtDist(distanceM)} covered, which $name does not do: this session is probably mislabelled"
                still -> Verdict.CONFIRMED_STATIONARY to
                    "GPS confirms you stayed put ($where), as $name should"
                else -> Verdict.INCONCLUSIVE to "GPS spread over $where; neither clearly still nor travelling"
            }
            Expectation.COVERS_GROUND -> when {
                moved -> Verdict.CONFIRMED_MOVING to "GPS confirms ${fmtDist(distanceM)} of real ground covered"
                still && longEnough -> Verdict.MISLABELLED_STILL to
                    "GPS shows you never left $where: a treadmill, or a $name that never happened?"
                else -> Verdict.INCONCLUSIVE to "Too short or too little movement to judge against $name"
            }
            Expectation.EITHER -> when {
                moved -> Verdict.CONFIRMED_MOVING to "GPS recorded ${fmtDist(distanceM)}"
                still -> Verdict.INCONCLUSIVE to "GPS shows the session stayed $where"
                else -> Verdict.INCONCLUSIVE to "GPS spread over $where"
            }
        }
        return Result(verdict, expectation, radius, distanceM, usable.size, message)
    }

    /** Radius of the smallest centroid-centred circle containing all points, in metres. */
    fun radiusM(points: List<TrackPoint>): Double {
        if (points.isEmpty()) return 0.0
        // Only eastM/northM are used, so the pixel parameters are irrelevant; anchor the plane on the first fix.
        val proj = TrackGeometry.Projection(points[0].lat, points[0].lon, metresPerPx = 1.0, centreX = 0f, centreY = 0f)
        val xs = points.map { proj.eastM(it.lon) }
        val ys = points.map { proj.northM(it.lat) }
        val cx = xs.average()
        val cy = ys.average()
        var r = 0.0
        for (i in points.indices) {
            val dx = xs[i] - cx
            val dy = ys[i] - cy
            r = max(r, sqrt(dx * dx + dy * dy))
        }
        return r
    }

    private fun fmtDist(m: Double): String =
        if (m >= 1000) String.format(java.util.Locale.US, "%.2f km", m / 1000) else "${m.toInt()} m"
}
