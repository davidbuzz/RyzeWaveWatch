package au.buzz.ryzewave.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The collision maths that keeps the in-chart "avg NN" / "goal Nk" labels off the data (docs/APP.md "avg label overdraw"). */
class LabelLayoutTest {
    private val box = Box(100f, 50f, 160f, 70f)

    @Test
    fun segmentTests() {
        assertTrue("crosses left to right", box.intersectsSegment(0f, 60f, 300f, 60f))
        assertTrue("diagonal through the corner region", box.intersectsSegment(90f, 40f, 130f, 80f))
        assertTrue("endpoint inside", box.intersectsSegment(120f, 60f, 400f, 400f))
        assertFalse("parallel above", box.intersectsSegment(0f, 40f, 300f, 40f))
        assertFalse("parallel below", box.intersectsSegment(0f, 80f, 300f, 80f))
        assertFalse("vertical, left of the box", box.intersectsSegment(90f, 0f, 90f, 100f))
        assertFalse("diagonal missing the corner", box.intersectsSegment(150f, 0f, 200f, 45f))
        assertFalse("collinear with the top edge but outside", box.intersectsSegment(0f, 50f, 90f, 50f))
    }

    @Test
    fun pointsCountWithinTheRadius() {
        val pts = listOf(98f to 60f, 50f to 60f, 130f to 72f)
        assertEquals(2, LabelLayout.score(box, pts, 3f, emptyList(), emptyList(), emptyList()))
        assertEquals(0, LabelLayout.score(box, pts, 0f, emptyList(), emptyList(), emptyList()))
    }

    /** The build-3 symptom: the label at the right edge sits on the line; the left edge is free and must win. */
    @Test
    fun picksTheFirstFreeCandidateInPreferenceOrder() {
        val rightAbove = Box(240f, 40f, 300f, 60f)
        val rightBelow = Box(240f, 64f, 300f, 84f)
        val leftAbove = Box(0f, 40f, 60f, 60f)
        val line = listOf(200f to 90f, 250f to 50f, 300f to 80f)   // crosses both right-edge boxes
        val idx = LabelLayout.pick(listOf(rightAbove, rightBelow, leftAbove), polylines = listOf(line))
        assertEquals(2, idx)
    }

    @Test
    fun prefersEarlierCandidatesOnTies() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(20f, 0f, 30f, 10f)
        assertEquals(0, LabelLayout.pick(listOf(a, b)))
        val pts = listOf(5f to 5f, 25f to 5f)   // one hit each: still the first
        assertEquals(0, LabelLayout.pick(listOf(a, b), pts, 1f))
    }

    @Test
    fun leastCoveredCandidateWinsWhenNoneIsFree() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(20f, 0f, 30f, 10f)
        val pts = listOf(5f to 5f, 6f to 6f, 25f to 5f)
        assertEquals(1, LabelLayout.pick(listOf(a, b), pts, 1f))
    }

    @Test
    fun overlappingAnotherLabelOutweighsCoveringData() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(20f, 0f, 30f, 10f)
        val maxLabel = Box(5f, 5f, 15f, 15f)          // "154" sits where a would go
        val pts = (0 until 50).map { 21f + it * 0.1f to 5f }   // b is covered in dots
        assertEquals(1, LabelLayout.pick(listOf(a, b), pts, 1f, avoid = listOf(maxLabel)))
    }

    @Test
    fun barsCountAsBlocks() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(20f, 0f, 30f, 10f)
        assertEquals(1, LabelLayout.pick(listOf(a, b), blocks = listOf(Box(2f, 5f, 8f, 100f))))
        assertEquals(0, LabelLayout.pick(listOf(a, b), blocks = listOf(Box(2f, 12f, 8f, 100f))))   // below a: no overlap
    }
}
