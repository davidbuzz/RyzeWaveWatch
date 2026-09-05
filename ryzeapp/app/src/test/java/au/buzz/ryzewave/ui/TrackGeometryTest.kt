package au.buzz.ryzewave.ui

import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.workout.RealTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class TrackGeometryTest {
    private val lat0 = -27.5
    private val lon0 = 153.0
    private val degPerMetreN = 1.0 / TrackGeometry.METRES_PER_DEGREE
    private val degPerMetreE = 1.0 / (TrackGeometry.METRES_PER_DEGREE * cos(Math.toRadians(lat0)))

    private fun pt(i: Int, northM: Double, eastM: Double, accepted: Boolean = true, cumulativeM: Double? = null) =
        TrackPoint(1L, 1_000_000L + i * 1000L, lat0 + northM * degPerMetreN, lon0 + eastM * degPerMetreE, 5f, 1f, null, accepted, cumulativeM)

    /** A 100 m square walked clockwise from the south-west corner. */
    private fun square(): List<TrackPoint> = listOf(pt(0, 0.0, 0.0), pt(1, 100.0, 0.0), pt(2, 100.0, 100.0), pt(3, 0.0, 100.0), pt(4, 0.0, 0.0))

    @Test
    fun squareLoopProjectsToASquareNorthUpAndCentred() {
        val proj = TrackGeometry.projection(square(), 400f, 300f, 20f)!!
        // the limiting side is the height: 260 px for 100 m
        assertEquals(100.0 / 260.0, proj.metresPerPx, 1e-9)
        val sw = proj.project(square()[0])
        val nw = proj.project(square()[1])
        val ne = proj.project(square()[2])
        val se = proj.project(square()[3])
        val width = ne.x - nw.x
        val height = sw.y - nw.y
        assertEquals(260f, width, 0.5f)
        assertEquals(260f, height, 0.5f)
        assertEquals(width, height, 0.5f)                       // one scale for both axes
        assertTrue("north must be up", nw.y < sw.y)
        assertTrue("east must be right", ne.x > nw.x)
        assertEquals(0f, sw.x - nw.x, 0.01f)                    // vertical sides stay vertical
        assertEquals(0f, se.y - sw.y, 0.01f)
        // centred in the canvas
        assertEquals(200f, (nw.x + ne.x) / 2f, 0.01f)
        assertEquals(150f, (nw.y + sw.y) / 2f, 0.01f)
        assertEquals(20f, nw.y, 0.5f)                          // the limiting side (height) touches the padding
        assertEquals(70f, nw.x, 0.5f)                          // the other side is centred: (400 - 260) / 2
    }

    @Test
    fun kmMarksLandAtEveryThousandMetres() {
        // straight north, a fix every 100 m for 2.5 km, no stored totals -> summed hops
        val line = (0..25).map { pt(it, it * 100.0, 0.0) }
        val marks = TrackGeometry.kmMarks(line)
        assertEquals(listOf(1, 2), marks.map { it.km })
        assertEquals(lat0 + 1000.0 * degPerMetreN, marks[0].lat, 1e-7)      // ~1 cm
        assertEquals(lat0 + 2000.0 * degPerMetreN, marks[1].lat, 1e-7)
        assertEquals(lon0, marks[0].lon, 1e-9)
        // the stored running total wins over the geometry (a pause makes it shorter than the hops)
        val paused = line.mapIndexed { i, p -> p.copy(cumulativeM = i * 90.0) }
        val m2 = TrackGeometry.kmMarks(paused)
        assertEquals(listOf(1, 2), m2.map { it.km })
        // 1000 m is crossed between fix 11 (990) and 12 (1080): 1/9 of the 100 m hop
        assertEquals(lat0 + (1100.0 + 100.0 / 9.0) * degPerMetreN, m2[0].lat, 1e-7)
        // below a kilometre nothing is marked
        assertTrue(TrackGeometry.kmMarks(square()).isEmpty())
    }

    @Test
    fun cumulativeMetresFallsBackToHopsWhenAnyTotalIsMissing() {
        val line = (0..3).map { pt(it, it * 100.0, 0.0, cumulativeM = it * 50.0) }
        val stored = TrackGeometry.cumulativeMetres(line)
        assertEquals(150.0, stored.last(), 1e-9)
        val mixed = line.mapIndexed { i, p -> if (i == 2) p.copy(cumulativeM = null) else p }
        val hops = TrackGeometry.cumulativeMetres(mixed)
        assertEquals(300.0, hops.last(), 0.5)
        assertEquals(0.0, hops.first(), 0.0)
        // never backwards even when the rows are
        val backwards = line.mapIndexed { i, p -> p.copy(cumulativeM = if (i == 2) 20.0 else i * 50.0) }
        assertEquals(listOf(0.0, 50.0, 50.0, 150.0), TrackGeometry.cumulativeMetres(backwards).toList())
    }

    @Test
    fun emptyAndSinglePointTracksDoNotCrash() {
        assertNull(TrackGeometry.projection(emptyList(), 400f, 300f, 20f))
        assertTrue(TrackGeometry.kmMarks(emptyList()).isEmpty())
        assertEquals(0, TrackGeometry.cumulativeMetres(emptyList()).size)

        val single = listOf(pt(0, 0.0, 0.0))
        val proj = TrackGeometry.projection(single, 400f, 300f, 20f)
        assertNotNull(proj)
        val xy = proj!!.project(single[0])
        assertEquals(200f, xy.x, 1e-3f)
        assertEquals(150f, xy.y, 1e-3f)
        // a degenerate extent gets the minimum-extent zoom, so the 10 m bar still fits in 45 % of the width
        assertEquals(TrackGeometry.MIN_EXTENT_M / 260.0, proj.metresPerPx, 1e-9)
        assertTrue(TrackGeometry.scaleBar(proj.metresPerPx, 180f).px <= 180f)
        assertTrue(TrackGeometry.kmMarks(single).isEmpty())
        // two fixes at the same spot: still no division by zero
        val same = listOf(pt(0, 0.0, 0.0), pt(1, 0.0, 0.0))
        assertNotNull(TrackGeometry.projection(same, 400f, 300f, 20f))
        assertTrue(TrackGeometry.kmMarks(same).isEmpty())
        // NaN coordinates are ignored, not projected
        val nan = listOf(pt(0, 0.0, 0.0).copy(lat = Double.NaN))
        assertNull(TrackGeometry.projection(nan, 400f, 300f, 20f))
    }

    @Test
    fun scaleBarPicksTheLargestStepThatFits() {
        assertEquals(TrackGeometry.ScaleBar(100, 100f, "100 m"), TrackGeometry.scaleBar(1.0, 160f))
        assertEquals(TrackGeometry.ScaleBar(500, 500f, "500 m"), TrackGeometry.scaleBar(1.0, 500f))
        assertEquals(TrackGeometry.ScaleBar(1000, 100f, "1 km"), TrackGeometry.scaleBar(10.0, 160f))
        assertEquals(TrackGeometry.ScaleBar(50, 125f, "50 m"), TrackGeometry.scaleBar(0.4, 160f))
        // nothing fits: the shortest bar is still returned (the caller decides how to draw it)
        assertEquals(TrackGeometry.ScaleBar(10, 200f, "10 m"), TrackGeometry.scaleBar(0.05, 100f))
    }

    @Test
    fun rejectedFixesDoNotStretchTheFrame() {
        val withOutlier = square() + pt(9, 1000.0, 1000.0, accepted = false)
        val proj = TrackGeometry.projection(withOutlier, 400f, 300f, 20f)!!
        assertEquals(100.0 / 260.0, proj.metresPerPx, 1e-9)
        val far = proj.project(withOutlier.last())
        assertTrue("the outlier lands off-canvas and is clipped", far.x > 400f && far.y < 0f)
        // only rejected fixes: they define the frame instead
        val allRejected = square().map { it.copy(accepted = false) }
        val proj2 = TrackGeometry.projection(allRejected, 400f, 300f, 20f)!!
        assertEquals(100.0 / 260.0, proj2.metresPerPx, 1e-9)
        assertTrue(TrackGeometry.accepted(allRejected).isEmpty())
    }

    /** The real outdoor walk (captures/pixel_outdoor_walk_20260905): 146 fixes, 86 accepted, a short loop of 155 m. */
    @Test
    fun realOutdoorWalkProjectsAndMeasures() {
        val points = RealTrack.pixelOutdoorWalk()
        assertEquals(146, points.size)
        val accepted = TrackGeometry.accepted(points)
        assertEquals(86, accepted.size)
        val cum = TrackGeometry.cumulativeMetres(accepted)
        assertEquals(155.17, cum.last(), 0.01)
        assertTrue(TrackGeometry.kmMarks(points).isEmpty())
        val proj = TrackGeometry.projection(points, 1000f, 600f, 40f)!!
        val xs = accepted.map { proj.project(it).x }
        val ys = accepted.map { proj.project(it).y }
        // fits inside the padding, and the limiting side touches it
        assertTrue(xs.all { it >= 40f - 0.01f && it <= 960f + 0.01f })
        assertTrue(ys.all { it >= 40f - 0.01f && it <= 560f + 0.01f })
        val spanE = (xs.max() - xs.min()) * proj.metresPerPx
        val spanN = (ys.max() - ys.min()) * proj.metresPerPx
        // the projected box agrees with the haversine size of the accepted fixes' bounding box
        val minLat = accepted.minOf { it.lat }; val maxLat = accepted.maxOf { it.lat }
        val minLon = accepted.minOf { it.lon }; val maxLon = accepted.maxOf { it.lon }
        val midLat = (minLat + maxLat) / 2
        val haverE = ChartData.haversineM(midLat, minLon, midLat, maxLon)
        val haverN = ChartData.haversineM(minLat, minLon, maxLat, minLon)
        assertEquals("extent east", haverE, spanE, 0.3)
        assertEquals("extent north", haverN, spanN, 0.3)
        assertTrue("a short loop, tens of metres: $spanE x $spanN", spanE in 20.0..60.0 && spanN in 20.0..60.0)
        // the wider side fills the padded height (600 - 80 px), north up: the northernmost fix has the smallest y
        assertEquals(560f, ys.max(), 0.01f)
        assertEquals(40f, ys.min(), 0.01f)
        assertEquals(accepted.maxBy { it.lat }.lat, accepted[ys.indexOf(ys.min())].lat, 1e-12)
        assertEquals(TrackGeometry.ScaleBar(10, (10.0 / proj.metresPerPx).toFloat(), "10 m"), TrackGeometry.scaleBar(proj.metresPerPx, 450f))
    }
}
