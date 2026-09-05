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
    fun waitCreditsTheIntegralWhenTheCurrentFixIsTheBestAtExpiry() {
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(3.0 * i), lon0, 30f, 3f))
        assertTrue(t.addFix(15_000L, latPlus(45.0), lon0, 25f, 3f))      // wait over, 25 m is the best seen: anchors here
        assertEquals(45.0, t.distanceMeters, 0.05)                        // 15 s × 3 m/s
        assertEquals(1, t.acceptedCount)
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
    fun waitExpiryNeverRejects_anImplausibleHopFromTheCandidateAnchorsTheCurrentFix() {
        // a 6 m/s cyclist with no Doppler speed and 25 m fixes: after the 15 s wait the current fix is 90 m from the
        // candidate, more than 2.5 × 15 + two radii, so it is a spike from there — it becomes the anchor instead
        // (nothing to credit without a receiver speed) and the ride is measured from it. Rejecting it deadlocked the
        // tracker: every later fix was farther still, 0.0 m after 20 minutes.
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(6.0 * i), lon0, 25f, 0f))
        assertTrue(t.addFix(15_000L, latPlus(90.0), lon0, 25f, 0f))
        assertEquals(1, t.rejectedSpikeCount)
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(0.0, t.distanceMeters, 0.0)
        for (i in 16..21) assertFalse(t.addFix(i * 1000L, latPlus(6.0 * i), lon0, 25f, 0f))   // 6..36 m: inside the 37.5 m radius
        assertTrue(t.addFix(22_000L, latPlus(132.0), lon0, 25f, 0f))                            // 42 m over 7 s
        assertEquals(42.0, t.distanceMeters, 0.1)
    }

    @Test
    fun waitExpiryOnASpikeCreditsNothingBeyondTheCappedIntegral() {
        // the same start with Doppler 6 m/s, but the fix at expiry is a real spike (200 m in 15 s > 12 m/s): it anchors,
        // the 90 m the receiver measured during the wait are credited, the 200 m hop is not
        for (i in 0..14) assertFalse(t.addFix(i * 1000L, latPlus(6.0 * i), lon0, 25f, 6f))
        assertTrue(t.addFix(15_000L, latPlus(200.0), lon0, 25f, 6f))
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(90.0, t.distanceMeters, 0.05)
    }

    // rule 3: plausibility uses the speed the tracker has measured, and spikes cannot go on for ever

    @Test
    fun theLastAcceptedSpeedKeepsARunnerWithoutDopplerPlausible() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertTrue(t.addFix(2000L, latPlus(12.0), lon0, 5f, 0f))          // 6 m/s over 2 s: 12 m ≤ 2.5 × 2 + 10
        assertFalse(t.addFix(3000L, latPlus(31.0), lon0, 5f, 0f))         // 19 m in 1 s: a spike (> 12 m/s)
        assertEquals(1, t.rejectedSpikeCount)
        // 18 m over 3 s: more than 2.5 × 3 + 10 = 17.5 (the old bound rejected it, and every later fix — dt grew
        // while the allowance grew 2.5 m per second and the runner 6), but within 6 × 3 + 10
        assertTrue(t.addFix(5000L, latPlus(30.0), lon0, 5f, 0f))
        assertEquals(30.0, t.distanceMeters, 0.05)
    }

    @Test
    fun threeConsistentSpikesReAnchorWithoutCredit() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertFalse(t.addFix(1000L, latPlus(2.0), lon0, 5f, 0f))          // jitter
        assertFalse(t.addFix(2000L, latPlus(16.0), lon0, 5f, 0f))         // 16 m > 2.5 × 2 + 10: spike; 14 m/s from the previous fix, not consistent
        assertFalse(t.addFix(3000L, latPlus(26.0), lon0, 5f, 0f))         // spike, 10 m/s from the previous fix (1)
        assertFalse(t.addFix(4000L, latPlus(36.0), lon0, 5f, 0f))         // spike (2)
        assertTrue(t.addFix(5000L, latPlus(46.0), lon0, 5f, 0f))          // (3): the fixes agree with each other, the anchor is stale — re-anchor here
        assertEquals(4, t.rejectedSpikeCount)
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(0.0, t.distanceMeters, 0.0)                           // the lost segment is lost, nothing is invented
        assertTrue(t.addFix(6000L, latPlus(56.0), lon0, 5f, 0f))          // and the walk is measured on from the new anchor
        assertEquals(10.0, t.distanceMeters, 0.05)
    }

    @Test
    fun tenSecondsOfSpikesReAnchorWithoutCredit() {
        assertTrue(t.addFix(0L, lat0, lon0, 5f, 0f))
        assertFalse(t.addFix(4000L, latPlus(100.0), lon0, 5f, 0f))        // 25 m/s: spike
        assertFalse(t.addFix(8000L, latPlus(150.0), lon0, 5f, 0f))        // 18.8 m/s from the anchor, 12.5 from the previous fix: spike, not consistent
        assertFalse(t.addFix(12_000L, latPlus(200.0), lon0, 5f, 0f))      // 16.7 m/s: spike, 8 s of spikes so far
        assertTrue(t.addFix(16_000L, latPlus(250.0), lon0, 5f, 0f))       // still a spike, but spikes for 12 s > 10 s: re-anchor here
        assertEquals(4, t.rejectedSpikeCount)                              // 4 spikes, the last one escaped
        assertEquals(1, t.escapedSpikeCount)
        assertEquals(0.0, t.distanceMeters, 0.0)
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
    fun firstFixWaitExpiresOnTheBestFixSeen() {
        // only 25–30 m fixes for 15 s: the best of them (25 m at t=5) becomes the anchor, not the first or the last
        assertFalse(t.addFix(0L, lat0, lon0, 30f, 0f))
        assertFalse(t.addFix(5000L, latPlus(10.0), lon0, 25f, 0f))
        assertFalse(t.addFix(10_000L, latPlus(20.0), lon0, 28f, 0f))
        assertEquals(3, t.deferredFirstFixCount)
        assertEquals(0, t.acceptedCount)
        assertFalse(t.addFix(15_000L, latPlus(30.0), lon0, 28f, 0f))   // wait over: anchor = t=5 fix; this one is 20 m < 37.5 m jitter
        assertEquals(3, t.deferredFirstFixCount)
        assertEquals(1, t.rejectedJitterCount)
        assertTrue(t.addFix(25_000L, latPlus(80.0), lon0, 5f, 0f))     // 70 m from the 25 m anchor (80 from t=0, 50 from t=15)
        assertEquals(70.0, t.distanceMeters, 0.5)
        assertEquals(1, t.acceptedCount)
    }

    @Test
    fun firstFixWaitExpiresOnTheCurrentFixWhenItIsTheBest() {
        assertFalse(t.addFix(0L, lat0, lon0, 30f, 0f))
        assertTrue(t.addFix(15_000L, latPlus(10.0), lon0, 22f, 0f))    // still > 20 m, but the wait is over and it is the best
        assertEquals(1, t.acceptedCount)
        assertEquals(0.0, t.distanceMeters, 0.0)
        assertTrue(t.addFix(35_000L, latPlus(60.0), lon0, 5f, 0f))     // 50 m from the t=15 anchor
        assertEquals(50.0, t.distanceMeters, 0.5)
    }

    @Test
    fun aMuchBetterFixInsideAPoorStartingAnchorsRadiusReplacesItWithoutCredit() {
        // no Doppler, standing at the start. Only 28–30 m fixes for 15 s, so the wait expires on the 28 m fix (an
        // uncredited starting anchor); the next fix is 5 m and lands 20 m from it — inside its radius, 5.6 × better —
        // so it becomes the anchor with no credit (rule 1c): the 20 m are the poor anchor's scatter, not a walk.
        // The slow walk that follows is then measured from the good position with the 5 m radius.
        assertFalse(t.addFix(0L, lat0, lonPlus(20.0), 30f, 0f))
        assertTrue(t.addFix(15_000L, latPlus(0.0), lonPlus(20.0), 28f, 0f))
        assertEquals(1, t.acceptedCount)
        assertTrue(t.addFix(16_000L, lat0, lon0, 5f, 0f))
        assertEquals(1, t.reAnchoredCount)
        assertEquals(0.0, t.distanceMeters, 0.0)                        // the 20 m back to the path is not distance
        assertTrue(t.addFix(26_000L, latPlus(10.0), lon0, 5f, 0f))     // 10 m > 7.5 m radius of the new anchor
        assertEquals(10.0, t.distanceMeters, 0.1)                       // with the 28 m anchor this was 22.4 m of "jitter"
        assertEquals(1, t.reAnchoredCount)
    }

    @Test
    fun aMuchBetterFixInsideAPoorStartingAnchorsRadiusCreditsTheDopplerIntegral() {
        // the same start, but the receiver says the walker was moving at 1.2 m/s: the poor anchor's position is
        // still not trusted (it is replaced), but the Doppler integral since it is credited, like rule 4 would
        assertFalse(t.addFix(0L, lat0, lonPlus(20.0), 30f, 1.2f))
        assertTrue(t.addFix(15_000L, latPlus(18.0), lonPlus(20.0), 28f, 1.2f))    // wait over: this fix anchors, integral restarts
        assertTrue(t.addFix(16_000L, latPlus(19.2), lon0, 5f, 1.2f))              // 20 m away, 5.6 × better: replaces it
        assertEquals(1, t.reAnchoredCount)
        assertEquals(1.2, t.distanceMeters, 0.05)                                 // 1.2 m/s × 1 s, not 0 and not 20 m
        assertTrue(t.addFix(17_000L, latPlus(20.4), lon0, 5f, 1.2f))
        assertEquals(2.4, t.distanceMeters, 0.05)
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
