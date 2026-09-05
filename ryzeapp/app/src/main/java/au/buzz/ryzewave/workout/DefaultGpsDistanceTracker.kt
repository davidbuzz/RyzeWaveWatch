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
 *     than a stride estimate and are still dropped. A fix with the same timestamp as the last accepted (or held)
 *     fix is dropped too: there is no interval to measure, and its scatter would be credited as a hop;
 *  1b. the first anchor must be a decent fix: while nothing has been accepted yet, a fix worse than
 *     [firstFixAccuracyM] (20 m) is held as a candidate rather than anchored on (the caller sees it as rejected),
 *     and the tracker keeps waiting for a fix of ≤ 20 m. If none arrives within [firstFixWaitS] (15 s) of the
 *     first held fix, the wait expires on the best fix held *before* the current one and the current fix is judged
 *     from it like any other (jitter, spike or accepted). The first real walk anchored on a 52 m fix about 45 m
 *     off the loop the walker made and credited the hop back to the loop as distance; with this rule that fix is
 *     skipped and the 17 m fix 9 s later starts the walk. The movement *during* the wait is real — only where it
 *     started is doubted — so it is credited on every exit from the wait (a ≤ 20 m fix arriving, the expiry, a
 *     [markGap] while the wait is still open) from what the receiver measured:
 *      – with Doppler speeds, the integral of rule 4 runs from the first held fix (a better candidate mid-wait does
 *        not restart it) and is credited, capped as in rules 4 and 4b, at the exit fix, at the first fix accepted
 *        from the candidate after expiry, or at the last held fix on a [markGap]. Nothing is credited across a
 *        broken chain (held fixes more than [dopplerMaxDtS] apart);
 *      – without Doppler (every held fix reported no speed at all) there is no integral, and the movement is the
 *        straight-line hop from the first held fix to the exit fix / the candidate / the last held fix, credited
 *        when it is beyond the jitter radius of rule 2 for the pair (below it the hop is scatter, not a walk) and
 *        capped by the length of the raw chain through the held fixes up to that fix and by [maxSpeedMps] × the
 *        wait — nothing across an inconsistent chain (a hop of it faster than [chainNoiseFactor] × [maxSpeedMps]).
 *        A hop inside the jitter radius is not lost either: unless the exit fix / candidate is [reAnchorRatio] ×
 *        better than the first held fix (the 52 m-versus-17 m case: the poor fix's position is not trusted and the
 *        hop is its scatter), the *first held fix* anchors instead, so the wait's movement is inside the first hop
 *        measured from it, exactly as if it had anchored at once. Without this a speed-less receiver lost the wait
 *        at every start and every resume (−1.1 % per 19 m fix 14 s into a 25 m start, −4.7 % over a run with a pause
 *        every 5 minutes) and a pause cadence of ≤ 15 s in 25 m accuracy recorded nothing. At expiry the
 *        plausibility bound of rule 3 is seeded with the wait's *raw-chain speed* — the path through every held fix
 *        over the wait's duration, when the chain is consistent — so a 6 m/s rider 90 m from the candidate after
 *        15 s is accepted as the 90 m hop (2.5 × 15 + two radii would have called it a spike, and every later fix
 *        was farther still: 0.0 m after 20 minutes). A fix that is still a spike from the candidate at expiry is
 *        rejected like any spike unless the wait's chain is consistent and the fix is at most [maxSpeedMps] from
 *        the previous raw fix, in which case the rider simply outran the bound and the fix anchors with the chain
 *        credit of rule 3's escape (speed-less receivers only, see rule 3);
 *  1c. while the anchor is a *starting* anchor worse than [firstFixAccuracyM] — the first fix of the walk, the
 *     anchor of an expired wait, or the re-anchor after [markGap] — and no distance has been credited from it
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
 *     noise² / hop length). A fix whose 0 is a Doppler dropout (rule 4c) is judged by its raw 0 here: it stays
 *     inside the radius until the position has clearly moved, and its bridged seconds are folded meanwhile;
 *  3. a fix is also rejected as a spike when the distance it implies is not plausible for the time elapsed:
 *     the implied speed exceeds [maxSpeedMps] (12 m/s, faster than any runner), or the jump is larger than
 *     max(reported speed, [plausibleSpeedMps], the Doppler speed of the last accepted fix) × dt + both accuracy
 *     radii — a multipath jump 25 m off the path between two 1-second fixes fails both, while a real 25 m of slow
 *     walking over 10 s passes. The last accepted speed is in the bound only when the receiver reported it: a
 *     speed the tracker derived from a hop is noisy and self-reinforcing (each admitted hop widened the next
 *     bound, +0.9–1.6 % over the plain bound on the no-Doppler runs, and bursts of multipath fixes were let in as
 *     hops), so in hop mode the bound is the plain max(reported, 2.5 m/s) × dt + both radii. The anchor stays
 *     put on a spike, so a receiver without Doppler that is *faster* than the bound deadlocks in principle: a
 *     6 m/s hop at 5 m accuracy is 12 m over 2 s against an allowance of 2.5 × 2 + 10 = 15 m, position noise
 *     rejects one hop in seven, and after a rejection dt grows while the allowance grows by 2.5 m per second and
 *     the rider by 6 — every later fix was a "spike" (build 8: 851 of 7194 m). The escape hatch is that spikes
 *     cannot go on for ever: when fixes have been rejected as spikes for more than [spikeEscapeS] (30 s) since the
 *     first of them, the current fix becomes the anchor crediting the receiver's own integral (rule 4, capped) —
 *     losing that segment instead of everything. A *speed-less* receiver has a second escape: [spikeEscapeRun]
 *     (3) consecutive spikes each at most [maxSpeedMps] from the *previous raw fix* while the *raw chain* since
 *     the anchor is consistent (no hop between consecutive raw fixes exceeded [chainNoiseFactor] × [maxSpeedMps]:
 *     the fixes agree with each other, only the anchor is stale) re-anchor on the current fix crediting the raw
 *     chain's length since the anchor (capped at [maxSpeedMps] × its duration) — the path the fixes themselves
 *     drew, which for a rider without Doppler is the only measurement there is. That escape is *not* used while
 *     the receiver reports Doppler speeds: its bound already carries the last reported speed, so it never
 *     deadlocks, and a multipath excursion that ramps away at 13–18 m/s (below the chain-noise allowance) and
 *     drifts on at ≤ 12 m/s satisfies "three agreeing spikes on a consistent chain" too — re-anchoring on it
 *     credited the excursion's chain against the receiver's honest 3 m/s (+10 % on a run and +51 % on a walk
 *     with one such excursion a minute). With Doppler the excursion is ridden out as spikes and the first fix
 *     back on the path is accepted from the old anchor with the integral (bursts entered faster than 18 m/s are
 *     ridden out by both kinds of receiver: the hop into them breaks the chain). The 30 s (was 10 s) lets a burst
 *     of up to ~25 s pass the same way. When the time limit does fire for a receiver with Doppler — a cyclist at
 *     the 12 m/s cap whose noisy hops are all "spikes" — it credits the last credible speed over the interval,
 *     capped by the hop, on top of the integral;
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
 *     and a speed-less receiver still accumulates in hops (rule 2). A reported speed of NaN counts as 0;
 *  4b. the part of that integral made of sub-threshold speeds (below [stationarySpeedMps]) is doubtful — it is
 *     real slow walking on a walk and it is nothing on a receiver that says 0.7 m/s while its owner stands
 *     still — so it is believed only as far as the position confirms it: when the hop since the integral's
 *     start is at least as long as the sub-threshold part, the whole integral is credited; otherwise the
 *     integral is scaled by hop / sub-threshold part (never below the above-threshold part, which is trusted).
 *     That keeps the credit within the above-threshold part + the hop. On the real walk the slow stretches
 *     advanced the position and are kept; a "0.7 m/s while standing" receiver, whose 42 m claim per 60 s stop
 *     is met by a hop of a few metres, credits about the hop — what the same stop costs with the receiver
 *     reporting 0 — instead of one accuracy radius per stop, which the plain cap allowed (a 1.2 m/s walk with
 *     ten 60 s stops at 25 m accuracy came out 33 % long, 53 % at 50 m). The price is paid by a receiver that
 *     under-reports: a 1.2 m/s walker reported as 0.99 m/s with 1.2 every third fix is believed (−16 % at
 *     10–35 m accuracy, where the baseline's hop mode happened to be exact) — accepted, because the same rule
 *     takes symmetric speed noise from +22 % to −1 %;
 *  4c. a reported speed of exactly 0 after the receiver has been reporting Doppler speeds is "no speed", not
 *     "standing still": WorkoutService sends 0 when `Location.hasSpeed()` is false, which happens for a second or
 *     ten under trees, exactly where the accuracy is poor. While the last fix folded reported at least
 *     [stationarySpeedMps] (raw or itself bridged), such a fix is folded at that last speed instead of 0 and is
 *     judged, bounded and reported with it; seconds folded at 0 before the receiver has reported any speed (a
 *     dropout at the very start, or during a wait) are filled in by the first speed it reports. The *bridged* part
 *     of the integral is believed only when the position is consistent with the whole claim — the hop since the
 *     integral's start, plus an allowance of half the claim (capped at the larger accuracy radius, never below
 *     3 × [minMoveM]), reaches the integral — and is dropped entirely otherwise: a walker who stops for 20 s while the
 *     receiver keeps saying 0 claims 25 m against a hop of a few metres, nothing of it is credited, and the walk
 *     stays exact (the plain accuracy allowance let a 20 s stop in 25 m credit its whole claim, +31 %). A fix
 *     accepted on its position while its bridge is contradicted credits its hop, like any speed-less fix.
 *     Sub-threshold speeds (0.3 m/s) are not bridged: they are the receiver's own measurement (rule 4b). Without
 *     this a 3 m/s run whose receiver dropped its speed for 1 s every 20 s was −4.9 %, for 10 s every 60 s
 *     −15.5 %, for 30 % of its fixes −30 %;
 *  5. pace comes from a rolling window that spans at least the last [paceWindowMs] (30 s) or the last
 *     [paceWindowM] (100 m), whichever is reached first, never from two consecutive fixes;
 *  6. speed is the receiver's Doppler speed of the last accepted fix (the bridged one for a dropout), or credited
 *     distance / time since the previous accepted fix when the receiver reports none (in every branch that
 *     anchors, rule 1b/1c included).
 *
 * The controller calls [markGap] at a pause and at the stop, so a wait still open at that moment is credited too.
 * Not thread-safe: the caller serialises [addFix] (the controller does).
 */
