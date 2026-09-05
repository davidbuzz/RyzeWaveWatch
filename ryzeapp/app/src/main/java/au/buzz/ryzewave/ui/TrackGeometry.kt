package au.buzz.ryzewave.ui

import au.buzz.ryzewave.core.TrackPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure maths behind the workout track plot (`TrackPlot` in ui/TrackPlot.kt): a north-up equirectangular
 * projection of a track scaled to fit a canvas with padding, the ground positions where whole kilometres were
 * crossed, and the scale bar. No Android imports, unit-tested in `TrackGeometryTest`.
 *
 * The projection is local flat-Earth metres around the track's centre: one degree of latitude is
 * [METRES_PER_DEGREE], one degree of longitude is that times cos(centre latitude). Good to well under a metre
 * over the few kilometres a workout covers, which is all the plot needs.
 */
object TrackGeometry {
    /** Metres per degree of latitude (2π × 6 371 000 m / 360). */
    const val METRES_PER_DEGREE = 111_194.9

    /**
     * A track smaller than this (a standing start, a single fix) is shown at the zoom this extent would get,
     * so the plot never zooms into GPS noise and the 10 m scale bar always fits.
     */
    const val MIN_EXTENT_M = 25.0

    /** Scale-bar lengths to choose from (metres); the largest that fits is used, the smallest as a last resort. */
    val SCALE_STEPS_M = intArrayOf(10, 50, 100, 500, 1000)

    /** A canvas position in pixels; y grows downwards, so north is up. */
    data class XY(val x: Float, val y: Float)

    /** A whole-kilometre crossing: where on the ground, and which kilometre (1, 2, …). */
    data class KmMark(val lat: Double, val lon: Double, val km: Int)

    /** The scale bar: length in metres and pixels, and its label ("100 m", "1 km"). */
    data class ScaleBar(val metres: Int, val px: Float, val label: String)

    /**
     * Projection of one track onto a canvas: local metres around ([lat0], [lon0]) at [metresPerPx] for both
     * axes (a square loop stays square), the track's bounding box centred on ([centreX], [centreY]).
     */
    class Projection(
        val lat0: Double,
        val lon0: Double,
        val metresPerPx: Double,
        val centreX: Float,
        val centreY: Float,
    ) {
        private val cosLat = cos(Math.toRadians(lat0))

        /** Metres east of the centre. */
        fun eastM(lon: Double): Double = (lon - lon0) * cosLat * METRES_PER_DEGREE

        /** Metres north of the centre. */
        fun northM(lat: Double): Double = (lat - lat0) * METRES_PER_DEGREE

        fun project(lat: Double, lon: Double): XY =
            XY(centreX + (eastM(lon) / metresPerPx).toFloat(), centreY - (northM(lat) / metresPerPx).toFloat())

        fun project(p: TrackPoint): XY = project(p.lat, p.lon)
    }

    /** Accepted fixes with finite coordinates, oldest first. */
    fun accepted(points: List<TrackPoint>): List<TrackPoint> =
        points.filter { it.accepted && it.lat.isFinite() && it.lon.isFinite() }.sortedBy { it.time }

    /**
     * Fits the track into a [widthPx] × [heightPx] canvas leaving [paddingPx] on every side. The frame is the
     * bounding box of the accepted fixes (all fixes when none was accepted), so a wild rejected fix cannot
     * squash the plot; rejected fixes outside the canvas are simply clipped. Null when there is nothing to draw.
     */
    fun projection(points: List<TrackPoint>, widthPx: Float, heightPx: Float, paddingPx: Float): Projection? {
        val basis = accepted(points).ifEmpty { points.filter { it.lat.isFinite() && it.lon.isFinite() } }
        if (basis.isEmpty()) return null
        val minLat = basis.minOf { it.lat }
        val maxLat = basis.maxOf { it.lat }
        val minLon = basis.minOf { it.lon }
        val maxLon = basis.maxOf { it.lon }
        val lat0 = (minLat + maxLat) / 2.0
        val lon0 = (minLon + maxLon) / 2.0
        val extentE = (maxLon - minLon) * cos(Math.toRadians(lat0)) * METRES_PER_DEGREE
        val extentN = (maxLat - minLat) * METRES_PER_DEGREE
        val availW = max(1f, widthPx - 2f * paddingPx).toDouble()
        val availH = max(1f, heightPx - 2f * paddingPx).toDouble()
        val fit = max(extentE / availW, extentN / availH)
        val floor = MIN_EXTENT_M / minOf(availW, availH)
        val metresPerPx = if (fit.isFinite() && fit > floor) fit else floor
        return Projection(lat0, lon0, metresPerPx, widthPx / 2f, heightPx / 2f)
    }

    /**
     * Distance so far (m) at each of [acceptedPoints]: the tracker's own running total when every point stores
     * one (so a pause does not add the walk in between), otherwise the sum of the haversine hops — the same rule
     * as `ChartData.cumulativeDistance`.
     */
    fun cumulativeMetres(acceptedPoints: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(acceptedPoints.size)
        if (acceptedPoints.isEmpty()) return out
        if (acceptedPoints.all { it.cumulativeM != null && it.cumulativeM.isFinite() }) {
            var last = 0.0
            for ((i, p) in acceptedPoints.withIndex()) {
                last = max(last, p.cumulativeM!!)
                out[i] = last
            }
            return out
        }
        var d = 0.0
        for (i in 1 until acceptedPoints.size) {
            val a = acceptedPoints[i - 1]
            val b = acceptedPoints[i]
            d += ChartData.haversineM(a.lat, a.lon, b.lat, b.lon)
            out[i] = d
        }
        return out
    }

    /** Where each whole kilometre was crossed along the accepted fixes (linear interpolation between fixes). */
    fun kmMarks(points: List<TrackPoint>): List<KmMark> {
        val acc = accepted(points)
        if (acc.size < 2) return emptyList()
        val cum = cumulativeMetres(acc)
        val out = ArrayList<KmMark>()
        var next = 1000.0
        for (i in 1 until acc.size) {
            val a = cum[i - 1]
            val b = cum[i]
            while (b >= next) {
                val f = if (b <= a) 1.0 else ((next - a) / (b - a)).coerceIn(0.0, 1.0)
                val pa = acc[i - 1]
                val pb = acc[i]
                out += KmMark(pa.lat + (pb.lat - pa.lat) * f, pa.lon + (pb.lon - pa.lon) * f, (next / 1000.0).roundToInt())
                next += 1000.0
            }
        }
        return out
    }

    /** The longest of [SCALE_STEPS_M] that is at most [maxPx] wide at [metresPerPx]; the shortest when none fits. */
    fun scaleBar(metresPerPx: Double, maxPx: Float): ScaleBar {
        var chosen = SCALE_STEPS_M.first()
        for (m in SCALE_STEPS_M) if (m / metresPerPx <= maxPx) chosen = m
        return ScaleBar(chosen, (chosen / metresPerPx).toFloat(), scaleLabel(chosen))
    }

    fun scaleLabel(metres: Int): String = if (metres >= 1000) "${metres / 1000} km" else "$metres m"
}
