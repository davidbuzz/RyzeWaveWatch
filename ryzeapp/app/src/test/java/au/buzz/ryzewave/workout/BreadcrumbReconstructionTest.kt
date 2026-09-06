package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.Breadcrumb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class BreadcrumbReconstructionTest {
    /** A straight 1 km walk north at 1.2 m/s, one breadcrumb every 30 s, 5 m accuracy. */
    private fun walk(start: Long, seconds: Int, everyS: Int = 30): List<Breadcrumb> {
        val lat0 = -27.4698; val lon0 = 153.0251
        return (0..seconds step everyS).map { t ->
            val north = 1.2 * t
            Breadcrumb(start + t * 1000L, lat0 + north / 111_320.0, lon0, 5f, 1.2f, 30.0, "WALKING")
        }
    }

    @Test
    fun reconstructsDistanceFromBreadcrumbsInsideTheWindow() {
        val start = 1_000_000L
        val crumbs = walk(start - 600_000L, 1800 + 600)                      // starts 10 min early, ends after
        val r = BreadcrumbReconstruction.forWorkout(7, start, start + 1800_000L, crumbs)
        assertNotNull(r); r!!
        assertTrue("all points inside the window", r.points.all { it.time in start..(start + 1800_000L) })
        assertEquals(7L, r.points.first().workoutId)
        val truth = 1.2 * 1800
        assertTrue("distance ${r.distanceMeters} vs $truth", kotlin.math.abs(r.distanceMeters - truth) / truth < 0.03)
        assertTrue(r.accepted >= r.points.size / 2)
        assertEquals(r.distanceMeters, r.points.last().cumulativeM!!, 0.01)
    }

    @Test
    fun fewerThanTwoPointsGiveNothing() {
        assertNull(BreadcrumbReconstruction.forWorkout(1, 0, 1000, emptyList()))
        assertNull(BreadcrumbReconstruction.forWorkout(1, 0, 1000, walk(0, 0)))
        assertNull(BreadcrumbReconstruction.forWorkout(1, 5_000_000L, 6_000_000L, walk(0, 600)))   // all outside
    }
}
