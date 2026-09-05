package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.GpsDistanceTracker
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS distance with jitter rejection, per docs/PLAN.md §3b:
 *  1. fixes with accuracy worse than [maxAccuracyM] (60 m) are dropped. The cut used to be 20 m, which reproduced
 *     the vendor bug on a poor-signal run (every fix rejected, 0.0 km after 20 minutes); 60 m lets a 25–35 m
 *     accuracy run under trees still measure: with Doppler it comes within 1 %, without Doppler it accumulates in
 *     coarse ≥ 1.5 × accuracy hops (rule 2) and comes within a few percent short. Fixes beyond 60 m are worse
 *     than a stride estimate and are still dropped;
 *  2. when the reported speed is below [stationarySpeedMps] (1.0 m/s — hand-held receivers under trees or in
 *     streets happily report 0.5–0.9 m/s while standing still), movement smaller than
 *     max([jitterRadiusFactor] × max(accuracy, anchor accuracy), [minMoveM]) from the last *accepted* fix is
 *     treated as jitter and ignored — the anchor stays put, so slow real movement still accumulates once it
 *     leaves the radius. The factor (1.5) is there because the *difference* of two positions each scattered
 *     within `accuracy` spreads about 1.4 × accuracy: hops shorter than that are more noise than movement, and
 *     summing them over-counts (the shorter the hop, the worse: the excess per hop is about
 *     noise² / hop length);
 *  3. a fix is also rejected as a spike when the distance it implies is not plausible for the time elapsed:
 *     the implied speed exceeds [maxSpeedMps] (12 m/s, faster than any runner), or the jump is larger than
 *     max(reported speed, [plausibleSpeedMps]) × dt + both accuracy radii — a multipath jump 25 m off the path
 *     between two 1-second fixes fails both, while a real 25 m of slow walking over 10 s passes;
 *  4. distance is the sum over accepted fixes of the movement since the previous accepted fix. When the
 *     receiver reports a Doppler speed of at least [stationarySpeedMps] and the fixes are at most
 *     [dopplerMaxDtS] (5 s) apart, that movement is `speed × dt` (capped at the position hop plus the accuracy
 *     radius, so a bogus speed cannot run away from the position): Doppler speed is far less noisy than the
 *     difference of two 1 Hz positions, whose per-fix scatter would otherwise be integrated straight into the
 *     distance (with 6 m accuracy and 2 m of independent noise per fix, +48 % over a 20-minute run). Otherwise
 *     — no receiver speed, or a gap between fixes — the movement is the haversine hop, so a GPS outage is
 *     bridged by the straight line and a speed-less receiver still accumulates in hops (rule 2);
 *  5. pace comes from a rolling window that spans at least the last [paceWindowMs] (30 s) or the last
 *     [paceWindowM] (100 m), whichever is reached first, never from two consecutive fixes;
 *  6. speed is the receiver's Doppler speed of the last accepted fix, or distance/time since the previous
 *     accepted fix when the receiver reports none.
 *
 * Not thread-safe: the caller serialises [addFix] (the controller does).
 */