class DefaultGpsDistanceTracker(
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
    private val spikeEscapeS: Double = 30.0,
    private val spikeEscapeRun: Int = 3,
    private val chainNoiseFactor: Double = 1.5,
) : GpsDistanceTracker {

    private class Sample(val time: Long, val cumulativeM: Double)

    private val window = ArrayDeque<Sample>()

    private var hasAnchor = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorTime = 0L
    private var anchorAccuracyM = 0f

    // rule 1b: the best fix seen while waiting for one good enough to anchor on, and the raw chain up to it
    private var hasCandidate = false
    private var candidateLat = 0.0
    private var candidateLon = 0.0
    private var candidateTime = 0L
    private var candidateAccuracyM = 0f
    private var candidateChainM = 0.0
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
    private var movingIntegralM = 0.0          // rule 4b: the part at reported speeds ≥ stationarySpeedMps
    private var bridgedIntegralM = 0.0         // rule 4c: the part folded at the last speed across dropouts
    private var dopplerChainIntact = true
    private var lastFixTime = 0L               // the last fix folded into the integral, and where markGap credits an open wait
    private var lastFixLat = 0.0
    private var lastFixLon = 0.0
    private var lastFixAccuracyM = 0f
    private var lastDopplerMps = 0.0           // rule 4c: the speed of the last fix folded (or anchored), bridged or raw; 0 = none
    private var unknownDtS = 0.0               // rule 4c: seconds folded at 0 before any speed was reported; the first speed fills them in

    // rule 3: the plausibility bound's speed (the last accepted fix's Doppler speed, or the wait's chain speed at
    // expiry), the escape hatch, and the raw chain — every fix past the accuracy gate since the anchor (or the
    // wait's first fix), accepted or not
    private var boundSpeedMps = 0.0
    private var firstSpikeTime = 0L            // 0 = no spike since the last accepted fix
    private var slowSpikeRun = 0
    private var hasPrevRaw = false
    private var prevRawTime = 0L
    private var prevRawLat = 0.0
    private var prevRawLon = 0.0
    private var rawChainStartTime = 0L
    private var rawChainM = 0.0
    private var rawChainConsistent = true

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
    /** Spikes that became the anchor instead — the escape hatch of rule 3, also at the expiry of rule 1b. */
    var escapedSpikeCount: Int = 0
        private set
    /** Fixes held back while waiting for a first anchor of ≤ [firstFixAccuracyM] (rule 1b). */
    var deferredFirstFixCount: Int = 0
        private set
    /** Poor starting anchors replaced by a much better fix inside their radius (rule 1c). */
    var reAnchoredCount: Int = 0
        private set
    /** Fixes whose reported 0 was a Doppler dropout and was bridged with the last speed (rule 4c). */
    var bridgedSpeedCount: Int = 0
        private set
    var lastAccuracyM: Float? = null
        private set

    override fun addFix(time: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float): Boolean {
        lastAccuracyM = accuracyM
        if (accuracyM.isNaN() || accuracyM < 0f || accuracyM > maxAccuracyM || lat.isNaN() || lon.isNaN()) {
            rejectedAccuracyCount++
            return false
        }
        val reported = if (speedMps.isNaN()) 0.0 else max(0f, speedMps).toDouble()
        // rule 4c: an exact 0 while the receiver has been reporting speeds is a dropout, not a stop
        val bridged = reported == 0.0 && lastDopplerMps >= stationarySpeedMps
        val speed = if (bridged) lastDopplerMps else reported
        // the raw chain (rule 3): the hop from the previous fix past the gate, whatever became of that fix
        var rawImplied = Double.MAX_VALUE
        if (hasPrevRaw && time > prevRawTime) {
            val hop = haversineMeters(prevRawLat, prevRawLon, lat, lon)
            rawImplied = hop / ((time - prevRawTime) / 1000.0)
            rawChainM += hop
            if (rawImplied > chainNoiseFactor * maxSpeedMps) rawChainConsistent = false
        }
        val accepted = judge(time, lat, lon, accuracyM, speed, bridged, rawImplied)
        if (!hasPrevRaw || time >= prevRawTime) {
            hasPrevRaw = true
            prevRawTime = time
            prevRawLat = lat
            prevRawLon = lon
        }
        return accepted
    }

    /** [speed] is the receiver's speed, or the last one when this fix's 0 was [bridged] (rule 4c). */
    private fun judge(time: Long, lat: Double, lon: Double, accuracyM: Float, speed: Double, bridged: Boolean, rawImplied: Double): Boolean {
        // true when this fix ended the rule 1b wait with a held fix as the anchor and is judged from it
        var expiry = false
        if (!hasAnchor) {
            if (!hasCandidate) {
                if (accuracyM <= firstFixAccuracyM) {
                    anchorAt(time, lat, lon, accuracyM, speed, speed, credited = false)
                    return true
                }
                // rule 1b: too poor to anchor on; hold it, start the wait, the Doppler integral and the raw chain from it
                startWait(time, lat, lon, accuracyM, speed)
                deferredFirstFixCount++
                this.speedMps = speed
                return false
            }
            if (time <= lastFixTime) {
                rejectedJitterCount++
                return false
            }
            val waitS = (time - waitStartTime) / 1000.0
            if (accuracyM <= firstFixAccuracyM) {
                // a decent fix ends the wait. The movement since the first held fix was real, only its start was
                // doubted: credit what the receiver measured and anchor here
                if (bridged) bridgedSpeedCount++
                fold(time, lat, lon, accuracyM, speed, bridged)
                if (dopplerIntegralM > 0.0) {
                    val moved = dopplerCredit(time, lat, lon, accuracyM)
                    distanceMeters += moved
                    anchorAt(time, lat, lon, accuracyM, speed, speedOf(speed, moved, waitS), credited = moved > 0.0)
                    return true
                }
                // no receiver speed at all: the hop from the first held fix, when it is beyond the noise
                val moved = waitHopCredit(time, lat, lon, accuracyM, rawChainM)
                if (moved > 0.0 || accuracyM * reAnchorRatio <= integralStartAccuracyM) {
                    distanceMeters += moved
                    anchorAt(time, lat, lon, accuracyM, speed, speedOf(speed, moved, waitS), credited = moved > 0.0)
                    return true
                }
                // the hop is scatter and this fix is not much better than the first held one: that fix anchors,
                // as if it had at once, and this fix is judged from it below (inside its radius: jitter)
                anchorOnHeldFix(integralStartLat, integralStartLon, integralStartTime, integralStartAccuracyM, waitS)
                expiry = true
            } else if (waitS < firstFixWaitS) {
                if (bridged) bridgedSpeedCount++
                fold(time, lat, lon, accuracyM, speed, bridged)
                if (accuracyM < candidateAccuracyM) setCandidate(time, lat, lon, accuracyM)
                deferredFirstFixCount++
                this.speedMps = speed
                return false
            } else {
                // the wait is over: a fix held before this one becomes the starting anchor and this fix is judged
                // against it below, with the integral since the first held fix still open. The raw chain over the
                // wait seeds the plausibility bound: a speed-less rider 90 m from the candidate after 15 s is no
                // spike when the held fixes walked those 90 m
                if (dopplerIntegralM > 0.0 || candidateTime == integralStartTime) {
                    anchorOnHeldFix(candidateLat, candidateLon, candidateTime, candidateAccuracyM, waitS)
                } else {
                    // no receiver speed: the hop from the first held fix to the candidate is credited when it is
                    // beyond the noise (the candidate anchors, credited), skipped when the candidate is much better
                    // (its scatter), and otherwise kept inside the first hop by anchoring on the first held fix
                    val moved = waitHopCredit(candidateTime, candidateLat, candidateLon, candidateAccuracyM, candidateChainM)
                    if (moved > 0.0 || candidateAccuracyM * reAnchorRatio <= integralStartAccuracyM) {
                        distanceMeters += moved
                        anchorOnHeldFix(candidateLat, candidateLon, candidateTime, candidateAccuracyM, waitS)
                        anchorUncredited = moved == 0.0
                        // the chain up to the candidate is done with: rule 3's escape measures on from it
                        rawChainM -= candidateChainM
                        rawChainStartTime = candidateTime
                    } else {
                        anchorOnHeldFix(integralStartLat, integralStartLon, integralStartTime, integralStartAccuracyM, waitS)
                    }
                }
                expiry = true
            }
        } else if (time <= anchorTime) {
            // out-of-order or duplicated fix; nothing sensible to do with it
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
            if (bridged) bridgedSpeedCount++
            fold(time, lat, lon, accuracyM, speed, bridged)
            val moved = dopplerCredit(time, lat, lon, accuracyM)
            distanceMeters += moved
            anchorAt(time, lat, lon, accuracyM, speed, speedOf(speed, moved, dtS), credited = moved > 0.0)
            return true
        }
        val threshold = max(jitterRadiusFactor * max(accuracyM, anchorAccuracyM), minMoveM).toDouble()
        val reportedRaw = if (bridged) 0.0 else speed
        if (reportedRaw < stationarySpeedMps && d < threshold) {
            if (bridged) bridgedSpeedCount++
            fold(time, lat, lon, accuracyM, speed, bridged)
            rejectedJitterCount++
            firstSpikeTime = 0L
            slowSpikeRun = 0
            this.speedMps = 0.0
            appendWindow(time, distanceMeters)     // "no movement at this time" keeps the pace window honest
            recomputePace()
            return false
        }
        val spike = if (dtS > 0.0) {
            val allowed = max(speed, max(plausibleSpeedMps, boundSpeedMps)) * dtS + accuracyM + anchorAccuracyM
            d / dtS > maxSpeedMps || d > allowed
        } else {
            d > accuracyM + anchorAccuracyM
        }
        if (spike) {
            // A spike: the position is not trustworthy, keep the anchor and wait for the next fix. Its speed is not
            // folded into the integral either (rule 4) and the chain timing is kept, so the next credible fix spans it.
            rejectedSpikeCount++
            if (firstSpikeTime == 0L) firstSpikeTime = time
            val agrees = rawImplied <= maxSpeedMps          // plausible from the previous raw fix
            slowSpikeRun = if (agrees) slowSpikeRun + 1 else 0
            // a receiver that reports speeds never deadlocks (its bound carries its last speed), so only the time
            // limit applies to it; the agreeing-spikes escape is for speed-less receivers, whose fixes are the only
            // measurement — with Doppler it re-anchored on multipath excursions ramping away below 18 m/s
            val speedless = lastDopplerMps <= 0.0
            val escape = if (expiry) {
                speedless && rawChainConsistent && agrees
            } else {
                (time - firstSpikeTime) / 1000.0 > spikeEscapeS || (speedless && rawChainConsistent && slowSpikeRun >= spikeEscapeRun)
            }
            if (!escape) return false
            // the escape hatch (rule 3): anchor here crediting the receiver's own integral up to the last credible
            // fix (capped); a speed-less receiver credits the raw chain the fixes drew since the anchor instead, a
            // receiver with Doppler its last credible speed over the interval, capped by the hop (the spikes' own
            // speeds are not believed, rule 4) — never the hop itself
            escapedSpikeCount++
            val integral = dopplerCredit(time, lat, lon, accuracyM)
            val moved = if (speedless) max(integral, chainCredit(time)) else max(integral, min(d, lastDopplerMps * dtS))
            distanceMeters += moved
            anchorAt(time, lat, lon, accuracyM, speed, speedOf(speed, moved, dtS), credited = moved > 0.0)
            return true
        }
        if (bridged) bridgedSpeedCount++
        fold(time, lat, lon, accuracyM, speed, bridged)
        val contradicted = bridged && !bridgeConfirmed(haversineMeters(integralStartLat, integralStartLon, lat, lon), max(accuracyM, integralStartAccuracyM))
        val moved = if (speed >= stationarySpeedMps && dtS > 0.0 && dopplerChainIntact && !contradicted) {
            dopplerCredit(time, lat, lon, accuracyM)
        } else d
        distanceMeters += moved
        anchorAt(time, lat, lon, accuracyM, speed, if (contradicted) speedOf(0.0, d, dtS) else speedOf(speed, d, dtS), credited = true)
        return true
    }

    /** Rule 6: the receiver's speed, else the credited movement over the interval. */
    private fun speedOf(reported: Double, moved: Double, dtS: Double): Double = when {
        reported > 0.0 -> reported
        dtS > 0.0 -> moved / dtS
        else -> 0.0
    }

    /**
     * Rule 4c: the bridged part of the integral is believed only when the position is consistent with the whole
     * claim — the hop since the integral's start, plus an allowance for its noise, reaches the integral. The
     * allowance is half the claim, capped at the larger accuracy radius (rule 4's own cap: a stop the receiver
     * reports as 0 claims twice what the position shows and more), but never below 3 × [minMoveM]: a bridge of a
     * few seconds is inside the scatter of any hop, whatever the accuracy says.
     */
    private fun bridgeConfirmed(hop: Double, maxAcc: Float): Boolean {
        if (bridgedIntegralM <= 0.0) return true
        val allowance = max(3.0 * minMoveM, min(maxAcc.toDouble(), 0.5 * dopplerIntegralM))
        return hop + allowance >= dopplerIntegralM
    }

    /**
     * Rules 4, 4b and 4c: the Doppler integral since its start, capped at the hop from that start + the larger
     * accuracy (a bogus speed cannot run away from the position) and at the above-threshold part + the hop
     * (sub-threshold speeds only confirm movement the position shows), with the bridged part dropped when the
     * position contradicts it. Nothing across a broken chain or without elapsed time.
     */
    private fun dopplerCredit(time: Long, lat: Double, lon: Double, accuracyM: Float): Double {
        if (!dopplerChainIntact || time <= integralStartTime || dopplerIntegralM <= 0.0) return 0.0
        val hop = haversineMeters(integralStartLat, integralStartLon, lat, lon)
        val maxAcc = max(accuracyM, integralStartAccuracyM)
        val bridgedBelieved = if (bridgeConfirmed(hop, maxAcc)) bridgedIntegralM else 0.0
        val trusted = movingIntegralM + bridgedBelieved
        val doubtful = dopplerIntegralM - movingIntegralM - bridgedIntegralM
        val believed = if (doubtful <= 0.0) trusted else max(trusted, (trusted + doubtful) * min(1.0, hop / doubtful))
        return min(believed, hop + maxAcc)
    }

    /**
     * Rule 3's escape: the length of the raw chain since the anchor (the path through every fix past the gate,
     * including this one), when no hop of it was faster than the noise allowance, capped at [maxSpeedMps] × its
     * duration. Nothing for an inconsistent chain: a burst of multipath fixes entered at 40 m/s is not a path.
     */
    private fun chainCredit(time: Long): Double {
        if (!rawChainConsistent || time <= rawChainStartTime) return 0.0
        return min(rawChainM, maxSpeedMps * (time - rawChainStartTime) / 1000.0)
    }

    /**
     * Rule 1b without Doppler: the straight-line hop from the first held fix to the fix at [time] (the exit fix,
     * the candidate, or the last held fix), credited when it is beyond the jitter radius of the pair (rule 2's
     * test, had the first held fix been the anchor) and capped by the raw chain through the held fixes up to that
     * fix ([chainM]) and by [maxSpeedMps] × the wait; nothing across an inconsistent chain. The chain bounds the
     * hop at the wait's own speed: max(2.5 m/s, chain speed) × wait + both radii is never smaller than it.
     */
    private fun waitHopCredit(time: Long, lat: Double, lon: Double, accuracyM: Float, chainM: Double): Double {
        if (!rawChainConsistent || time <= integralStartTime) return 0.0
        val hop = haversineMeters(integralStartLat, integralStartLon, lat, lon)
        val gate = max(jitterRadiusFactor * max(accuracyM, integralStartAccuracyM), minMoveM).toDouble()
        if (hop < gate) return 0.0
        return min(hop, min(chainM, maxSpeedMps * (time - integralStartTime) / 1000.0))
    }

    /**
     * Make this fix the anchor (accepted), restarting the Doppler integral and the raw chain from it. [credited]
     * says whether a hop was credited from the previous anchor to this fix; a starting anchor (nothing credited
     * yet) is the only kind rule 1c may replace. [reported] (the receiver's speed, bridged for a dropout, 0 for
     * none) feeds the rule 3 bound and rule 4c; [speed] is what rule 6 reports.
     */
    private fun anchorAt(time: Long, lat: Double, lon: Double, accuracyM: Float, reported: Double, speed: Double, credited: Boolean) {
        hasAnchor = true
        hasCandidate = false
        anchorLat = lat
        anchorLon = lon
        anchorTime = time
        anchorAccuracyM = accuracyM
        anchorUncredited = !credited
        startIntegral(time, lat, lon, accuracyM)
        startChain(time)
        boundSpeedMps = if (reported > 0.0) min(reported, maxSpeedMps) else 0.0
        lastDopplerMps = reported
        firstSpikeTime = 0L
        slowSpikeRun = 0
        acceptedCount++
        this.speedMps = speed
        appendWindow(time, distanceMeters)
        recomputePace()
    }

    /**
     * Rule 1b at expiry (or a decent fix inside the noise): a fix held during the wait becomes the uncredited
     * starting anchor; the integral and the raw chain run on, and the wait's chain speed seeds the rule 3 bound.
     */
    private fun anchorOnHeldFix(lat: Double, lon: Double, time: Long, accuracyM: Float, waitS: Double) {
        hasAnchor = true
        hasCandidate = false
        anchorLat = lat
        anchorLon = lon
        anchorTime = time
        anchorAccuracyM = accuracyM
        anchorUncredited = true
        boundSpeedMps = if (rawChainConsistent && waitS > 0.0) min(rawChainM / waitS, maxSpeedMps) else 0.0
    }

    /** Rule 1b: hold the first poor fix as the candidate; the wait, the Doppler integral and the raw chain start here. */
    private fun startWait(time: Long, lat: Double, lon: Double, accuracyM: Float, speed: Double) {
        setCandidate(time, lat, lon, accuracyM)
        waitStartTime = time
        startIntegral(time, lat, lon, accuracyM)
        startChain(time)
        lastDopplerMps = speed
    }

    /** Rule 1b: a better fix mid-wait replaces the candidate; the integral since the first held fix runs on. */
    private fun setCandidate(time: Long, lat: Double, lon: Double, accuracyM: Float) {
        hasCandidate = true
        candidateLat = lat
        candidateLon = lon
        candidateTime = time
        candidateAccuracyM = accuracyM
        candidateChainM = rawChainM
    }

    private fun startIntegral(time: Long, lat: Double, lon: Double, accuracyM: Float) {
        integralStartLat = lat
        integralStartLon = lon
        integralStartTime = time
        integralStartAccuracyM = accuracyM
        dopplerIntegralM = 0.0
        movingIntegralM = 0.0
        bridgedIntegralM = 0.0
        unknownDtS = 0.0
        dopplerChainIntact = true
        lastFixTime = time
        lastFixLat = lat
        lastFixLon = lon
        lastFixAccuracyM = accuracyM
    }

    private fun startChain(time: Long) {
        rawChainStartTime = time
        rawChainM = 0.0
        rawChainConsistent = true
    }

    /**
     * Rule 4: fold this fix's speed × its own dt into the integral; a gap over [dopplerMaxDtS] breaks the chain.
     * A [bridged] speed (rule 4c) goes into its own part; a raw one at or above [stationarySpeedMps] into the trusted part.
     */
    private fun fold(time: Long, lat: Double, lon: Double, accuracyM: Float, speed: Double, bridged: Boolean) {
        val dtS = (time - lastFixTime) / 1000.0
        if (dtS <= 0.0) return
        if (dtS > dopplerMaxDtS) dopplerChainIntact = false
        dopplerIntegralM += speed * dtS
        if (bridged) {
            bridgedIntegralM += speed * dtS
        } else if (speed >= stationarySpeedMps) {
            movingIntegralM += speed * dtS
            if (unknownDtS > 0.0) {
                // the first speed the receiver reports also fills in the seconds before it that had none (rule 4c)
                dopplerIntegralM += speed * unknownDtS
                bridgedIntegralM += speed * unknownDtS
                unknownDtS = 0.0
            }
        } else if (speed <= 0.0 && lastDopplerMps <= 0.0) {
            unknownDtS += dtS
        } else {
            unknownDtS = 0.0
        }
        lastFixTime = time
        lastFixLat = lat
        lastFixLon = lon
        lastFixAccuracyM = accuracyM
        lastDopplerMps = speed
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
        bridgedSpeedCount = 0
        lastAccuracyM = null
    }

    /**
     * Forget the anchor and the pace window but keep the distance: used at a pause (and at the stop) so the walk
     * between the pause point and the resume point is not counted. A rule 1b wait still open at this point has
     * measured real movement through its held fixes — the receiver's Doppler integral, capped as in rules 4 and 4b
     * at the last held fix, or without Doppler the hop from the first held fix to the last one (rule 1b) — which
     * no exit would otherwise credit, so it is credited here: a pause every 10 s in 25 m accuracy recorded nothing
     * without this because the wait never completed, and a workout stopped 14 s into a 25 m start lost those
     * seconds. It is deliberately *not* called after a GPS outage: the straight line from the last fix before the
     * gap to the first one after it is the best distance estimate we have for a tunnel or a tree-covered stretch,
     * so the controller lets the next fix bridge the gap.
     */
    fun markGap() {
        if (hasCandidate && !hasAnchor) {
            distanceMeters += if (dopplerIntegralM > 0.0) dopplerCredit(lastFixTime, lastFixLat, lastFixLon, lastFixAccuracyM)
            else waitHopCredit(lastFixTime, lastFixLat, lastFixLon, lastFixAccuracyM, rawChainM)
        }
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
        candidateChainM = 0.0
        waitStartTime = 0L
        startIntegral(0L, 0.0, 0.0, 0f)
        startChain(0L)
        lastDopplerMps = 0.0
        boundSpeedMps = 0.0
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
