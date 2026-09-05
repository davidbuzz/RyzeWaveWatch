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
 *  1b. the first anchor must be a decent fix: while nothing has been accepted yet, a fix worse than
 *     [firstFixAccuracyM] (20 m) is held as a candidate rather than anchored on (the caller sees it as rejected),
 *     and the tracker keeps waiting for a fix of ≤ 20 m. If none arrives within [firstFixWaitS] (15 s) of the
 *     first held fix, the best fix seen so far becomes the anchor and the walk is measured from there. The first
 *     real walk anchored on a 52 m fix about 45 m off the loop the walker made and credited the hop back to the
 *     loop as distance; with this rule that fix is skipped and the 17 m fix 9 s later starts the walk. The
 *     movement *during* the wait is real — only where it started is doubted — so the Doppler integral of rule 4
 *     runs from the first held fix (a better candidate mid-wait does not restart it) and is credited, capped as
 *     in rule 4 (hop from the first held fix + max accuracy) and as in rule 4b, on every exit from the wait: a
 *     ≤ 20 m fix arriving, the current fix being the best at expiry, and the candidate becoming the anchor at
 *     expiry (then the current fix is judged against it and the credit lands at the first accepted fix). Nothing
 *     is credited across a broken chain (held fixes more than [dopplerMaxDtS] apart). Without this a 3 m/s run
 *     lost up to 45 m at every start and every resume after a pause; and the wait can never end in a rejection:
 *     when the current fix is a spike from the candidate (rule 3 — a 6 m/s cyclist without Doppler is 90 m away
 *     after 15 s, more than 2.5 × 15 + two radii) the current fix becomes the anchor instead, with the same
 *     credit, so the ride is measured from here rather than deadlocked (every later fix was farther still);
 *  1c. while the anchor is a *starting* anchor worse than [firstFixAccuracyM] — the first fix of the walk, the
 *     best fix of an expired wait, or the re-anchor after [markGap] — and no distance has been credited from it
 *     yet, a fix at least [reAnchorRatio] (3) × better that lands inside the anchor's accuracy radius replaces
 *     the anchor: the poor anchor was probably scattered, not the walker. The receiver's Doppler speed is still
 *     believed (only the position is doubted): the integral of rule 4 since the anchor is credited, capped the
 *     same way (and only while the chain is intact and time has passed), so a stationary receiver credits
 *     nothing and a walker keeps the metres it walked. The rule is limited to an uncredited anchor because once
 *     a hop has been credited the anchor is just the last accepted fix of a run in progress, and replacing it
 *     drops the fix interval since it — real movement — every time a better fix happens to land inside its
 *     radius. A phone's accuracy steps between bands every few seconds (a 25 m run with every 10th fix at 8 m,
 *     or 30 / 9 m alternating), and the unrestricted rule lost 10–50 % of such runs; a poor-but-credited anchor
 *     is instead kept and the jitter radius of rule 2 (or the Doppler credit of rule 4) measures on from it;
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
 *     max(reported speed, [plausibleSpeedMps], the speed of the last accepted fix) × dt + both accuracy radii —
 *     a multipath jump 25 m off the path between two 1-second fixes fails both, while a real 25 m of slow walking
 *     over 10 s passes. The last accepted speed is in the bound because without it a receiver with no Doppler
 *     speed deadlocked at running/cycling pace: a 6 m/s hop at 5 m accuracy is 12 m over 2 s against an
 *     allowance of 2.5 × 2 + 10 = 15 m, so position noise rejects one hop in seven, and after a rejection dt
 *     grows while the allowance grows by 2.5 m per second and the rider by 6 — every later fix was a "spike"
 *     (HEAD: 851 of 7194 m). A speed the tracker has itself measured is plausible for the next hop. The anchor
 *     stays put on a spike, so a deadlock is still possible in principle (a genuine outage of the spike test);
 *     the escape hatch is that spikes cannot go on for ever: when fixes have been rejected as spikes for more
 *     than [spikeEscapeS] (10 s) since the first of them, or [spikeEscapeRun] (3) consecutive spikes were each
 *     at most [maxSpeedMps] from the *previous raw fix* (the fixes agree with each other, only the anchor is
 *     stale), the current fix becomes the anchor with no hop credited — the receiver's own integral (rule 4,
 *     capped) is kept, nothing else — so a lost segment costs that segment instead of the rest of the workout;
 *  4. distance is the sum over accepted fixes of the movement since the previous accepted fix. When the
 *     receiver reports a Doppler speed of at least [stationarySpeedMps] at the accepted fix, that movement is the
 *     *integral of the reported speeds* over the fixes since the previous accepted fix — every fix that passed
 *     the accuracy gate contributes its own speed × its own dt, including the ones rule 2 rejected (their speed
 *     is still the receiver's, only their position was doubted), but *not* the ones rule 3 rejected as spikes
 *     (a fix whose position is impossible is not believed about its speed either: the next credible fix's dt
 *     spans it) — capped at the position hop plus the accuracy radius, so a bogus speed cannot run away from the
 *     position. Doppler speed is far less noisy than the difference of two 1 Hz positions, whose per-fix scatter
 *     would otherwise be integrated straight into the distance (with 6 m accuracy and 2 m of independent noise
 *     per fix, +48 % over a 20-minute run). The credit used to be the accepted fix's speed × the whole interval,
 *     which on the first real walk turned 33 s of 0–0.5 m/s fixes into 1.17 m/s × 33 s. The integral is only
 *     used while consecutive fixes are at most [dopplerMaxDtS] (5 s) apart; across a larger gap — no receiver
 *     speed, or a GPS outage — the movement is the haversine hop, so an outage is bridged by the straight line
 *     and a speed-less receiver still accumulates in hops (rule 2);
 *  4b. the part of that integral made of sub-threshold speeds (below [stationarySpeedMps]) is doubtful — it is
 *     real slow walking on a walk and it is nothing on a receiver that says 0.7 m/s while its owner stands
 *     still — so it is believed only as far as the position confirms it: when the hop since the integral's
 *     start is at least as long as the sub-threshold part, the whole integral is credited; otherwise the
 *     integral is scaled by hop / sub-threshold part (never below the above-threshold part, which is trusted).
 *     That keeps the credit within the above-threshold part + the hop. On the real walk the slow stretches
 *     advanced the position and are kept; a "0.7 m/s while standing" receiver, whose 42 m claim per 60 s stop
 *     is met by a hop of a few metres, credits about the hop — what the same stop costs with the receiver
 *     reporting 0 — instead of one accuracy radius per stop, which the plain cap allowed (a 1.2 m/s walk with
 *     ten 60 s stops at 25 m accuracy came out 33 % long, 53 % at 50 m);
 *  5. pace comes from a rolling window that spans at least the last [paceWindowMs] (30 s) or the last
 *     [paceWindowM] (100 m), whichever is reached first, never from two consecutive fixes;
 *  6. speed is the receiver's Doppler speed of the last accepted fix, or credited distance / time since the
 *     previous accepted fix when the receiver reports none (in every branch that anchors, rule 1b/1c included).
 *
 * Not thread-safe: the caller serialises [addFix] (the controller does).
 */
class ZzWipTracker(
    private val maxAccuracyM: Float = 60f,
    private val minMoveM: Float = 3f,
    private val stationarySpeedMps: Float = 1.0f,
    private val paceWindowMs: Long = 30_000L,
    private val paceWindowM: Double = 100.0,
    private val maxSpeedMps: Double = 12.0,
    private val plausibleSpeedMps: Double = 2.5,
    private val jitterRadiusFactor: Float = 1.5f,
    private val dopplerMaxDtS: Double = 5.0,
    private val firstFixAccuracyM: Float = 20f,
    private val firstFixWaitS: Double = 15.0,
    private val reAnchorRatio: Float = 3f,
    private val spikeEscapeS: Double = 10.0,
    private val spikeEscapeRun: Int = 3,
) : GpsDistanceTracker {

    private class Sample(val time: Long, val cumulativeM: Double)

    private val window = ArrayDeque<Sample>()

    private var hasAnchor = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorTime = 0L
    private var anchorAccuracyM = 0f

    // rule 1b: the best fix seen while waiting for one good enough to anchor on
    private var hasCandidate = false
    private var candidateLat = 0.0
    private var candidateLon = 0.0
    private var candidateTime = 0L
    private var candidateAccuracyM = 0f
    private var waitStartTime = 0L

    // rule 1c: true while the anchor is a starting anchor no hop has been credited from yet
    private var anchorUncredited = false

    // rule 4: Doppler integral over the fixes since the integral's start — the anchor, or the first fix held by
    // rule 1b (the anchor and the start differ only between an expired wait and the next accepted fix)
    private var integralStartLat = 0.0
    private var integralStartLon = 0.0
    private var integralStartTime = 0L
    private var integralStartAccuracyM = 0f
    private var dopplerIntegralM = 0.0
    private var movingIntegralM = 0.0          // rule 4b: the part at speeds ≥ stationarySpeedMps
    private var dopplerChainIntact = true
    private var lastFixTime = 0L               // the last fix folded into the integral

    // rule 3: the last accepted speed (plausibility bound) and the escape hatch
    private var lastAcceptedSpeedMps = 0.0
    private var firstSpikeTime = 0L            // 0 = no spike since the last accepted fix
    private var slowSpikeRun = 0
    private var hasPrevRaw = false
    private var prevRawTime = 0L
    private var prevRawLat = 0.0
    private var prevRawLon = 0.0

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
    /** Spikes that became the anchor instead — the escape hatch of rule 3 and the expiry of rule 1b. */
    var escapedSpikeCount: Int = 0
        private set
    /** Fixes held back while waiting for a first anchor of ≤ [firstFixAccuracyM] (rule 1b). */
    var deferredFirstFixCount: Int = 0
        private set
    /** Poor starting anchors replaced by a much better fix inside their radius (rule 1c). */
    var reAnchoredCount: Int = 0
        private set
    var lastAccuracyM: Float? = null
        private set

    override fun addFix(time: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float): Boolean {
        lastAccuracyM = accuracyM
        if (accuracyM.isNaN() || accuracyM < 0f || accuracyM > maxAccuracyM || lat.isNaN() || lon.isNaN()) {
            rejectedAccuracyCount++
            return false
        }
        val accepted = judge(time, lat, lon, accuracyM, max(0f, speedMps).toDouble())
        if (!hasPrevRaw || time >= prevRawTime) {
            hasPrevRaw = true
            prevRawTime = time
            prevRawLat = lat
            prevRawLon = lon
        }
        return accepted
    }

    private fun judge(time: Long, lat: Double, lon: Double, accuracyM: Float, reported: Double): Boolean {
        // true when this fix ended the rule 1b wait with the candidate as the anchor: it has been folded into the
        // integral already, and rule 3 must not reject it
        var expiry = false
        if (!hasAnchor) {
            if (!hasCandidate) {
                if (accuracyM <= firstFixAccuracyM) {
                    anchorAt(time, lat, lon, accuracyM, reported, credited = false)
                    return true
                }
                // rule 1b: too poor to anchor on; hold it, start the wait and the Doppler integral from it
                startWait(time, lat, lon, accuracyM)
                deferredFirstFixCount++
                this.speedMps = reported
                return false
            }
            if (time < lastFixTime) {
                rejectedJitterCount++
                return false
            }
            fold(time, reported)
            val waiting = (time - waitStartTime) / 1000.0 < firstFixWaitS
            if (accuracyM <= firstFixAccuracyM || (!waiting && accuracyM < candidateAccuracyM)) {
                // the wait ends on this fix — a decent one, or the best seen once the wait is over. The movement
                // since the first held fix was real, only its start was doubted: credit the capped integral
                val moved = dopplerCredit(time, lat, lon, accuracyM)
                distanceMeters += moved
                anchorAt(time, lat, lon, accuracyM, speedOf(reported, moved, (time - integralStartTime) / 1000.0), credited = moved > 0.0)
                return true
            }
            if (waiting) {
                if (accuracyM < candidateAccuracyM) setCandidate(time, lat, lon, accuracyM)
                deferredFirstFixCount++
                this.speedMps = reported
                return false
            }
            // the wait is over and the candidate is the best fix seen: it becomes the (uncredited) starting anchor
            // and this fix is judged against it below, with the integral since the first held fix still open
            hasAnchor = true
            hasCandidate = false
            anchorLat = candidateLat
            anchorLon = candidateLon
            anchorTime = candidateTime
            anchorAccuracyM = candidateAccuracyM
            anchorUncredited = true
            expiry = true
        } else if (time < anchorTime) {
            // out-of-order fix; nothing sensible to do with it
            rejectedJitterCount++
            return false
        }
        val d = haversineMeters(anchorLat, anchorLon, lat, lon)
        val dtS = (time - anchorTime) / 1000.0
        if (anchorUncredited && anchorAccuracyM > firstFixAccuracyM &&
            accuracyM * reAnchorRatio <= anchorAccuracyM && d <= anchorAccuracyM
        ) {
            // rule 1c: a much better fix inside a poor *starting* anchor's radius replaces it. The anchor's position
            // is not trusted, the receiver's speed still is: credit the Doppler integral (rule 4) if it says we moved.
            reAnchoredCount++
            if (!expiry) fold(time, reported)
            val moved = dopplerCredit(time, lat, lon, accuracyM)
            distanceMeters += moved
            anchorAt(time, lat, lon, accuracyM, speedOf(reported, moved, dtS), credited = moved > 0.0)
            return true
        }
        val threshold = max(jitterRadiusFactor * max(accuracyM, anchorAccuracyM), minMoveM).toDouble()
        if (reported < stationarySpeedMps && d < threshold) {
            if (!expiry) fold(time, reported)
            rejectedJitterCount++
            firstSpikeTime = 0L
            slowSpikeRun = 0
            this.speedMps = 0.0
            appendWindow(time, distanceMeters)     // "no movement at this time" keeps the pace window honest
            recomputePace()
            return false
        }
        val spike = if (dtS > 0.0) {
            val allowed = max(reported, max(plausibleSpeedMps, lastAcceptedSpeedMps)) * dtS + accuracyM + anchorAccuracyM
            d / dtS > maxSpeedMps || d > allowed
        } else {
            d > accuracyM + anchorAccuracyM
        }
        if (spike) {
            // A spike: the position is not trustworthy, keep the anchor and wait for the next fix. Its speed is not
            // folded into the integral either (rule 4) and the chain timing is kept, so the next credible fix spans it.
            rejectedSpikeCount++
            if (firstSpikeTime == 0L) firstSpikeTime = time
            val rawImplied = if (hasPrevRaw && time > prevRawTime) {
                haversineMeters(prevRawLat, prevRawLon, lat, lon) / ((time - prevRawTime) / 1000.0)
            } else Double.MAX_VALUE
            slowSpikeRun = if (rawImplied <= maxSpeedMps) slowSpikeRun + 1 else 0
            val escape = expiry || (time - firstSpikeTime) / 1000.0 > spikeEscapeS || slowSpikeRun >= spikeEscapeRun
            if (!escape) return false
            // the escape hatch (rule 3) / the expiry of rule 1b: anchor here without crediting the hop — only the
            // receiver's own integral up to the last credible fix, capped
            escapedSpikeCount++
            val moved = dopplerCredit(time, lat, lon, accuracyM)
            distanceMeters += moved
            anchorAt(time, lat, lon, accuracyM, speedOf(reported, moved, dtS), credited = moved > 0.0)
            return true
        }
        if (!expiry) fold(time, reported)
        val moved = if (reported >= stationarySpeedMps && dtS > 0.0 && dopplerChainIntact) {
            dopplerCredit(time, lat, lon, accuracyM)
        } else d
        distanceMeters += moved
        anchorAt(time, lat, lon, accuracyM, speedOf(reported, d, dtS), credited = true)
        return true
    }

    /** Rule 6: the receiver's speed, else the credited movement over the interval. */
    private fun speedOf(reported: Double, moved: Double, dtS: Double): Double = when {
        reported > 0.0 -> reported
        dtS > 0.0 -> moved / dtS
        else -> 0.0
    }

    /**
     * Rules 4 and 4b: the Doppler integral since its start, capped at the hop from that start + the larger accuracy
     * (a bogus speed cannot run away from the position) and at the above-threshold part + the hop (sub-threshold
     * speeds only confirm movement the position shows). Nothing across a broken chain or without elapsed time.
     */
    private fun dopplerCredit(time: Long, lat: Double, lon: Double, accuracyM: Float): Double {
        if (!dopplerChainIntact || time <= integralStartTime || dopplerIntegralM <= 0.0) return 0.0
        val hop = haversineMeters(integralStartLat, integralStartLon, lat, lon)
        val doubtful = dopplerIntegralM - movingIntegralM
        val believed = if (doubtful <= 0.0) dopplerIntegralM else max(movingIntegralM, dopplerIntegralM * min(1.0, hop / doubtful))
        return min(believed, hop + max(accuracyM, integralStartAccuracyM))
    }

    /**
     * Make this fix the anchor (accepted), restarting the Doppler integral from it. [credited] says whether a hop
     * was credited from the previous anchor to this fix; a starting anchor (nothing credited yet) is the only kind
     * rule 1c may replace.
     */
    private fun anchorAt(time: Long, lat: Double, lon: Double, accuracyM: Float, speed: Double, credited: Boolean) {
        hasAnchor = true
        hasCandidate = false
        anchorLat = lat
        anchorLon = lon
        anchorTime = time
        anchorAccuracyM = accuracyM
        anchorUncredited = !credited
        startIntegral(time, lat, lon, accuracyM)
        lastAcceptedSpeedMps = min(speed, maxSpeedMps)
        firstSpikeTime = 0L
        slowSpikeRun = 0
        acceptedCount++
        this.speedMps = speed
        appendWindow(time, distanceMeters)
        recomputePace()
    }

    /** Rule 1b: hold the first poor fix as the candidate; the wait and the Doppler integral start here. */
    private fun startWait(time: Long, lat: Double, lon: Double, accuracyM: Float) {
        setCandidate(time, lat, lon, accuracyM)
        waitStartTime = time
        startIntegral(time, lat, lon, accuracyM)
    }

    /** Rule 1b: a better fix mid-wait replaces the candidate; the integral since the first held fix runs on. */
    private fun setCandidate(time: Long, lat: Double, lon: Double, accuracyM: Float) {
        hasCandidate = true
        candidateLat = lat
        candidateLon = lon
        candidateTime = time
        candidateAccuracyM = accuracyM
    }

    private fun startIntegral(time: Long, lat: Double, lon: Double, accuracyM: Float) {
        integralStartLat = lat
        integralStartLon = lon
        integralStartTime = time
        integralStartAccuracyM = accuracyM
        dopplerIntegralM = 0.0
        movingIntegralM = 0.0
        dopplerChainIntact = true
        lastFixTime = time
    }

    /** Rule 4: fold this fix's speed × its own dt into the integral; a gap over [dopplerMaxDtS] breaks the chain. */
    private fun fold(time: Long, reported: Double) {
        val dtS = (time - lastFixTime) / 1000.0
        if (dtS <= 0.0) return
        if (dtS > dopplerMaxDtS) dopplerChainIntact = false
        dopplerIntegralM += reported * dtS
        if (reported >= stationarySpeedMps) movingIntegralM += reported * dtS
        lastFixTime = time
    }

    override fun reset() {
        window.clear()
        clearAnchor()
        distanceMeters = 0.0
        paceSecPerKm = 0.0
        speedMps = 0.0
        acceptedCount = 0
        rejectedAccuracyCount = 0
        rejectedJitterCount = 0
        rejectedSpikeCount = 0
        escapedSpikeCount = 0
        deferredFirstFixCount = 0
        reAnchoredCount = 0
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
        clearAnchor()
        paceSecPerKm = 0.0
        speedMps = 0.0
    }

    private fun clearAnchor() {
        hasAnchor = false
        anchorLat = 0.0
        anchorLon = 0.0
        anchorTime = 0L
        anchorAccuracyM = 0f
        anchorUncredited = false
        hasCandidate = false
        candidateLat = 0.0
        candidateLon = 0.0
        candidateTime = 0L
        candidateAccuracyM = 0f
        waitStartTime = 0L
        startIntegral(0L, 0.0, 0.0, 0f)
        lastAcceptedSpeedMps = 0.0
        firstSpikeTime = 0L
        slowSpikeRun = 0
        hasPrevRaw = false
        prevRawTime = 0L
        prevRawLat = 0.0
        prevRawLon = 0.0
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