class ZzBaseTracker(
    private val maxAccuracyM: Float = 60f,
    private val minMoveM: Float = 3f,
    private val stationarySpeedMps: Float = 1.0f,
    private val paceWindowMs: Long = 30_000L,
    private val paceWindowM: Double = 100.0,
    private val maxSpeedMps: Double = 12.0,
    private val plausibleSpeedMps: Double = 2.5,
    private val jitterRadiusFactor: Float = 1.5f,
    private val dopplerMaxDtS: Double = 5.0,
) : GpsDistanceTracker {

    private class Sample(val time: Long, val cumulativeM: Double)

    private val window = ArrayDeque<Sample>()

    private var hasAnchor = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorTime = 0L
    private var anchorAccuracyM = 0f

    override var distanceMeters: Double = 0.0
        private set
    override var paceSecPerKm: Double = 0.0
        private set
    override var speedMps: Double = 0.0
        private set

    /** Diagnostics for the UI / notes. */
    var acceptedCount: Int = 0
        private set
    var rejectedAccuracyCount: Int = 0
        private set
    var rejectedJitterCount: Int = 0
        private set
    /** Fixes rejected because the jump was not plausible for the elapsed time (rule 3). */
    var rejectedSpikeCount: Int = 0
        private set
    var lastAccuracyM: Float? = null
        private set

    override fun addFix(time: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float): Boolean {
        lastAccuracyM = accuracyM
        if (accuracyM.isNaN() || accuracyM < 0f || accuracyM > maxAccuracyM || lat.isNaN() || lon.isNaN()) {
            rejectedAccuracyCount++
            return false
        }
        if (!hasAnchor) {
            hasAnchor = true
            anchorLat = lat
            anchorLon = lon
            anchorTime = time
            anchorAccuracyM = accuracyM
            acceptedCount++
            this.speedMps = max(0f, speedMps).toDouble()
            appendWindow(time, distanceMeters)
            recomputePace()
            return true
        }
        if (time < anchorTime) {
            // out-of-order fix; nothing sensible to do with it
            rejectedJitterCount++
            return false
        }
        val d = haversineMeters(anchorLat, anchorLon, lat, lon)
        val reported = max(0f, speedMps).toDouble()
        val threshold = max(jitterRadiusFactor * max(accuracyM, anchorAccuracyM), minMoveM).toDouble()
        if (reported < stationarySpeedMps && d < threshold) {
            rejectedJitterCount++
            this.speedMps = 0.0
            appendWindow(time, distanceMeters)     // "no movement at this time" keeps the pace window honest
            recomputePace()
            return false
        }
        val dtS = (time - anchorTime) / 1000.0
        if (dtS > 0.0) {
            val implied = d / dtS
            val allowed = max(reported, plausibleSpeedMps) * dtS + accuracyM + anchorAccuracyM
            if (implied > maxSpeedMps || d > allowed) {
                // A spike: the position is not trustworthy, keep the anchor and wait for the next fix.
                rejectedSpikeCount++
                return false
            }
        } else if (d > accuracyM + anchorAccuracyM) {
            rejectedSpikeCount++
            return false
        }
        val moved = if (reported >= stationarySpeedMps && dtS > 0.0 && dtS <= dopplerMaxDtS) {
            min(reported * dtS, d + max(accuracyM, anchorAccuracyM))
        } else d
        distanceMeters += moved
        this.speedMps = when {
            reported > 0.0 -> reported
            dtS > 0.0 -> d / dtS
            else -> 0.0
        }
        anchorLat = lat
        anchorLon = lon
        anchorTime = time
        anchorAccuracyM = accuracyM
        acceptedCount++
        appendWindow(time, distanceMeters)
        recomputePace()
        return true
    }

    override fun reset() {
        window.clear()
        hasAnchor = false
        anchorLat = 0.0
        anchorLon = 0.0
        anchorTime = 0L
        anchorAccuracyM = 0f
        distanceMeters = 0.0
        paceSecPerKm = 0.0
        speedMps = 0.0
        acceptedCount = 0
        rejectedAccuracyCount = 0
        rejectedJitterCount = 0
        rejectedSpikeCount = 0
        lastAccuracyM = null
    }

    /**
     * Forget the anchor and the pace window but keep the distance: used after a pause so the walk between the
     * pause point and the resume point is not counted. It is deliberately *not* called after a GPS outage: the
     * straight line from the last fix before the gap to the first one after it is the best distance estimate
     * we have for a tunnel or a tree-covered stretch, so the controller lets the next fix bridge the gap.
     */
    fun markGap() {
        window.clear()
        hasAnchor = false
        paceSecPerKm = 0.0
        speedMps = 0.0
    }

    private fun appendWindow(time: Long, cumulativeM: Double) {
        window.addLast(Sample(time, cumulativeM))
        if (window.size > MAX_WINDOW_SAMPLES) window.removeFirst()
    }

    private fun recomputePace() {
        val newest = window.lastOrNull()
        if (newest == null) {
            paceSecPerKm = 0.0
            return
        }
        // Drop samples older than needed: after this the oldest sample is the first one (from the newest
        // backwards) that reaches either threshold, or simply the oldest we have.
        while (window.size >= 2) {
            val second = window[1]
            val spansTime = newest.time - second.time >= paceWindowMs
            val spansDist = newest.cumulativeM - second.cumulativeM >= paceWindowM
            if (spansTime || spansDist) window.removeFirst() else break
        }
        val oldest = window.first()
        val dtS = (newest.time - oldest.time) / 1000.0
        val dd = newest.cumulativeM - oldest.cumulativeM
        paceSecPerKm = if (dd >= MIN_PACE_DISTANCE_M && dtS >= MIN_PACE_SECONDS) dtS / dd * 1000.0 else 0.0
    }

    companion object {
        private const val MAX_WINDOW_SAMPLES = 4096
        private const val MIN_PACE_DISTANCE_M = 5.0
        private const val MIN_PACE_SECONDS = 5.0
        const val EARTH_RADIUS_M = 6_371_000.0

        /** Great-circle distance in metres between two WGS-84 points. */
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val dPhi = Math.toRadians(lat2 - lat1)
            val dLambda = Math.toRadians(lon2 - lon1)
            val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_M * c
        }
    }
}
