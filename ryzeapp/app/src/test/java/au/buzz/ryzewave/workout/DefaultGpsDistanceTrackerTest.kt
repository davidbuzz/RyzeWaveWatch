package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class DefaultGpsDistanceTrackerTest {
    private val t = DefaultGpsDistanceTracker()
    private val lat0 = -27.4698
    private val lon0 = 153.0251

    private fun latPlus(meters: Double): Double = lat0 + meters / DEG_LAT_M
    private fun lonPlus(meters: Double): Double = lon0 + meters / (DEG_LAT_M * cos(Math.toRadians(lat0)))

    @Test
    fun haversineOneDegreeOfLatitude() {
        assertEquals(DEG_LAT_M, DefaultGpsDistanceTracker.haversineMeters(0.0, 0.0, 1.0, 0.0), 0.5)
        assertEquals(0.0, DefaultGpsDistanceTracker.haversineMeters(lat0, lon0, lat0, lon0), 0.0)
        assertEquals(100.0, DefaultGpsDistanceTracker.haversineMeters(lat0, lon0, lat0, lonPlus(100.0)), 0.05)
    }

    @Test
    fun rejectsPoorAccuracy() {
        assertFalse(t.addFix(0L, lat0, lon0, 61f, 1f))
        assertFalse(t.addFix(1000L, lat0, lon0, Float.NaN, 1f))
        assertFalse(t.addFix(1500L, lat0, lon0, 9_999f, 1f))       // WorkoutService.NO_ACCURACY_M
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(3, t.rejectedAccuracyCount)
        assertEquals(0, t.acceptedCount)
        // exactly 60 m passes the gate (25 m used to be dropped by the old 20 m gate, the vendor's 0.0 km symptom),
        // but as a *first* fix it is only held as the anchor candidate (rule 1b) until something ≤ 20 m or 15 s arrives
        assertFalse(t.addFix(2000L, lat0, lon0, 60f, 1f))
        assertEquals(3, t.rejectedAccuracyCount)
        assertEquals(1, t.deferredFirstFixCount)
        assertEquals(0, t.acceptedCount)
        assertTrue(t.addFix(17_000L, lat0, lon0, 60f, 1f))     // 15 s later, nothing better: anchored on the best seen
        assertEquals(1, t.acceptedCount)
        assertEquals(0.0, t.distanceMeters, 0.0)
    }

    @Test
    fun poorFirstFixDoesNotAnchorTheWalkOffThePath() {
        // the first real walk: a 52 m first fix about 45 m beside the loop, then good fixes along it. The hop from the
        // scattered fix to the loop must not become distance.
        assertFalse(t.addFix(0L, lat0, lonPlus(45.0), 50f, 0f))
        assertEquals(1, t.deferredFirstFixCount)
        assertEquals(0, t.acceptedCount)
        for (i in 1..10) assertTrue(t.addFix(i * 1000L, latPlus(1.2 * i), lon0, 5f, 1.2f))   // 5 m: anchors at once
        // 10 × 1.2 m: the second between the held fix and the 5 m fix is credited from the receiver's speed (the
        // movement during the wait is real, rule 1b), the 45 m hop back to the loop is not
        assertEquals(12.0, t.distanceMeters, 0.1)
        assertEquals(10, t.acceptedCount)
        assertEquals(1, t.deferredFirstFixCount)
    }

    // rule 1b: the Doppler integral over the held fixes is credited on every way out of the wait

    @Test
    fun waitCreditsTheIntegralWhenADecentFixArrives() {
        // 3 m/s with 25 m fixes for 10 s, then a 15 m fix: the 30 m run during the wait is credited (it used to be
        // dropped: 45 m per start and per resume at 3 m/s)
        for (i in 0..9) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 3f))
        assertEquals(10, t.deferredFirstFixCount)
        assertTrue(t.addFix(10_000L, latPlus(30.0), lon0, 15f, 3f))
        assertEquals(30.0, t.distanceMeters, 0.05)
        assertEquals(1, t.acceptedCount)
    }

    @Test
    fun waitCreditsTheIntegralWhenABetterCurrentFixIsAcceptedFromTheCandidateAtExpiry() {
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 30f, 3f))
        // wait over: the t=0 candidate anchors (the best fix held *before* this one, even though this one is better)
        // and this fix is accepted from it with the integral since the first held fix
        assertTrue(t.addFix(15_000L, latPlus(45.0), lon0, 25f, 3f))
        assertEquals(45.0, t.distanceMeters, 0.05)                        // 15 s × 3 m/s
        assertEquals(1, t.acceptedCount)
        assertEquals(0, t.escapedSpikeCount)
    }

    @Test
    fun waitCreditsTheIntegralWhenTheCandidateBecomesTheAnchorAtExpiry() {
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 3f))
        assertTrue(t.addFix(15_000L, latPlus(45.0), lon0, 25f, 3f))      // the t=0 candidate anchors; this fix is accepted from it
        assertEquals(45.0, t.distanceMeters, 0.05)                        // the integral since t=0, capped by the 45 m hop + 25 m
        assertEquals(1, t.acceptedCount)
    }

    @Test
    fun aBetterCandidateMidWaitDoesNotRestartTheIntegral() {
        assertFalse(t.addFix(0L, lat0, lon0, 30f, 3f))
        for (i in 1..4) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 30f, 3f))
        assertFalse(t.addFix(5000L, latPlus(15.0), lon0, 25f, 3f))       // a better candidate 5 s in
        for (i in 6..14) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 30f, 3f))
        assertTrue(t.addFix(15_000L, latPlus(45.0), lon0, 28f, 3f))      // expiry: the t=5 candidate anchors, this fix is judged from it
        assertEquals(45.0, t.distanceMeters, 0.05)                        // 15 s of running since the first held fix, not 10 s since the candidate
    }

    @Test
    fun waitExpiryAcceptsASpeedlessRiderFromTheWaitsChainSpeed() {
        // a 6 m/s cyclist with no Doppler speed and 25 m fixes: after the 15 s wait the current fix is 90 m from the
        // t=0 candidate, more than 2.5 × 15 + two radii — but the held fixes walked those 90 m in 6 m hops, so the
        // plausibility bound is seeded with the chain's 6 m/s and the fix is accepted as the 90 m hop. Rejecting it
        // deadlocked the tracker (every later fix was farther still, 0.0 m after 20 minutes); anchoring on it
        // without credit, as the previous version did, lost the wait's 90 m at every start.
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(6.0 * i), lon0, 25f, 0f))
        assertTrue(t.addFix(15_000L, latPlus(90.0), lon0, 25f, 0f))
        assertEquals(0, t.rejectedSpikeCount)
        assertEquals(0, t.escapedSpikeCount)
        assertEquals(90.0, t.distanceMeters, 0.05)
        for (i in 16..21) assertFalse(t.addFix(i * 1000L, latPlus(6.0 * i), lon0, 25f, 0f))   // 6..36 m: inside the 37.5 m radius
        assertTrue(t.addFix(22_000L, latPlus(132.0), lon0, 25f, 0f))                            // 42 m over 7 s: the plain 2.5 × 7 + 50 bound
        assertEquals(132.0, t.distanceMeters, 0.1)
    }

    @Test
    fun aSpikeAtWaitExpiryIsRejectedAndTheCandidateStaysTheAnchor() {
        // the same start with Doppler 6 m/s, but the fix at expiry is a real spike (200 m: a 116 m hop in 1 s from the
        // previous raw fix breaks the chain). It is rejected like any spike — anchoring on it put the anchor 100 m off
        // the path and cost the wait plus an escape — and the next fix on the path is accepted from the t=0 candidate
        // with the receiver's 96 m
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(6.0 * i), lon0, 25f, 6f))
        assertFalse(t.addFix(15_000L, latPlus(200.0), lon0, 25f, 6f))
        assertEquals(1, t.rejectedSpikeCount)
        assertEquals(0, t.escapedSpikeCount)
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertTrue(t.addFix(16_000L, latPlus(96.0), lon0, 25f, 6f))
        assertEquals(96.0, t.distanceMeters, 0.05)
        assertEquals(1, t.acceptedCount)
    }

    @Test
    fun aRiderBeyondTheSpeedCapAtWaitExpiryAnchorsWithTheChainCredit() {
        // no Doppler, 14 m/s hops (noisy but consistent: below 1.5 × 12 m/s) held for 15 s with a better fix at t=10
        // as the candidate. At expiry the 140 m from the first held fix to the candidate are credited (beyond the
        // noise; capped at 12 m/s × 10 s = 120 m) and the candidate anchors; the fix at expiry is 12.4 m/s from it —
        // over the cap, a spike — yet 6 m/s from the previous raw fix with the whole chain consistent: the rider
        // outran the bound, so the fix anchors crediting the chain from the candidate (62 m, capped at 12 m/s × 5 s)
        // instead of being rejected for ever: 120 + 60 = 180 m of the 202 m ridden
        assertFalse(t.addFix(0L, lat0, lon0, 30f, 0f))
        for (i in 1..14) assertFalse(t.addFix(i * 1000L, latPlus(14.0 * i), lon0, if (i == 10) 25f else 30f, 0f))
        assertTrue(t.addFix(15_000L, latPlus(202.0), lon0, 30f, 0f))
        assertEquals(1, t.rejectedSpikeCount)
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(180.0, t.distanceMeters, 0.05)
    }

    // rule 3: the bound knows the receiver's last Doppler speed but not a hop-derived one, and spikes cannot go on
    // for ever — the escape credits the raw chain when the fixes agree with each other

    @Test
    fun aSpeedlessRunnerRejectedByNoiseIsRecoveredFromTheRawChain() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertTrue(t.addFix(2000L, latPlus(12.0), lon0, 5f, 0f))          // 6 m/s over 2 s: 12 m ≤ 2.5 × 2 + 10
        assertFalse(t.addFix(4000L, latPlus(28.0), lon0, 5f, 0f))         // 16 m over 2 s > 15: a spike (noise), 8 m/s from the previous fix (1)
        assertFalse(t.addFix(5000L, latPlus(34.0), lon0, 5f, 0f))         // 22 m over 3 s > 17.5: the bound grows 2.5 m/s, the runner 6 (2)
        assertEquals(2, t.rejectedSpikeCount)
        // (3): three spikes each ≤ 12 m/s from the previous raw fix and no hop of the chain over 18 m/s — the fixes
        // agree with each other and only the anchor is stale. Re-anchor crediting the chain since the anchor
        // (16 + 6 + 6 = 28 m, within 12 m/s × 4 s), not the 28 m hop and not nothing
        assertTrue(t.addFix(6000L, latPlus(40.0), lon0, 5f, 0f))
        assertEquals(3, t.rejectedSpikeCount)
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(40.0, t.distanceMeters, 0.05)
        assertTrue(t.addFix(8000L, latPlus(52.0), lon0, 5f, 0f))          // and the run is measured on from the new anchor (12 m over 2 s)
        assertEquals(52.0, t.distanceMeters, 0.05)
    }

    @Test
    fun aHopDerivedSpeedDoesNotWidenTheBound() {
        // a 6 m/s hop accepted at t=2 does not make 6 m/s the bound: the next fix is judged by the plain
        // 2.5 × dt + radii (a hop-derived speed is noisy and self-reinforcing; the escape above recovers a real runner)
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertTrue(t.addFix(2000L, latPlus(12.0), lon0, 5f, 0f))
        assertFalse(t.addFix(5000L, latPlus(30.0), lon0, 5f, 0f))         // 18 m over 3 s > 17.5: a spike
        assertEquals(1, t.rejectedSpikeCount)
        assertEquals(12.0, t.distanceMeters, 0.05)
    }

    @Test
    fun theReceiversLastDopplerSpeedWidensTheBound() {
        // with a Doppler speed of 6 m/s at the last accepted fix, a fix reporting nothing (a dropout) 18 m away over
        // 3 s is within 6 × 3 + 10 and accepted
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 6f))
        assertTrue(t.addFix(2000L, latPlus(12.0), lon0, 5f, 6f))
        assertTrue(t.addFix(5000L, latPlus(30.0), lon0, 5f, 0f))
        assertEquals(0, t.rejectedSpikeCount)
        assertEquals(30.0, t.distanceMeters, 0.05)
    }

    @Test
    fun threeConsistentSpikesReAnchorCreditingTheRawChain() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertFalse(t.addFix(1000L, latPlus(2.0), lon0, 5f, 0f))          // jitter
        assertFalse(t.addFix(2000L, latPlus(16.0), lon0, 5f, 0f))         // 16 m > 2.5 × 2 + 10: spike; 14 m/s from the previous fix, not agreeing (but under the 18 m/s chain allowance)
        assertFalse(t.addFix(3000L, latPlus(26.0), lon0, 5f, 0f))         // spike, 10 m/s from the previous fix (1)
        assertFalse(t.addFix(4000L, latPlus(36.0), lon0, 5f, 0f))         // spike (2)
        assertTrue(t.addFix(5000L, latPlus(46.0), lon0, 5f, 0f))          // (3): the fixes agree with each other, the anchor is stale — re-anchor here
        assertEquals(4, t.rejectedSpikeCount)
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(46.0, t.distanceMeters, 0.05)                         // the chain the fixes drew: 2 + 14 + 10 + 10 + 10 m (≤ 12 m/s × 5 s)
        assertTrue(t.addFix(6000L, latPlus(56.0), lon0, 5f, 0f))          // and the walk is measured on from the new anchor
        assertEquals(56.0, t.distanceMeters, 0.05)
    }

    @Test
    fun aBurstOfOffTrackFixesEnteredAtSpeedIsRiddenOutAsSpikes() {
        // 3 m/s with Doppler at 6 m; six consecutive multipath fixes 40 m east of the path agree with each other at
        // 3 m/s, but the 40 m/s hop into the burst broke the raw chain, so they do not re-anchor the walk on the
        // excursion (which lost ~8 s per burst): the first fix back on the path is accepted from the old anchor
        for (i in 0..9) assertTrue(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 6f, 3f))
        for (i in 10..15) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lonPlus(40.0), 6f, 3f))
        assertEquals(6, t.rejectedSpikeCount)
        assertEquals(0, t.escapedSpikeCount)
        assertTrue(t.addFix(16_000L, latPlus(48.0), lon0, 6f, 3f))       // 21 m over 7 s from the t=9 anchor: the hop (the chain is > 5 s)
        assertEquals(48.0, t.distanceMeters, 0.05)
    }

    @Test
    fun thirtySecondsOfSpikesReAnchorWithoutCredit() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertFalse(t.addFix(4000L, latPlus(100.0), lon0, 5f, 0f))        // 25 m/s: spike, and the chain is broken (> 18 m/s)
        for (k in 2..8) assertFalse(t.addFix(k * 4000L, latPlus(50.0 + 50.0 * k), lon0, 5f, 0f))   // 12.5 m/s from the previous fix each: spike, not agreeing
        assertEquals(8, t.rejectedSpikeCount)                              // 28 s of spikes so far
        assertTrue(t.addFix(36_000L, latPlus(500.0), lon0, 5f, 0f))       // still a spike, but spikes for 32 s > 30 s: re-anchor here
        assertEquals(9, t.rejectedSpikeCount)                              // 9 spikes, the last one escaped
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(0.0, t.distanceMeters, 0.0)                           // nothing is invented across a broken chain
    }

    @Test
    fun spikesAreNotIntegrated() {
        // 3 m/s at 6 m accuracy; one fix 25 m off the path reporting 10 m/s is a spike: its speed is not integrated
        // (the fix is not believed about anything), the next fix's 2 s span it at the receiver's 3 m/s
        for (i in 0..9) assertTrue(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 6f, 3f))
        assertEquals(27.0, t.distanceMeters, 0.05)
        assertFalse(t.addFix(10_000L, latPlus(30.0), lonPlus(25.0), 6f, 10f))    // 25.2 m in 1 s
        assertEquals(1, t.rejectedSpikeCount)
        assertTrue(t.addFix(11_000L, latPlus(33.0), lon0, 6f, 3f))
        assertEquals(33.0, t.distanceMeters, 0.05)                                 // 27 + 3 × 2 s, not 27 + 10 + 3
    }

    // rule 4b: sub-threshold speeds are believed as far as the position confirms them

    @Test
    fun creepingReceiverCreditsAboutTheHopNotTheAccuracyRadius() {
        // standing for 60 s at 5 m accuracy with the receiver saying 0.7 m/s and the position scattering ±2 m, then
        // one second of walking at 1.2 m/s. The 42 m the receiver claimed for the stop are met by a 1.2 m hop, so
        // the credit is the walking second plus the 3 % of the claim that hop covers — not min(43.2, 1.2 + 5) = 6.2 m
        // (one accuracy radius per stop, what the integral alone gave)
        t.addFix(0L, lat0, lon0, 5f, 0.7f)
        for (i in 1..60) assertFalse(t.addFix(i * 1000L, lat0, lonPlus(if (i % 2 == 0) 2.0 else -2.0), 5f, 0.7f))
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertTrue(t.addFix(61_000L, latPlus(1.2), lon0, 5f, 1.2f))
        assertEquals(1.23, t.distanceMeters, 0.05)
    }

    @Test
    fun slowWalkingThePositionConfirmsIsCredited() {
        // 0.5 m/s for 9 s (jitter: inside the 7.5 m radius, but the position advances in step), then 1.2 m/s: the
        // 6.2 m hop confirms the 4.5 m of sub-threshold integral, so the whole 5.7 m is credited
        t.addFix(0L, lat0, lon0, 5f, 0.5f)
        for (i in 1..9) assertFalse(t.addFix(i * 1000L, latPlus(0.5 * i), lon0, 5f, 0.5f))
        assertTrue(t.addFix(10_000L, latPlus(6.2), lon0, 5f, 1.2f))
        assertEquals(5.7, t.distanceMeters, 0.05)
    }

    @Test
    fun reAnchorWithoutAReceiverSpeedReportsTheCreditedSpeed() {
        // 30 m fixes walking 1.2 m/s for 15 s (held: 14 intervals of 1.2 m), the fix at expiry says 0.5 m/s and is
        // jitter from the t=0 candidate that anchors (uncredited); a 5 m fix inside its radius then replaces it
        // (rule 1c) crediting the 16.8 + 0.5 = 17.3 m integral — and, with no receiver speed at that fix, rule 6
        // reports 17.3 m / 16 s
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(1.2 * i), lon0, 30f, 1.2f))
        assertFalse(t.addFix(15_000L, latPlus(18.0), lon0, 30f, 0.5f))
        assertEquals(1, t.rejectedJitterCount)
        assertTrue(t.addFix(16_000L, latPlus(18.6), lon0, 5f, 0f))
        assertEquals(1, t.reAnchoredCount)
        assertEquals(17.3, t.distanceMeters, 0.05)
        assertEquals(17.3 / 16.0, t.speedMps, 0.01)
    }

    @Test
    fun firstFixWaitExpiresOnTheFirstHeldFixWhenTheBetterOneIsInsideItsNoise() {
        // no Doppler, only 25–30 m fixes for 15 s: the best of them (25 m at t=5) is 10 m from the first held fix —
        // inside the 45 m jitter radius of the pair — and not 3 × better, so the *first* held fix anchors (the walk
        // since t=0 stays inside the first hop, as if it had anchored at once); anchoring on the t=5 fix lost 10 m
        assertFalse(t.addFix(0L, lat0, lon0, 30f, 0f))
        assertFalse(t.addFix(5000L, latPlus(10.0), lon0, 25f, 0f))
        assertFalse(t.addFix(10_000L, latPlus(20.0), lon0, 28f, 0f))
        assertEquals(3, t.deferredFirstFixCount)
        assertEquals(0, t.acceptedCount)
        assertFalse(t.addFix(15_000L, latPlus(30.0), lon0, 28f, 0f))   // wait over: anchor = t=0 fix; this one is 30 m < 45 m jitter
        assertEquals(3, t.deferredFirstFixCount)
        assertEquals(1, t.rejectedJitterCount)
        assertTrue(t.addFix(25_000L, latPlus(80.0), lon0, 5f, 0f))     // 80 m from the t=0 anchor: the whole walk
        assertEquals(80.0, t.distanceMeters, 0.5)
        assertEquals(1, t.acceptedCount)
    }

    @Test
    fun hopModeWaitCreditsTheHopToTheCandidateAtExpiry() {
        // no Doppler, 3 m/s: 25 m fixes, a 21 m fix at t=14 (the candidate) 42 m from the first held fix — beyond the
        // 37.5 m jitter radius, so it is real movement: credited at expiry (capped by the raw chain), the candidate
        // anchors, and the t=15 fix is jitter from it. Anchoring on the candidate without the credit lost those 42 m
        // at every start and every resume (−1.1 %)
        for (i in 0..13) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 0f))
        assertFalse(t.addFix(14_000L, latPlus(42.0), lon0, 21f, 0f))
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertFalse(t.addFix(15_000L, latPlus(45.0), lon0, 25f, 0f))
        assertEquals(42.0, t.distanceMeters, 0.05)
        assertEquals(1, t.rejectedJitterCount)
        assertTrue(t.addFix(28_000L, latPlus(84.0), lon0, 25f, 0f))    // 42 m from the candidate: the next hop
        assertEquals(84.0, t.distanceMeters, 0.05)
    }

    @Test
    fun hopModeWaitCreditsTheHopToADecentFixBeyondTheNoise() {
        // no Doppler, 3 m/s: 25 m fixes for 14 s, then a 19 m fix 42 m from the first held fix: the wait ends on it
        // with the 42 m credited (the decent-fix exit credited nothing for a speed-less receiver: −1.1 % per start)
        for (i in 0..13) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 0f))
        assertTrue(t.addFix(14_000L, latPlus(42.0), lon0, 19f, 0f))
        assertEquals(42.0, t.distanceMeters, 0.05)
        assertEquals(1, t.acceptedCount)
        assertEquals(14, t.deferredFirstFixCount)
        assertEquals(3.0, t.speedMps, 0.01)                             // rule 6: 42 m / 14 s
    }

    @Test
    fun hopModeWaitAnchorsOnTheFirstHeldFixWhenTheDecentFixIsInsideTheNoise() {
        // no Doppler, 1.2 m/s: 25 m fixes for 10 s, then a 19 m fix 12 m from the first held fix — inside the noise and
        // not 3 × better: the first held fix anchors and the 19 m fix is jitter from it, so the walk is measured from
        // t=0 (the baseline's behaviour) instead of losing the 12 m
        for (i in 0..9) assertFalse(t.addFix(i * 1000L, latPlus(1.2 * i), lon0, 25f, 0f))
        assertFalse(t.addFix(10_000L, latPlus(12.0), lon0, 19f, 0f))
        assertEquals(0, t.acceptedCount)
        assertEquals(1, t.rejectedJitterCount)
        assertTrue(t.addFix(40_000L, latPlus(48.0), lon0, 5f, 0f))     // 48 m from the t=0 anchor
        assertEquals(48.0, t.distanceMeters, 0.05)
    }

    @Test
    fun hopModeWaitSkipsTheHopToAMuchBetterDecentFix() {
        // the real walk's case without Doppler: a 60 m first fix 24 m beside the path, then a 17 m fix on it — 3 ×
        // better, inside the noise: it anchors and the 24 m of the poor fix's scatter are not distance
        assertFalse(t.addFix(0L, lat0, lonPlus(24.0), 60f, 0f))
        assertTrue(t.addFix(9000L, lat0, lon0, 17f, 0f))
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(1, t.acceptedCount)
    }

    @Test
    fun markGapCreditsTheHopOfAnOpenWaitWithoutDoppler() {
        // no Doppler, 3 m/s in 25 m: a pause 14 s into the wait credits the 39 m from the first held fix to the last one
        // (beyond the 37.5 m radius); a pause 10 s in credits nothing — 27 m is inside the noise. A pause cadence of
        // 14–15 s in 25 m recorded nothing without this; the controller calls markGap() at a pause and at the stop
        for (i in 0..13) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 0f))
        t.markGap()
        assertEquals(39.0, t.distanceMeters, 0.05)
        for (i in 20..29) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 0f))
        t.markGap()
        assertEquals(39.0, t.distanceMeters, 0.05)
    }

    // rule 4c: an exact 0 after a Doppler speed is a dropout, bridged with the last speed and confirmed by the position

    @Test
    fun aDopplerDropoutIsBridgedWithTheLastSpeed() {
        // 3 m/s at 6 m; one fix with hasSpeed() false (0) 3 m along the path is held inside the jitter radius, folded
        // at the last speed, and the next fix credits both seconds: 18 m, not 15 (the baseline: 18)
        for (i in 0..4) assertTrue(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 6f, 3f))
        assertEquals(12.0, t.distanceMeters, 0.05)
        assertFalse(t.addFix(5000L, latPlus(15.0), lon0, 6f, 0f))
        assertEquals(1, t.bridgedSpeedCount)
        assertTrue(t.addFix(6000L, latPlus(18.0), lon0, 6f, 3f))
        assertEquals(18.0, t.distanceMeters, 0.05)
        assertEquals(3.0, t.speedMps, 1e-6)
    }

    @Test
    fun aLongDropoutIsAcceptedOnItsPositionWithTheBridge() {
        // 3 m/s in 25 m (a 5 m first fix anchors at once), the speed missing for 13 s: the 13th 0 fix is 39 m from the
        // anchor — beyond the 37.5 m radius — and is accepted crediting the 13 bridged seconds (39 m, consistent with
        // the hop), reporting 3 m/s
        for (i in 0..4) assertTrue(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, if (i == 0) 5f else 25f, 3f))
        for (i in 5..16) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 0f))
        assertTrue(t.addFix(17_000L, latPlus(51.0), lon0, 25f, 0f))
        assertEquals(51.0, t.distanceMeters, 0.05)
        assertEquals(13, t.bridgedSpeedCount)
        assertEquals(3.0, t.speedMps, 1e-6)
        assertEquals(0, t.rejectedSpikeCount)
    }

    @Test
    fun aStopTheReceiverReportsAsZeroIsNotBridged() {
        // walking 1.2 m/s in 25 m (a 5 m first fix anchors at once), then standing 60 s with the receiver saying exactly
        // 0 (position scattering ±2 m), then one step: the bridge claims 72 m for the stop, the hop shows 1.2 —
        // contradicted, nothing of it is credited, and the walk is exact (the plain "hop + accuracy" allowance
        // credited 25 m per 20 s stop)
        for (i in 0..4) assertTrue(t.addFix(i * 1000L, latPlus(1.2 * i), lon0, if (i == 0) 5f else 25f, 1.2f))
        for (i in 5..64) assertFalse(t.addFix(i * 1000L, latPlus(4.8), lonPlus(if (i % 2 == 0) 2.0 else -2.0), 25f, 0f))
        assertEquals(60, t.bridgedSpeedCount)
        assertTrue(t.addFix(65_000L, latPlus(6.0), lon0, 25f, 1.2f))
        assertEquals(6.0, t.distanceMeters, 0.05)
    }

    @Test
    fun aBridgedFixAcceptedOnItsPositionCreditsTheHopWhenTheBridgeIsContradicted() {
        // 3 m/s in 25 m (a 5 m first fix anchors at once), then the receiver loses its speed for good while the runner
        // slows to 1 m/s: the bridge claims 3 m/s, the position shows 1 m/s — when a fix finally leaves the 37.5 m
        // radius (38 s later) the bridge is contradicted and the fix credits its hop like any speed-less fix,
        // reporting the hop's speed
        for (i in 0..2) assertTrue(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, if (i == 0) 5f else 25f, 3f))
        for (k in 1..37) assertFalse(t.addFix((2 + k) * 1000L, latPlus(6.0 + k), lon0, 25f, 0f))
        assertTrue(t.addFix(40_000L, latPlus(44.0), lon0, 25f, 0f))
        assertEquals(44.0, t.distanceMeters, 0.05)
        assertEquals(1.0, t.speedMps, 0.01)
    }

    @Test
    fun aDropoutAtTheStartIsFilledInByTheFirstSpeed() {
        // 6 m fixes at 3 m/s whose first three report 0 (no speed yet): the first speed reported fills in the two
        // seconds before it, and the position (9 m) agrees
        assertTrue(t.addFix(0L, lat0, lon0, 6f, 0f))
        assertFalse(t.addFix(1000L, latPlus(3.0), lon0, 6f, 0f))
        assertFalse(t.addFix(2000L, latPlus(6.0), lon0, 6f, 0f))
        assertTrue(t.addFix(3000L, latPlus(9.0), lon0, 6f, 3f))
        assertEquals(9.0, t.distanceMeters, 0.05)
    }

    @Test
    fun aSubThresholdSpeedIsNotBridged() {
        // 0.3 m/s is the receiver's own measurement (rule 4b), not a dropout: 12 + 0.3 + 3 = 15.3 m
        for (i in 0..4) assertTrue(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 6f, 3f))
        assertFalse(t.addFix(5000L, latPlus(15.0), lon0, 6f, 0.3f))
        assertEquals(0, t.bridgedSpeedCount)
        assertTrue(t.addFix(6000L, latPlus(18.0), lon0, 6f, 3f))
        assertEquals(15.3, t.distanceMeters, 0.05)
    }

    // rule 3 with Doppler: the agreeing-spikes escape is for speed-less receivers; the time escape credits the last speed

    @Test
    fun agreeingSpikesDoNotReAnchorAReceiverWithDoppler() {
        // 3 m/s at 5 m; a multipath excursion ramping 15 / 23 / 31 / 39 m east: each fix is a spike from the anchor, the
        // last three agree with each other at 8.5 m/s on a consistent chain — the speed-less escape's trigger — but
        // the receiver reports 3 m/s, so the excursion is ridden out and the first fix back on the path is accepted
        // from the old anchor with the 5 s integral: 42 m, not the excursion's chain
        for (i in 0..9) assertTrue(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 5f, 3f))
        for ((k, east) in doubleArrayOf(15.0, 23.0, 31.0, 39.0).withIndex()) assertFalse(t.addFix((10 + k) * 1000L, latPlus(3.0 * (10 + k)), lonPlus(east), 5f, 3f))
        assertEquals(4, t.rejectedSpikeCount)
        assertEquals(0, t.escapedSpikeCount)
        assertTrue(t.addFix(14_000L, latPlus(42.0), lon0, 5f, 3f))
        assertEquals(42.0, t.distanceMeters, 0.05)
    }

    @Test
    fun theTimeEscapeWithDopplerCreditsTheLastSpeedOverTheInterval() {
        // a cyclist reporting 12 m/s whose hops measure 12.5 m/s (over the cap: every fix a spike) for over 30 s: the
        // time escape re-anchors crediting 12 m/s × 32 s = 384 m, capped by the 400 m hop — not the empty integral
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 12f))
        for (i in 1..31) assertFalse(t.addFix(i * 1000L, latPlus(12.5 * i), lon0, 5f, 12f))
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertTrue(t.addFix(32_000L, latPlus(400.0), lon0, 5f, 12f))
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(384.0, t.distanceMeters, 0.05)
    }

    @Test
    fun aDuplicatedTimestampIsRejected() {
        // a second fix with the same timestamp has no interval: its 2 m of scatter used to be credited as a hop
        assertTrue(t.addFix(0L, lat0, lon0, 6f, 3f))
        assertTrue(t.addFix(1000L, latPlus(3.0), lon0, 6f, 3f))
        assertFalse(t.addFix(1000L, latPlus(3.0), lonPlus(2.0), 6f, 3f))
        assertEquals(3.0, t.distanceMeters, 0.05)
        assertEquals(1, t.rejectedJitterCount)
        assertTrue(t.addFix(2000L, latPlus(6.0), lon0, 6f, 3f))
        assertEquals(6.0, t.distanceMeters, 0.05)
        // during a wait too
        t.markGap()
        assertFalse(t.addFix(10_000L, latPlus(6.0), lon0, 25f, 3f))
        assertFalse(t.addFix(10_000L, latPlus(8.0), lon0, 25f, 3f))
        assertEquals(1, t.deferredFirstFixCount)
        assertEquals(2, t.rejectedJitterCount)
    }

    @Test
    fun firstFixWaitExpiresOnTheBestFixHeldBeforeTheCurrentOne() {
        assertFalse(t.addFix(0L, lat0, lon0, 30f, 0f))
        // still > 20 m at expiry and better than the candidate — but the candidate anchors (the best fix held before
        // this one) and this fix is judged from it: 10 m inside the 45 m radius is jitter
        assertFalse(t.addFix(15_000L, latPlus(10.0), lon0, 22f, 0f))
        assertEquals(1, t.rejectedJitterCount)
        assertEquals(0, t.acceptedCount)
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertTrue(t.addFix(35_000L, latPlus(60.0), lon0, 5f, 0f))     // 60 m from the t=0 anchor (what anchoring at once gave)
        assertEquals(60.0, t.distanceMeters, 0.5)
        assertEquals(1, t.acceptedCount)
    }

    @Test
    fun aMuchBetterFixInsideAPoorStartingAnchorsRadiusReplacesItWithoutCredit() {
        // no Doppler, standing at the start. Only 28–30 m fixes for 15 s, so the wait expires on the 30 m fix (an
        // uncredited starting anchor); the next fix is 5 m and lands 20 m from it — inside its radius, 6 × better —
        // so it becomes the anchor with no credit (rule 1c): the 20 m are the poor anchor's scatter, not a walk.
        // The slow walk that follows is then measured from the good position with the 5 m radius.
        assertFalse(t.addFix(0L, lat0, lonPlus(20.0), 30f, 0f))
        assertFalse(t.addFix(15_000L, latPlus(0.0), lonPlus(20.0), 28f, 0f))    // wait over: the t=0 fix anchors, this one is jitter from it
        assertEquals(0, t.acceptedCount)
        assertTrue(t.addFix(16_000L, lat0, lon0, 5f, 0f))
        assertEquals(1, t.reAnchoredCount)
        assertEquals(1, t.acceptedCount)
        assertEquals(0.0, t.distanceMeters, 0.0)                        // the 20 m back to the path is not distance
        assertTrue(t.addFix(26_000L, latPlus(10.0), lon0, 5f, 0f))     // 10 m > 7.5 m radius of the new anchor
        assertEquals(10.0, t.distanceMeters, 0.1)                       // with the 28 m anchor this was 22.4 m of "jitter"
        assertEquals(1, t.reAnchoredCount)
    }

    @Test
    fun aMuchBetterFixInsideAPoorStartingAnchorsRadiusCreditsTheDopplerIntegral() {
        // the same start, but the receiver says the walker was moving at 1.2 m/s (30 m fixes 20 m beside the path,
        // held for 15 s; the fix at expiry says 0.5 m/s and is jitter from the t=0 anchor): the poor anchor's position
        // is still not trusted (it is replaced by the 5 m fix 27.7 m away, 6 × better), but the Doppler integral
        // since it — 14 × 1.2 + 0.5 + 1.2 m — is credited, like rule 4 would
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(1.2 * i), lonPlus(20.0), 30f, 1.2f))
        assertFalse(t.addFix(15_000L, latPlus(18.0), lonPlus(20.0), 28f, 0.5f))    // wait over: the t=0 fix anchors, this one is jitter from it
        assertTrue(t.addFix(16_000L, latPlus(19.2), lon0, 5f, 1.2f))               // inside the 30 m radius, 6 × better: replaces it
        assertEquals(1, t.reAnchoredCount)
        assertEquals(18.5, t.distanceMeters, 0.05)                                 // the integral, not 0 and not the 27.7 m hop
        assertTrue(t.addFix(17_000L, latPlus(20.4), lon0, 5f, 1.2f))
        assertEquals(19.7, t.distanceMeters, 0.05)
    }

    @Test
    fun aPoorAnchorThatHasCreditedAHopIsKeptMidWalk() {
        // no Doppler. A good anchor, then a 30 m fix accepted 50 m along the path but scattered 20 m sideways, then
        // a 5 m fix on the path 20 m from it. The 30 m anchor has credited a hop, so rule 1c leaves it alone: the
        // 5 m fix is jitter inside its 45 m radius (the anchor stays), and the walk accumulates once it leaves
        // that radius. The unrestricted rule replaced every such anchor and dropped the interval since it — one
        // fix interval of real movement per accuracy step, 10–50 % of a run with stepping accuracy bands.
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertTrue(t.addFix(20_000L, latPlus(50.0), lonPlus(20.0), 30f, 0f))
        assertEquals(53.9, t.distanceMeters, 0.1)
        assertFalse(t.addFix(21_000L, latPlus(51.0), lon0, 5f, 0f))    // 20 m < 45 m: jitter, anchor kept
        assertEquals(0, t.reAnchoredCount)
        assertFalse(t.addFix(31_000L, latPlus(61.0), lon0, 5f, 0f))    // 22.8 m < 45 m: still inside the poor anchor's radius
        assertEquals(53.9, t.distanceMeters, 0.1)
        assertTrue(t.addFix(51_000L, latPlus(100.0), lon0, 5f, 0f))    // 53.9 m > 45 m: the hop from the 30 m anchor
        assertEquals(107.7, t.distanceMeters, 0.2)                      // 100 m walked; the 30 m fix's 20 m scatter is the error
        assertEquals(0, t.reAnchoredCount)
    }

    @Test
    fun creditedPoorAnchorIsNotReplacedWhileRunningThroughAccuracyBands() {
        // Doppler 3 m/s, accuracy 25 m with every 10th fix at 8 m (the first one anchors at once): with the
        // unrestricted rule 1c each later 8 m fix (3.1 × better, 3 m from the 25 m anchor) replaced the anchor and
        // dropped the 3 m integral — 10 % of the run; now every interval is credited
        for (i in 0..30) assertTrue(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, if (i % 10 == 0) 8f else 25f, 3.0f))
        assertEquals(0, t.reAnchoredCount)
        assertEquals(31, t.acceptedCount)
        assertEquals(90.0, t.distanceMeters, 0.1)
    }

    @Test
    fun goodAnchorIsNotReplacedByABetterFix() {
        // 9 m → 3 m is a routine accuracy step on a phone; replacing the anchor would drop a second of walking each time
        assertTrue(t.addFix(0L, lat0, lon0, 9f, 1.2f))
        assertTrue(t.addFix(1000L, latPlus(1.2), lon0, 3f, 1.2f))
        assertEquals(0, t.reAnchoredCount)
        assertEquals(1.2, t.distanceMeters, 0.05)
    }

    @Test
    fun dopplerCreditIsIntegratedPerFix() {
        // standing, then starting to walk: speeds 0, 0, 0.4, 1.2 m/s over 4 s credit 0 + 0 + 0.4 + 1.2 = 1.6 m at the
        // fix that is finally accepted — not 1.2 m/s × 4 s = 4.8 m (the build 6 rule)
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertFalse(t.addFix(1000L, latPlus(0.2), lon0, 5f, 0f))
        assertFalse(t.addFix(2000L, latPlus(0.4), lon0, 5f, 0f))
        assertFalse(t.addFix(3000L, latPlus(0.8), lon0, 5f, 0.4f))
        assertTrue(t.addFix(4000L, latPlus(1.6), lon0, 5f, 1.2f))
        assertEquals(1.6, t.distanceMeters, 0.05)
    }

    @Test
    fun dopplerIntegralSkipsAccuracyRejectedFixesAndNeedsAnUnbrokenChain() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 1.2f))
        assertFalse(t.addFix(1000L, latPlus(1.2), lon0, 100f, 5f))      // beyond the gate: ignored, its 5 m/s does not count
        assertTrue(t.addFix(2000L, latPlus(2.4), lon0, 5f, 1.2f))       // its own dt is the 2 s since the anchor
        assertEquals(2.4, t.distanceMeters, 0.05)
        assertTrue(t.addFix(8000L, latPlus(10.2), lon0, 5f, 1.2f))      // 6 s gap > 5 s: the hop (7.8 m), not 1.2 × 6
        assertEquals(10.2, t.distanceMeters, 0.05)
        assertTrue(t.addFix(9000L, latPlus(11.4), lon0, 5f, 1.2f))      // the chain restarts at the accepted fix
        assertEquals(11.4, t.distanceMeters, 0.05)
    }

    @Test
    fun firstFixAnchorsWithoutDistance() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
        assertEquals(1, t.acceptedCount)
        assertEquals(5f, t.lastAccuracyM)
    }

    @Test
    fun stationaryJitterIsIgnored() {
        t.addFix(0L, lat0, lon0, 8f, 0f)
        assertFalse(t.addFix(1000L, latPlus(2.0), lon0, 8f, 0f))      // 2 m < max(8, 3)
        assertFalse(t.addFix(2000L, latPlus(6.0), lon0, 8f, 0.2f))    // 6 m < 8 m, still nearly stationary
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(0.0, t.speedMps, 0.0)
        assertEquals(2, t.rejectedJitterCount)
    }

    @Test
    fun thresholdIsAtLeastThreeMetresAndMeasuredFromTheAnchor() {
        t.addFix(0L, lat0, lon0, 1f, 0f)
        assertFalse(t.addFix(1000L, latPlus(2.0), lon0, 1f, 0f))      // 2 m < 3 m floor
        assertTrue(t.addFix(2000L, latPlus(3.5), lon0, 1f, 0f))       // 3.5 m from the anchor
        assertEquals(3.5, t.distanceMeters, 0.05)
    }

    @Test
    fun smallMoveIsAcceptedWhenMovingAndCreditedAtTheReceiverSpeed() {
        t.addFix(0L, lat0, lon0, 8f, 1.2f)
        assertTrue(t.addFix(1000L, latPlus(1.5), lon0, 8f, 1.2f))
        assertEquals(1.2, t.distanceMeters, 0.05)      // rule 4: Doppler 1.2 m/s × 1 s, not the 1.5 m position hop
        assertEquals(1.2, t.speedMps, 1e-6)
        assertEquals(2, t.acceptedCount)
    }

    @Test
    fun dopplerCreditIsCappedByThePositionHopPlusTheAccuracy() {
        // the receiver claims 10 m/s but the position barely moves: credit at most hop + accuracy, not 10 m
        t.addFix(0L, lat0, lon0, 4f, 10f)
        assertTrue(t.addFix(1000L, latPlus(1.0), lon0, 4f, 10f))
        assertEquals(5.0, t.distanceMeters, 0.05)
    }

    @Test
    fun positionHopIsUsedWhenFixesAreMoreThanFiveSecondsApart() {
        // 6 s between fixes: the speed may have changed in between, the hop is the better estimate
        t.addFix(0L, lat0, lon0, 5f, 2f)
        assertTrue(t.addFix(6000L, latPlus(15.0), lon0, 5f, 2f))
        assertEquals(15.0, t.distanceMeters, 0.05)
    }

    @Test
    fun oneHertzJitterAtRunningSpeedIsNotIntegrated() {
        // 3 m/s due north, every fix 2 m off the line alternately left and right (a 4 m lateral zigzag)
        var d = 0.0
        for (i in 0..100) {
            val side = if (i % 2 == 0) 2.0 else -2.0
            t.addFix(i * 1000L, latPlus(3.0 * i), lonPlus(side), 6f, 3.0f)
            d = t.distanceMeters
        }
        assertEquals(300.0, d, 0.5)          // the raw hops would sum to 100 × 5 m = 500 m
        assertEquals(101, t.acceptedCount)
    }

    @Test
    fun slowMoveBeyondTheRadiusAccumulates() {
        t.addFix(0L, lat0, lon0, 5f, 0f)
        assertTrue(t.addFix(10_000L, latPlus(25.0), lon0, 5f, 0f))
        assertEquals(25.0, t.distanceMeters, 0.1)
        assertEquals(2.5, t.speedMps, 0.01)   // no receiver speed: distance / time
    }

    @Test
    fun distanceIsTheHaversineSumBetweenAcceptedFixes() {
        // 100 m legs at a plausible 3.5 m/s (one fix every 30 s)
        t.addFix(0L, lat0, lon0, 5f, 3.5f)
        t.addFix(30_000L, latPlus(100.0), lon0, 5f, 3.5f)
        t.addFix(60_000L, latPlus(100.0), lonPlus(100.0), 5f, 3.5f)
        assertFalse(t.addFix(61_000L, latPlus(100.0), lonPlus(100.0), 61f, 3.5f))   // fix beyond the 60 m gate does not count
        assertEquals(200.0, t.distanceMeters, 0.5)
        assertEquals(3, t.acceptedCount)
    }

    @Test
    fun singleFixSpikeOffThePathIsRejected() {
        // walking 1.4 m/s at 1 Hz, accuracy 8 m; one multipath spike 25 m off-track, then back on the path
        for (i in 0..9) t.addFix(i * 1000L, latPlus(1.4 * i), lon0, 8f, 1.4f)
        val before = t.distanceMeters
        assertFalse(t.addFix(10_000L, latPlus(1.4 * 10), lonPlus(25.0), 8f, 1.4f))   // 25 m in 1 s = 25 m/s
        assertEquals(1, t.rejectedSpikeCount)
        assertTrue(t.addFix(11_000L, latPlus(1.4 * 11), lon0, 8f, 1.4f))
        assertEquals(before + 2.8, t.distanceMeters, 0.1)                            // 2 s of real walking, not 50 m
    }

    @Test
    fun standingStillWithASmallReportedSpeedAddsNothing() {
        // fused provider says 0.7 m/s (hand-held under trees) while the position scatters 2 m around the truth
        t.addFix(0L, lat0, lon0, 3f, 0.7f)
        var rejected = 0
        for (i in 1..60) {
            val dx = if (i % 2 == 0) 2.0 else -2.0
            if (!t.addFix(i * 1000L, latPlus(dx), lon0, 3f, 0.7f)) rejected++
        }
        assertEquals(60, rejected)
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
    }

    @Test
    fun anchorAccuracyCountsTowardsTheJitterRadius() {
        t.addFix(0L, lat0, lon0, 20f, 0f)                    // poor but accepted anchor
        assertFalse(t.addFix(1000L, latPlus(12.0), lon0, 4f, 0f))   // 12 m < 1.5 × max(4, 20): still inside the anchor's error
        assertFalse(t.addFix(2000L, latPlus(25.0), lon0, 4f, 0f))   // 25 m < 30 m: two 20 m-accuracy positions can differ by that
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertTrue(t.addFix(10_000L, latPlus(35.0), lon0, 4f, 0f))  // 35 m > 30 m, 3.5 m/s over 10 s: real movement
        assertEquals(35.0, t.distanceMeters, 0.1)
    }

    @Test
    fun paceComesFromTheLast30Seconds() {
        // 1.5 m/s walk, one fix per second for a minute: 30 s window = 45 m, well under 100 m
        for (i in 0..60) t.addFix(i * 1000L, latPlus(1.5 * i), lon0, 5f, 1.5f)
        val expected = 1000.0 / 1.5
        assertEquals(expected, t.paceSecPerKm, expected * 0.03)
        assertEquals(90.0, t.distanceMeters, 0.5)
        assertEquals(1.5, t.speedMps, 1e-6)
    }

    @Test
    fun paceUsesTheLast100MetresWhenFast() {
        // 5 m/s: 100 m are covered in 20 s, so the window is 100 m rather than 30 s
        for (i in 0..30) t.addFix(i * 1000L, latPlus(5.0 * i), lon0, 5f, 5f)
        assertEquals(200.0, t.paceSecPerKm, 6.0)
    }

    @Test
    fun paceIsUnknownForTheFirstFewMetres() {
        t.addFix(0L, lat0, lon0, 5f, 1f)
        t.addFix(1000L, latPlus(1.0), lon0, 5f, 1f)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
    }

    @Test
    fun paceDropsToZeroAfterStopping() {
        for (i in 0..30) t.addFix(i * 1000L, latPlus(1.5 * i), lon0, 5f, 1.5f)
        assertTrue(t.paceSecPerKm > 0.0)
        val stopLat = latPlus(45.0)
        for (i in 31..80) t.addFix(i * 1000L, stopLat, lon0, 5f, 0f)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
        assertEquals(0.0, t.speedMps, 0.0)
        assertEquals(45.0, t.distanceMeters, 0.5)
    }

    @Test
    fun outOfOrderFixIsRejected() {
        t.addFix(5000L, lat0, lon0, 5f, 1f)
        assertFalse(t.addFix(4000L, latPlus(50.0), lon0, 5f, 1f))
        assertEquals(0.0, t.distanceMeters, 0.0)
    }

    @Test
    fun resetClearsEverything() {
        t.addFix(0L, lat0, lon0, 5f, 1f)
        t.addFix(1000L, latPlus(10.0), lon0, 5f, 1f)
        t.reset()
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertEquals(0.0, t.speedMps, 0.0)
        assertEquals(0, t.acceptedCount)
        assertNull(t.lastAccuracyM)
        assertTrue(t.addFix(0L, latPlus(500.0), lon0, 5f, 1f))
        assertEquals(0.0, t.distanceMeters, 0.0)
    }

    @Test
    fun markGapCreditsTheIntegralOfAnOpenWait() {
        // a pause 10 s into a wait in 25 m accuracy at 3 m/s: the held fixes measured 27 m (9 intervals), credited at
        // the last held fix (capped by its 27 m hop + 25 m); the 10th second, between the last held fix and the pause,
        // is lost. A pause every 10 s in 25 m accuracy used to record nothing: the wait restarted every time
        for (i in 0..9) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 3f))
        t.markGap()
        assertEquals(27.0, t.distanceMeters, 0.05)
        assertTrue(t.addFix(20_000L, latPlus(60.0), lon0, 5f, 3f))     // re-anchors: the walk in between is not counted
        assertEquals(27.0, t.distanceMeters, 0.05)
        assertTrue(t.addFix(21_000L, latPlus(63.0), lon0, 5f, 3f))
        assertEquals(30.0, t.distanceMeters, 0.05)
        // a wait whose receiver reported nothing credits the hop from its first held fix only beyond the noise: 27 m is
        // inside the 37.5 m radius (see markGapCreditsTheHopOfAnOpenWaitWithoutDoppler)
        t.markGap()
        for (i in 30..39) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 25f, 0f))
        t.markGap()
        assertEquals(30.0, t.distanceMeters, 0.05)
    }

    @Test
    fun nanSpeedCountsAsZero() {
        // a NaN reported speed must not poison the integral (distance stayed NaN for the rest of the workout): it counts
        // as 0, i.e. as a dropout bridged with the last speed (rule 4c)
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 1.2f))
        assertFalse(t.addFix(1000L, latPlus(1.2), lon0, 5f, Float.NaN))     // reported as 0: jitter, bridged at 1.2 m/s
        assertTrue(t.addFix(2000L, latPlus(2.4), lon0, 5f, 1.2f))
        assertEquals(2.4, t.distanceMeters, 0.05)
        assertFalse(t.distanceMeters.isNaN())
        t.markGap()
        assertFalse(t.addFix(10_000L, latPlus(2.4), lon0, 25f, 1.2f))
        assertFalse(t.addFix(11_000L, latPlus(3.6), lon0, 25f, Float.NaN))  // during a wait, too
        assertTrue(t.addFix(12_000L, latPlus(4.8), lon0, 5f, 1.2f))
        assertEquals(2.4 + 2.4, t.distanceMeters, 0.05)                     // the two held seconds, the NaN one bridged
        assertEquals(1.2, t.speedMps, 1e-6)
    }

    @Test
    fun markGapKeepsDistanceButDropsTheAnchor() {
        t.addFix(0L, lat0, lon0, 5f, 1f)
        t.addFix(10_000L, latPlus(10.0), lon0, 5f, 1f)
        t.markGap()
        assertEquals(10.0, t.distanceMeters, 0.05)
        assertEquals(0.0, t.paceSecPerKm, 0.0)
        assertTrue(t.addFix(60_000L, latPlus(500.0), lon0, 5f, 1f))   // re-anchors: the walk in between is not counted
        assertEquals(10.0, t.distanceMeters, 0.05)
        assertTrue(t.addFix(70_000L, latPlus(505.0), lon0, 5f, 1f))
        assertEquals(15.0, t.distanceMeters, 0.05)
    }

    companion object {
        /** Metres per degree of latitude for the tracker's Earth radius (6 371 000 × π / 180). */
        const val DEG_LAT_M = 111_194.927
    }
}
