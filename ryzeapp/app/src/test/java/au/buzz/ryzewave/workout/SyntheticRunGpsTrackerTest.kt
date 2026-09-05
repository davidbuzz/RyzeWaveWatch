package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Feeds [DefaultGpsDistanceTracker] (the real class, default thresholds) a synthetic 20-minute run and checks
 * the accumulated distance against the 3600 m truth. Every variant prints its measured numbers so the
 * behaviour of the ones the tracker is *designed* to reject (accuracy 80 m > maxAccuracyM 60 m) is reported
 * rather than hidden.
 */
class SyntheticRunGpsTrackerTest {

    private class Result(val name: String, val t: DefaultGpsDistanceTracker, val fixes: Int, val finalPace: Double, val finalSpeed: Double, val minPaceAfter60s: Double, val zeroPaceTicksAfter60s: Int, val truthM: Double) {
        val errorPct get() = (t.distanceMeters - truthM) / truthM * 100.0
        override fun toString() = "[$name] fixes=$fixes accepted=${t.acceptedCount} rejAccuracy=${t.rejectedAccuracyCount} " +
            "rejJitter=${t.rejectedJitterCount} rejSpike=${t.rejectedSpikeCount} escaped=${t.escapedSpikeCount} deferredFirst=${t.deferredFirstFixCount} reAnchored=${t.reAnchoredCount} distance=%.1f m (truth %.1f m, error %.2f%%) ".format(t.distanceMeters, truthM, errorPct) +
            "finalPace=%.1f s/km finalSpeed=%.2f m/s minPaceAfter60s=%.1f zeroPaceFixesAfter60s=%d".format(finalPace, finalSpeed, minPaceAfter60s, zeroPaceTicksAfter60s)
    }

    private fun run(name: String, fixes: List<SyntheticFix>, truthM: Double = SyntheticRun.TRUE_DISTANCE_M, gapAt: Long = -1L, gapEveryMs: Long = 0L): Result {
        val t = DefaultGpsDistanceTracker()
        var minPace = Double.MAX_VALUE
        var zeroPace = 0
        for (f in fixes) {
            // a pause/resume, as the controller does — at one fix, or on a cadence (including the first fix, a no-op)
            if (f.time == gapAt || (gapEveryMs > 0L && (f.time - SyntheticRun.T0) % gapEveryMs == 0L)) t.markGap()
            t.addFix(f.time, f.lat, f.lon, f.accuracyM, f.speedMps)
            if (f.time - SyntheticRun.T0 >= 60_000L) {
                if (t.paceSecPerKm == 0.0) zeroPace++ else minPace = minOf(minPace, t.paceSecPerKm)
            }
        }
        val r = Result(name, t, fixes.size, t.paceSecPerKm, t.speedMps, if (minPace == Double.MAX_VALUE) 0.0 else minPace, zeroPace, truthM)
        t.markGap()      // the controller's stop() flushes a first-anchor wait still open at the end
        println("SYNTHETIC_RUN $r")
        return r
    }

    private fun assertWithin5Percent(r: Result) {
        assertTrue("$r: expected within 5% of 3600 m", abs(r.errorPct) <= 5.0)
    }

    /** Variant A: accuracy 6 m, Doppler 3.0 m/s: the normal outdoor case, must be within 5 %. */
    @Test
    fun accuracy6mWithDopplerIsWithin5Percent() {
        val r = run("A accuracy=6m doppler=3.0", SyntheticRun.fixes(accuracyM = 6f, dopplerSpeedMps = 3.0f))
        assertEquals(1200, r.t.acceptedCount)                       // nothing rejected on a clean run
        assertWithin5Percent(r)
        // pace: 3.0 m/s = 333.3 s/km; the rolling window should settle close to that and never show 0 once warm
        assertTrue("pace after 60 s never 0 but was 0 on ${r.zeroPaceTicksAfter60s} fixes", r.zeroPaceTicksAfter60s == 0)
        assertEquals(333.3, r.finalPace, 25.0)
    }

    /**
     * Variant B: accuracy 25 m with Doppler speed — a run under trees. maxAccuracyM used to be 20 m, so this
     * run recorded NOTHING (the vendor's 0.0 km symptom); with the 60 m gate every fix is accepted and rule 4
     * credits speed × dt (capped at hop + accuracy), so the distance is within 5 % (measured: −0.08 %).
     */
    @Test
    fun accuracy25mWithDopplerIsWithin5Percent() {
        val r = run("B accuracy=25m doppler=3.0", SyntheticRun.fixes(accuracyM = 25f, dopplerSpeedMps = 3.0f))
        assertEquals(0, r.t.rejectedAccuracyCount)
        // rule 1b: 25 m is too poor for a first anchor, so the first 15 s are held (the t=0 fix is the best of them
        // and becomes the anchor when the wait runs out); the Doppler integral over the held fixes keeps the distance
        assertEquals(15, r.t.deferredFirstFixCount)
        assertEquals(1185, r.t.acceptedCount)
        assertWithin5Percent(r)
        assertTrue("pace after 60 s never 0 but was 0 on ${r.zeroPaceTicksAfter60s} fixes", r.zeroPaceTicksAfter60s == 0)
        assertEquals(333.3, r.finalPace, 25.0)
    }

    /** Variant B2: accuracy 35 m with Doppler, the worst signal we still expect to be accurate: within 5 %. */
    @Test
    fun accuracy35mWithDopplerIsWithin5Percent() {
        val r = run("B2 accuracy=35m doppler=3.0", SyntheticRun.fixes(accuracyM = 35f, dopplerSpeedMps = 3.0f))
        assertEquals(0, r.t.rejectedAccuracyCount)
        assertEquals(15, r.t.deferredFirstFixCount)
        assertEquals(1185, r.t.acceptedCount)
        assertWithin5Percent(r)
        assertEquals(333.3, r.finalPace, 25.0)
    }

    /**
     * Variant B3: accuracy 25 m and NO Doppler speed. Rule 2 absorbs every hop shorter than 1.5 × 25 = 37.5 m
     * as jitter, so the distance accumulates in ~38 m hops (about 95 of them) and only the unfinished last hop
     * is lost: coarse but non-zero, must be > 85 % of the truth. The numbers are printed.
     */
    @Test
    fun accuracy25mWithoutDopplerIsCoarseButAbove85Percent() {
        val r = run("B3 accuracy=25m doppler=0.0", SyntheticRun.fixes(accuracyM = 25f, dopplerSpeedMps = 0.0f))
        assertEquals(0, r.t.rejectedAccuracyCount)
        assertTrue("some fixes must be absorbed as jitter", r.t.rejectedJitterCount > 0)
        assertTrue("$r: expected > 85 % of 3600 m", r.t.distanceMeters > 0.85 * SyntheticRun.TRUE_DISTANCE_M)
        assertTrue("$r: expected within 15 % of 3600 m", abs(r.errorPct) <= 15.0)
    }

    /**
     * Variant B4: accuracy 80 m, beyond the 60 m gate: reported only. Every fix is dropped (rule 1), the run
     * records nothing — the same as the old behaviour for 25 m, now reserved for fixes that are worse than a
     * stride estimate.
     */
    @Test
    fun accuracy80mIsRejected_reported() {
        for (doppler in floatArrayOf(3.0f, 0.0f)) {
            val r = run("B4 accuracy=80m doppler=$doppler", SyntheticRun.fixes(accuracyM = 80f, dopplerSpeedMps = doppler))
            assertEquals(1200, r.t.rejectedAccuracyCount)
            assertEquals(0, r.t.acceptedCount)
            assertEquals(0.0, r.t.distanceMeters, 0.0)
        }
    }

    /**
     * Variant C: accuracy 8 m, receiver reports no speed (0.0). Rule 2 (reported < 1.0 m/s and
     * d < max(1.5 × max(accuracy, anchorAccuracy), 3 m)) treats every fix closer than 12 m to the anchor as
     * jitter; the anchor stays, so the distance accumulates in >= 12 m hops (about 3600 / 13.5 = 270 of them).
     * The 5 % band is asserted because the class KDoc promises slow real movement still accumulates; the
     * numbers are printed either way. (With the old radius of 1 × accuracy = 8 m the hops averaged 9.6 m and
     * the run came out 5.19 % long.)
     */
    @Test
    fun accuracy8mWithoutDopplerAccumulatesInHops() {
        val r = run("C accuracy=8m doppler=0.0", SyntheticRun.fixes(accuracyM = 8f, dopplerSpeedMps = 0.0f))
        assertTrue("some fixes must be absorbed as jitter", r.t.rejectedJitterCount > 0)
        assertTrue("accepted ${r.t.acceptedCount}", r.t.acceptedCount in 200..400)
        assertWithin5Percent(r)
    }

    /**
     * Variant D: accuracy 6 m, Doppler 3.0, but fixes 600..629 (30 s) are missing. markGap() is deliberately
     * not called for an outage (lines 148-153): the first fix after the gap is 90 m from the anchor over 30 s,
     * implied 3 m/s < maxSpeedMps 12 and 90 m < allowed = max(3.0, 2.5) * 30 + 6 + 6 = 102 m (line 105), so
     * the straight line bridges the gap and the distance stays within 5 %.
     */
    @Test
    fun thirtySecondGapIsBridgedWithin5Percent() {
        val r = run("D accuracy=6m doppler=3.0 gap=600..629s", SyntheticRun.fixes(accuracyM = 6f, dopplerSpeedMps = 3.0f, gapFromS = 600, gapLengthS = 30))
        assertEquals(1170, r.fixes)
        assertEquals(1170, r.t.acceptedCount)
        assertEquals(0, r.t.rejectedSpikeCount)
        assertWithin5Percent(r)
    }

    /**
     * Sensitivity of variant A to the jitter model: independent per-fix noise of sigma 0 / 0.5 / 1 / 2 / 3 m, and
     * temporally correlated noise (AR(1) phi = 0.9 / 0.98, stationary sigma 2 m) as a Kalman-smoothed provider
     * would give. Reported only (printed), no band asserted: the point is to show how much of the error is the
     * noise model. The exact 0-jitter run must be within 0.1 %.
     */
    @Test
    fun jitterSensitivityReported() {
        val exact = run("A0 accuracy=6m doppler=3.0 jitter=0", SyntheticRun.fixes(6f, 3.0f, jitterSigmaM = 0.0))
        assertEquals(3600.0, exact.t.distanceMeters, 3.6)
        for (sigma in doubleArrayOf(0.5, 1.0, 2.0, 3.0)) {
            run("A independent jitter sigma=$sigma m", SyntheticRun.fixes(6f, 3.0f, jitterSigmaM = sigma))
            run("C independent jitter sigma=$sigma m doppler=0 acc=8", SyntheticRun.fixes(8f, 0.0f, jitterSigmaM = sigma))
        }
        for (phi in doubleArrayOf(0.9, 0.98)) {
            run("A correlated jitter phi=$phi sigma=2 m", SyntheticRun.fixes(6f, 3.0f, jitterSigmaM = 2.0, phi = phi))
            run("C correlated jitter phi=$phi sigma=2 m doppler=0 acc=8", SyntheticRun.fixes(8f, 0.0f, jitterSigmaM = 2.0, phi = phi))
        }
        for (seed in longArrayOf(1L, 7L, 99L)) {
            run("A seed=$seed independent sigma=2 m", SyntheticRun.fixes(6f, 3.0f, seed = seed))
        }
        // poor-signal variants: the noise a 25–35 m accuracy receiver actually produces is larger than 2 m
        for (sigma in doubleArrayOf(2.0, 5.0, 10.0)) {
            run("B accuracy=25m doppler=3.0 sigma=$sigma m", SyntheticRun.fixes(25f, 3.0f, jitterSigmaM = sigma))
            run("B2 accuracy=35m doppler=3.0 sigma=$sigma m", SyntheticRun.fixes(35f, 3.0f, jitterSigmaM = sigma))
            run("B3 accuracy=25m doppler=0 sigma=$sigma m", SyntheticRun.fixes(25f, 0.0f, jitterSigmaM = sigma))
            run("B3' accuracy=35m doppler=0 sigma=$sigma m", SyntheticRun.fixes(35f, 0.0f, jitterSigmaM = sigma))
        }
    }

    /** Extra: what a slightly longer outage does (the plausibility rule 3 is the limit, not the gap length). */
    @Test
    fun longerGapsReported() {
        for (gap in intArrayOf(60, 120, 300)) {
            val r = run("D' accuracy=6m doppler=3.0 gap=600..${600 + gap - 1}s", SyntheticRun.fixes(accuracyM = 6f, dopplerSpeedMps = 3.0f, gapFromS = 600, gapLengthS = gap))
            assertEquals(0, r.t.rejectedSpikeCount)
            assertWithin5Percent(r)
        }
    }

    /**
     * Variant E: a phone whose accuracy steps between bands mid-run — every 10th fix at 8 m on a 25 m run, and
     * 30 / 9 m alternating every fix — with Doppler. Rule 1c (re-anchor without credit) used to fire on every
     * better fix inside the poor anchor's radius and drop the Doppler integral since the anchor: 3597 → 3225 m
     * (−10.3 %) and 3597 → 1797 m (−50.0 %). Restricted to an uncredited starting anchor it fires 0 times here
     * (the t=0 fix is ≤ 20 m, so it anchors at once, and every later anchor has credited a hop) and both runs
     * stay within 1 % of the 3600 m truth like the constant-accuracy run.
     */
    @Test
    fun accuracySteppingBetweenBandsWithDopplerIsWithin1Percent() {
        val tenth = run("E 25m, every 10th fix 8m, doppler=3.0", SyntheticRun.fixes(25f, 3.0f, accuracyAt = { i -> if (i % 10 == 0) 8f else 25f }))
        assertEquals(0, tenth.t.reAnchoredCount)
        assertEquals(1200, tenth.t.acceptedCount)
        assertTrue("$tenth: expected within 1 % of 3600 m", abs(tenth.errorPct) <= 1.0)
        val alternating = run("E 30/9m alternating, doppler=3.0", SyntheticRun.fixes(30f, 3.0f, accuracyAt = { i -> if (i % 2 == 0) 9f else 30f }))
        assertEquals(0, alternating.t.reAnchoredCount)
        assertEquals(1200, alternating.t.acceptedCount)
        assertTrue("$alternating: expected within 1 % of 3600 m", abs(alternating.errorPct) <= 1.0)
        // the same bands starting on the poor fix: 15 s held, then the t=0 fix anchors (uncredited, 25 m) and the
        // first 8 m fix after the wait lands 45 m away — outside its radius — so rule 1c still has nothing to do
        val poorFirst = run("E 25m, every 10th fix 8m from t=5, doppler=3.0", SyntheticRun.fixes(25f, 3.0f, accuracyAt = { i -> if (i % 10 == 5) 8f else 25f }))
        assertEquals(0, poorFirst.t.reAnchoredCount)
        assertTrue("$poorFirst: expected within 1 % of 3600 m", abs(poorFirst.errorPct) <= 1.0)
    }

    /**
     * Variant E2: a 1.2 m/s walk (1440 m truth) with the accuracy stepping 25 ↔ 8 m every 5 s, Doppler 1.2 m/s.
     * The unrestricted rule 1c lost one 1.2 m interval at each of the 120 steps down to 8 m (1438.8 → 1290.0 m,
     * −10.3 %); now within 1 %.
     */
    @Test
    fun walkWithAccuracyBandsEveryFiveSecondsIsWithin1Percent() {
        val truth = 1.2 * (SyntheticRun.DURATION_S - 1)
        val fixes = SyntheticRun.fixes(25f, 1.2f, speedMps = 1.2, accuracyAt = { i -> if ((i / 5) % 2 == 0) 25f else 8f })
        val r = run("E2 walk 1.2 m/s, 25<->8m every 5s, doppler=1.2", fixes, truthM = truth)
        assertEquals(0, r.t.reAnchoredCount)
        assertTrue("$r: expected within 1 % of ${"%.1f".format(truth)} m", abs(r.errorPct) <= 1.0)
    }

    /**
     * Variant E3: the same accuracy bands with NO Doppler (hop mode). Reported, with a loose band: hop mode
     * measures from whatever the anchor's accuracy happens to be, so a varying accuracy changes the hop lengths
     * (≈ ±1.5 % against the constant-accuracy run), while the unrestricted rule 1c lost up to an anchor radius
     * per re-anchor. Rule 1c must not fire mid-run here either.
     */
    @Test
    fun accuracySteppingBetweenBandsWithoutDopplerReported() {
        val tenth = run("E3 25m, every 10th fix 8m, doppler=0", SyntheticRun.fixes(25f, 0.0f, accuracyAt = { i -> if (i % 10 == 0) 8f else 25f }))
        val alternating = run("E3 30/9m alternating, doppler=0", SyntheticRun.fixes(30f, 0.0f, accuracyAt = { i -> if (i % 2 == 0) 9f else 30f }))
        for (r in listOf(tenth, alternating)) {
            assertEquals(0, r.t.reAnchoredCount)
            assertTrue("$r: expected within 5 % of 3600 m", abs(r.errorPct) <= 5.0)
        }
    }

    /**
     * Regression scenarios from the reviews of the tracker (docs/APP.md "Tracker refinements"). The reviews
     * replayed the working tree against the baseline tracker (git 1c28d2e, tracker last changed in b40739e) and found
     * regressions; the fixed numbers are asserted here against the truth (the baseline itself cannot be part of a
     * permanent test).
     *
     * F1: the first-anchor wait (rule 1b) dropped the Doppler integral on three of its four exits — a ≤ 20 m fix
     * arriving mid-wait, a better candidate mid-wait, the current fix being the best at expiry — losing up to
     * (15 s + one interval) × speed at every start and every resume after a pause: −45 m at 3 m/s, −18 m at 1.2 m/s
     * (−4.7 % of a 5-minute walk). Now every exit credits the capped integral since the first held fix (at expiry
     * the best fix held before the current one anchors and the current fix is accepted from it with the credit), and
     * each of these runs equals the constant-accuracy one (−0.08 %: the very first interval of the run has no fix
     * before it).
     */
    @Test
    fun firstAnchorWaitCreditsTheMovementOnEveryExit() {
        for ((speed, label) in listOf(3.0 to "run 3.0", 1.2 to "walk 1.2")) {
            val truth = speed * (SyntheticRun.DURATION_S - 1)
            val sp = speed.toFloat()
            fun check(r: Result, deferred: Int) {
                assertEquals("$r", deferred, r.t.deferredFirstFixCount)
                assertTrue("$r: expected within 0.5 % of ${"%.1f".format(truth)} m", abs(r.errorPct) <= 0.5)
            }
            check(run("F1a $label, 25 m then a 15 m fix at t=10 (decent fix arrives)", SyntheticRun.fixes(25f, sp, speedMps = speed, accuracyAt = { i -> if (i < 10) 25f else 15f }), truth), 10)
            check(run("F1b $label, 30 m, better candidate 25 m at t=5, 28 m from t=15 (candidate anchors at expiry)", SyntheticRun.fixes(30f, sp, speedMps = speed, accuracyAt = { i -> if (i < 5) 30f else if (i < 15) 25f else 28f }), truth), 15)
            check(run("F1b' $label, better candidate at t=14", SyntheticRun.fixes(30f, sp, speedMps = speed, accuracyAt = { i -> if (i < 14) 30f else if (i < 15) 25f else 28f }), truth), 15)
            check(run("F1c $label, 30 m for 15 s then 25 m (better current fix at expiry, accepted from the candidate)", SyntheticRun.fixes(30f, sp, speedMps = speed, accuracyAt = { i -> if (i < 15) 30f else 25f }), truth), 15)
            check(run("F1d $label, 25 m constant (candidate best at expiry)", SyntheticRun.fixes(25f, sp, speedMps = speed), truth), 15)
            check(run("F1e $label, 25 m, pause/resume at t=600", SyntheticRun.fixes(25f, sp, speedMps = speed), truth, gapAt = SyntheticRun.T0 + 600_000L), 30)
            check(run("F1f $label, 25 m, pause/resume at t=600, 15 m fixes from t=610", SyntheticRun.fixes(25f, sp, speedMps = speed, accuracyAt = { i -> if (i in 610..1199) 15f else 25f }), truth, gapAt = SyntheticRun.T0 + 600_000L), 25)
            check(run("F1g $label, 50 m first fix then 15 m", SyntheticRun.fixes(50f, sp, speedMps = speed, accuracyAt = { i -> if (i == 0) 50f else 15f }), truth), 1)
            check(run("F1h $label, accuracy descending 40..26 m over the wait then 25 m", SyntheticRun.fixes(25f, sp, speedMps = speed, accuracyAt = { i -> if (i < 15) (40 - i).toFloat() else 25f }), truth), 15)
        }
    }

    /**
     * F1 in hop mode (no Doppler): a speed-less receiver has no integral to credit, so the wait must not lose the
     * movement the hops would have measured from the first fix. Anchoring on the current fix at expiry ("30 m for
     * 15 s then 25 m") lost the 15 s hop: −1.33 % at 3 m/s, −1.54 % at 6 m/s against the baseline's −0.05 / −0.23 %;
     * anchoring on the best fix held *before* the current one gives the baseline's figures. The third review then
     * found the other exits losing the wait too: a 19 m fix 14 s into a 25 m start, a better candidate at t=14, a
     * 21 m fix at t=14 only, accuracy descending 40..26 m then 25 m (all −1.1 %), and a resume in 25 m with 15 m
     * fixes 14 s later compounding per resume (−4.7 % with a pause every 5 minutes). Now the hop from the first held
     * fix to the exit fix / candidate is credited when it is beyond the jitter radius (capped by the raw chain), and
     * when it is inside the noise the first held fix anchors instead (unless the exit fix is 3 × better) — the
     * baseline's own behaviour — so every row is within 1 % of the truth and of the baseline.
     */
    @Test
    fun firstAnchorWaitInHopModeMeasuresFromTheBestEarlierFix() {
        for (speed in doubleArrayOf(3.0, 6.0)) {
            val truth = speed * (SyntheticRun.DURATION_S - 1)
            val stepped = run("F1c hop $speed m/s, 30 m for 15 s then 25 m", SyntheticRun.fixes(30f, 0f, speedMps = speed, accuracyAt = { i -> if (i < 15) 30f else 25f }), truth)
            assertTrue("$stepped: expected within 0.5 % of ${"%.1f".format(truth)} m", abs(stepped.errorPct) <= 0.5)
            val constant = run("F1d hop $speed m/s, 25 m constant", SyntheticRun.fixes(25f, 0f, speedMps = speed), truth)
            assertTrue("$constant: expected within 0.5 %", abs(constant.errorPct) <= 0.5)
            val paused = run("F1e hop $speed m/s, 25 m, pause/resume at t=600", SyntheticRun.fixes(25f, 0f, speedMps = speed), truth, gapAt = SyntheticRun.T0 + 600_000L)
            assertTrue("$paused: expected within 1 %", abs(paused.errorPct) <= 1.0)
            val resumedInto15 = run("F1f hop $speed m/s, 25 m, pause/resume at t=600, 15 m fixes from t=610", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i in 610..1199) 15f else 25f }), truth, gapAt = SyntheticRun.T0 + 600_000L)
            assertTrue("$resumedInto15: expected within 1 %", abs(resumedInto15.errorPct) <= 1.0)
            val descending = run("F1h hop $speed m/s, accuracy descending 40..26 m then 25 m", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i < 15) (40 - i).toFloat() else 25f }), truth)
            assertTrue("$descending: expected within 1 %", abs(descending.errorPct) <= 1.0)
            val decentAt14 = run("F1a' hop $speed m/s, 25 m then 19 m at t=14", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i < 14) 25f else 19f }), truth)
            assertTrue("$decentAt14: expected within 1 %", abs(decentAt14.errorPct) <= 1.0)
            val candidateAt14 = run("F1b' hop $speed m/s, 30 m, better candidate 25 m at t=14, 28 m from t=15", SyntheticRun.fixes(30f, 0f, speedMps = speed, accuracyAt = { i -> if (i < 14) 30f else if (i < 15) 25f else 28f }), truth)
            assertTrue("$candidateAt14: expected within 1 %", abs(candidateAt14.errorPct) <= 1.0)
            val onlyAt14 = run("F1b'' hop $speed m/s, 25 m, 21 m at t=14 only", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i == 14) 21f else 25f }), truth)
            assertTrue("$onlyAt14: expected within 1 %", abs(onlyAt14.errorPct) <= 1.0)
            val resumes = run("F1f' hop $speed m/s, 25 m, pause every 300 s, 15 m fixes from 14 s after each resume", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i % 300 < 14) 25f else 15f }), truth, gapEveryMs = 300_000L)
            assertTrue("$resumes: expected within 1 %", abs(resumes.errorPct) <= 1.0)
        }
        // the 1.2 m/s walk: a hop of 14 × 1.2 m is inside the 25 m jitter radius, so the first held fix anchors and the
        // walk is measured from it — the baseline's figures (−0.1 to −2.7 %), reported
        val truthWalk = 1.2 * (SyntheticRun.DURATION_S - 1)
        run("F1a' hop 1.2 m/s, 25 m then 19 m at t=14", SyntheticRun.fixes(25f, 0f, speedMps = 1.2, accuracyAt = { i -> if (i < 14) 25f else 19f }), truthWalk)
        run("F1b' hop 1.2 m/s, better candidate at t=14", SyntheticRun.fixes(30f, 0f, speedMps = 1.2, accuracyAt = { i -> if (i < 14) 30f else if (i < 15) 25f else 28f }), truthWalk)
    }

    /**
     * Hop-mode (no Doppler) pauses and stops in 25 m accuracy: a pause cadence shorter than the 15 s wait recorded
     * nothing (every resume started a wait the next pause cleared), and a workout stopped inside the wait lost it.
     * markGap() — called by the controller at a pause and at the stop — now credits the hop from the first held fix
     * to the last one when it is beyond the jitter radius: at 3 m/s a 14 / 15 s cadence is at least 90 % of the
     * baseline's 2543 / 3070 m, at 6 m/s 10 / 14 / 15 s at least 90 % of 4935 / 5128 / 6352 m (the baseline anchored
     * at once and accepted the first fix beyond the radius). A 10 s cadence at 3 m/s is still 0: 27 m is inside the
     * 37.5 m radius, as for the baseline. Stopping a 25 m Doppler run after 5 / 10 / 14 s keeps the seconds run.
     */
    @Test
    fun hopModePauseCadenceAndEarlyStopKeepTheWaitsMovement() {
        for ((s, floor) in listOf(14 to 2289.0, 15 to 2763.0)) {
            val r = run("F6' 25 m no Doppler 3 m/s, markGap every $s s", SyntheticRun.fixes(25f, 0f), gapEveryMs = s * 1000L)
            assertTrue("$r: expected at least $floor m (90 % of the baseline)", r.t.distanceMeters >= floor)
        }
        for ((s, floor) in listOf(10 to 4441.0, 14 to 4615.0, 15 to 5717.0)) {
            val r = run("F6' 25 m no Doppler 6 m/s, markGap every $s s", SyntheticRun.fixes(25f, 0f, speedMps = 6.0), 6.0 * (SyntheticRun.DURATION_S - 1), gapEveryMs = s * 1000L)
            assertTrue("$r: expected at least $floor m (90 % of the baseline)", r.t.distanceMeters >= floor)
        }
        run("F6' 25 m no Doppler 3 m/s, markGap every 10 s (27 m hops inside the radius: 0, as the baseline)", SyntheticRun.fixes(25f, 0f), gapEveryMs = 10_000L)
        for (s in intArrayOf(5, 10, 14)) {
            val stopped = run("F6'' 25 m + Doppler 3 m/s, stopped after $s fixes", SyntheticRun.fixes(25f, 3.0f).take(s), 3.0 * (s - 1))
            assertEquals("$stopped", 3.0 * (s - 1), stopped.t.distanceMeters, 0.05)
        }
        for (s in intArrayOf(10, 14)) {
            val stopped = run("F6'' 25 m no Doppler 6 m/s, stopped after $s fixes", SyntheticRun.fixes(25f, 0f, speedMps = 6.0).take(s), 6.0 * (s - 1))
            assertTrue("$stopped: expected at least the baseline's 44.7 m", stopped.t.distanceMeters >= 44.7)
        }
    }

    /**
     * F2: a receiver with no Doppler speed at running / cycling pace. Two deadlocks: (a) with poor accuracy the
     * wait pushed the first judged hop to dt = 15 s, where rule 3 allowed only 2.5 × 15 + two radii — a 6 m/s
     * rider is 90 m away, the hop was rejected, and every later fix was farther still (0.0 m after 20 minutes);
     * (b) at 5 m accuracy a 6 m/s hop is 12 m over 2 s against an allowance of 15 m, position noise rejected one
     * hop in seven, and after a rejection the allowance grew 2.5 m per second while the rider moved 6 (baseline:
     * 851 of 7194 m at 6 m/s, 54 of 8393 m at 7 m/s, 14 m at 8 m/s, 0 at 10 m/s). Now (a) the wait's raw chain seeds
     * the bound at expiry, so the 90 m hop is accepted and the 21–30 m runs equal the baseline (within 1 %); (b) three
     * consecutive spikes that agree with each other while the raw chain is consistent re-anchor crediting the chain
     * (the path the fixes drew), so the 5 m runs are measured mostly by that path: +4.85 % at 6 m/s, +5.3 % at 7 m/s,
     * +4.7 % at 5 m/s — hop mode's own over-count of 1 s hops with 2 m independent noise (the baseline's 4 m/s run at
     * 5 m, which never deadlocked, is +4.95 % with the same noise), hence the 6 % bound; with Kalman-like (phi 0.9)
     * noise they are within 0.5 %. A hop-derived speed is deliberately *not* fed back into the bound (it was in an
     * earlier draft: +3.1 % here but +0.9–1.6 % over the baseline on every no-Doppler run and phantom hops from
     * multipath bursts, see multipathBurstsAreRiddenOutAsSpikes).
     */
    @Test
    fun speedlessReceiverAtRunningAndCyclingPaceDoesNotDeadlock() {
        fun bound(acc: Float) = when { acc <= 5f -> 6.0; acc <= 8f -> 2.0; else -> 1.0 }
        for (speed in doubleArrayOf(6.0, 7.0)) {
            val truth = speed * (SyntheticRun.DURATION_S - 1)
            for (acc in floatArrayOf(5f, 8f, 21f, 25f, 30f)) {
                val r = run("F2 $speed m/s no Doppler acc=$acc", SyntheticRun.fixes(acc, 0.0f, speedMps = speed), truth)
                assertTrue("$r: expected within ${bound(acc)} % of ${"%.1f".format(truth)} m", abs(r.errorPct) <= bound(acc))
                val poorStart = run("F2 $speed m/s no Doppler acc=$acc, 40 m for the first 20 s", SyntheticRun.fixes(acc, 0.0f, speedMps = speed, accuracyAt = { i -> if (i < 20) 40f else acc }), truth)
                assertTrue("$poorStart: expected within ${bound(acc)} % of ${"%.1f".format(truth)} m", abs(poorStart.errorPct) <= bound(acc))
            }
            val smooth = run("F2 $speed m/s no Doppler acc=5, phi=0.9 noise", SyntheticRun.fixes(5f, 0.0f, speedMps = speed, phi = 0.9), truth)
            assertTrue("$smooth: expected within 1 %", abs(smooth.errorPct) <= 1.0)
            val doppler = run("F2 $speed m/s Doppler acc=25", SyntheticRun.fixes(25f, speed.toFloat(), speedMps = speed), truth)
            assertTrue("$doppler: expected within 0.5 %", abs(doppler.errorPct) <= 0.5)
        }
        for (speed in doubleArrayOf(5.0, 8.0, 10.0)) {
            val truth = speed * (SyntheticRun.DURATION_S - 1)
            val poor = run("F2 $speed m/s no Doppler acc=25", SyntheticRun.fixes(25f, 0.0f, speedMps = speed), truth)
            assertTrue("$poor: expected within 1 %", abs(poor.errorPct) <= 1.0)
            val good = run("F2 $speed m/s no Doppler acc=5", SyntheticRun.fixes(5f, 0.0f, speedMps = speed), truth)
            assertTrue("$good: expected within 6 %", abs(good.errorPct) <= 6.0)
        }
        run("F2 4 m/s no Doppler acc=5 (hop mode's own over-count, unchanged since build 4)", SyntheticRun.fixes(5f, 0.0f, speedMps = 4.0), 4.0 * (SyntheticRun.DURATION_S - 1))
        // known limitation: at the 12 m/s speed cap every noisy hop is a spike (the baseline records 9 / 93 m; a
        // per-sport cap is the fix), reported only
        run("F2 12 m/s no Doppler acc=5 (at the speed cap)", SyntheticRun.fixes(5f, 0.0f, speedMps = 12.0), 12.0 * (SyntheticRun.DURATION_S - 1))
    }

    /**
     * F5: bursts of N consecutive multipath fixes 40 m off the path, one burst a minute. The rule 3 escape's
     * "three consecutive spikes that agree with each other" fired on the third fix of every burst of ≥ 4 (they agree
     * with each other at 3 m/s) and re-anchored the walk on the excursion with nothing credited, then the return
     * did the same: −12.7 % (N = 4) to −13.3 % (N = 5..25) against the baseline, which rode every burst out as
     * spikes and accepted the first fix back on the path from the old anchor. Now the escape needs the raw chain
     * since the anchor to be consistent (the 40 m/s hop into the burst breaks it) and its time limit is 30 s, so
     * bursts up to ~20 s cost nothing (within 0.5 % for N ≤ 12) and longer ones become the baseline's plausible
     * hops (N = 16 / 20 / 25: +3.7 / +7.4 / +10.9 %, equal to the baseline, reported only). Hop mode at 8 m is
     * within 3 % as the baseline (+1.5 / +1.7 %).
     */
    @Test
    fun multipathBurstsAreRiddenOutAsSpikes() {
        for (n in intArrayOf(4, 6, 8, 12)) {
            val r = run("F5 6 m + Doppler, burst of $n fixes 40 m off every 60 s", SyntheticRun.fixes(6f, 3.0f, spikeEvery = 60, spikeOffsetM = 40.0, spikeLength = n))
            // every burst fix is a spike (at N = 12 noise makes one burst's last fix a plausible 47.5 m hop, and the
            // return then costs 25 more spikes — what the baseline does too, +0.45 %)
            assertTrue("$r", r.t.rejectedSpikeCount >= 19 * n)
            assertEquals("$r", 0, r.t.escapedSpikeCount)
            assertTrue("$r: expected within 0.5 %", abs(r.errorPct) <= 0.5)
        }
        for (n in intArrayOf(16, 20, 25)) run("F5 6 m + Doppler, burst of $n fixes 40 m off every 60 s (reported)", SyntheticRun.fixes(6f, 3.0f, spikeEvery = 60, spikeOffsetM = 40.0, spikeLength = n))
        val truthWalk = 1.2 * (SyntheticRun.DURATION_S - 1)
        for (n in intArrayOf(4, 8)) {
            val walk = run("F5 walk 1.2 m/s, burst of $n fixes 40 m off every 60 s", SyntheticRun.fixes(6f, 1.2f, speedMps = 1.2, spikeEvery = 60, spikeOffsetM = 40.0, spikeLength = n), truthWalk)
            assertEquals("$walk", 0, walk.t.escapedSpikeCount)
            assertTrue("$walk: expected within 1 %", abs(walk.errorPct) <= 1.0)
            val hop = run("F5 8 m no Doppler, burst of $n fixes 40 m off every 60 s", SyntheticRun.fixes(8f, 0.0f, spikeEvery = 60, spikeOffsetM = 40.0, spikeLength = n))
            assertEquals("$hop", 0, hop.t.escapedSpikeCount)
            assertTrue("$hop: expected within 3 %", abs(hop.errorPct) <= 3.0)
        }
    }

    /**
     * F6: a pause/resume cadence shorter than the 15 s first-anchor wait, in 25 m accuracy: every resume started a
     * wait that the next pause cleared before it completed, so the run recorded **nothing** (baseline: −10 % at a
     * pause every 10 s, the second between the last fix before each pause and the pause itself). markGap() now
     * credits the open wait's integral at the last held fix, and the controller's stop() flushes the last one the
     * same way: 3240.0 m, equal to the baseline.
     */
    @Test
    fun pauseEveryTenSecondsInPoorAccuracyStillMeasures() {
        val every10 = run("F6 25 m + Doppler, markGap every 10 s", SyntheticRun.fixes(25f, 3.0f), gapEveryMs = 10_000L)
        assertEquals(0, every10.t.acceptedCount)                 // no wait ever completes
        assertTrue("$every10: expected within 12 % of 3600 m", abs(every10.errorPct) <= 12.0)
        assertTrue("$every10: expected below the truth (the second before each pause is lost)", every10.t.distanceMeters < SyntheticRun.TRUE_DISTANCE_M)
        val every20 = run("F6 25 m + Doppler, markGap every 20 s", SyntheticRun.fixes(25f, 3.0f), gapEveryMs = 20_000L)
        assertTrue("$every20: expected within 6 %", abs(every20.errorPct) <= 6.0)
        val good = run("F6 6 m + Doppler, markGap every 10 s", SyntheticRun.fixes(6f, 3.0f), gapEveryMs = 10_000L)
        assertTrue("$good: expected within 11 %", abs(good.errorPct) <= 11.0)
    }

    /**
     * F7: a multipath spike (100 m off the path) right at the expiry of the first-anchor wait, no Doppler. Anchoring
     * on the fix at expiry put the anchor 100 m off the path and the return cost the wait plus an escape: −1.5 to
     * −2.1 %. A spike at expiry is now rejected like any other (the 100 m/s hop into it breaks the raw chain) and
     * the candidate stays the anchor: within 0.2 % of the baseline (−0.14 %). With Doppler the integral over the
     * wait is credited at the first fix back on the path.
     */
    @Test
    fun spikeAtWaitExpiryIsRejected() {
        val degLonM = SyntheticRun.DEG_LAT_M * kotlin.math.cos(Math.toRadians(SyntheticRun.LAT0))
        fun spiked(range: IntRange, doppler: Float) = SyntheticRun.fixes(25f, doppler).mapIndexed { i, f -> if (i in range) f.copy(lon = f.lon + 100.0 / degLonM) else f }
        for (range in listOf(15..15, 15..17, 14..16, 13..20)) {
            val hop = run("F7 25 m no Doppler, 100 m spike at t=$range", spiked(range, 0f))
            assertEquals("$hop", 0, hop.t.escapedSpikeCount)
            assertTrue("$hop: expected within 0.5 %", abs(hop.errorPct) <= 0.5)
            val doppler = run("F7 25 m + Doppler, 100 m spike at t=$range", spiked(range, 3.0f))
            assertEquals("$doppler", 0, doppler.t.escapedSpikeCount)
            assertTrue("$doppler: expected within 0.5 %", abs(doppler.errorPct) <= 0.5)
        }
    }

    /** A NaN reported speed (not what WorkoutService sends, but a one-line guard) counts as 0 and cannot poison the integral. */
    @Test
    fun nanSpeedDoesNotPoisonTheRun() {
        val r = run("NaN speed at t=5 (during the wait) and t=600", SyntheticRun.fixes(25f, 3.0f).mapIndexed { i, f -> if (i == 5 || i == 600) f.copy(speedMps = Float.NaN) else f })
        assertTrue("$r: distance must be finite", r.t.distanceMeters.isFinite())
        assertTrue("$r: expected within 1 %", abs(r.errorPct) <= 1.0)
    }

    /**
     * F3: a creeping receiver — a 1.2 m/s walk with ten 60 s stops (718.8 m truth) during which the receiver
     * reports 0 / 0.7 / 0.95 m/s while the position only scatters. Integrating the sub-threshold speeds cost up to
     * one accuracy radius per stop: +33.5 % at 25 m, +52.6 % at 50 m (HEAD, which fell back to the hop across a
     * long interval, +2.2 %). Rule 4b now believes the sub-threshold part of the integral only as far as the
     * position confirms it, so a stop costs about the hop — the same as with the receiver reporting 0, and what
     * HEAD charged — and reporting 0 while standing is exact at 25 / 50 m (the walking second's Doppler, not the
     * scattered hop). At 5 m accuracy the baseline itself is hop-mode jitter (≈ 8 m per stop: the ±2 m scatter
     * sometimes leaves the 7.5 m radius), unchanged since build 4.
     */
    @Test
    fun creepingReceiverCostsAboutTheHopNotTheAccuracyRadius() {
        val stops = SyntheticRun.DURATION_S / 120
        for (acc in floatArrayOf(5f, 25f, 50f)) {
            val bound = when { acc <= 5f -> 15.0; acc <= 25f -> 5.0; else -> 10.0 }
            val reportedSpeeds = floatArrayOf(0.0f, 0.7f, 0.95f)
            val results = reportedSpeeds.map { reported ->
                val (fixes, truth) = SyntheticRun.walkWithStops(accuracyM = acc, standingReportedMps = reported)
                run("F3 walk 1.2 m/s 60 s / stand 60 s ×$stops, acc=$acc, standing receiver reports $reported", fixes, truth)
            }
            val silent = results[0]
            for (r in results) assertTrue("$r: expected within $bound % of the truth", abs(r.errorPct) <= bound)
            for ((reported, r) in reportedSpeeds.zip(results).drop(1)) {
                val marginal = (r.t.distanceMeters - silent.t.distanceMeters) / stops
                println("SYNTHETIC_STOPS acc=%.0fm: a receiver creeping at %.2f m/s while standing costs %.1f m per stop over one reporting 0".format(acc, reported, marginal))
                assertTrue("creeping costs %.1f m per stop over reporting 0, expected at most 3 m (about one hop's scatter): ".format(marginal) + r, marginal <= 3.0)
            }
            if (acc >= 25f) assertEquals("$silent: reporting 0 while standing is exact at $acc m", silent.truthM, silent.t.distanceMeters, 0.05)
        }
    }

    /**
     * F4: fixes rejected as spikes are not believed about their speed either. Every 30th fix 25 m off the path
     * reporting 10 m/s used to add its 10 m to the integral (+20 % over the run; HEAD 0 %); now the next credible
     * fix spans it at the receiver's real speed and the run is within 1 %, with every spike still rejected.
     */
    @Test
    fun spikesReportingABogusSpeedAreNotIntegrated() {
        val bogus = run("F4 6 m + Doppler, every 30th fix 25 m off reporting 10 m/s", SyntheticRun.fixes(6f, 3.0f, spikeEvery = 30, spikeOffsetM = 25.0, spikeReportedMps = 10f))
        assertEquals(39, bogus.t.rejectedSpikeCount)
        assertEquals(0, bogus.t.escapedSpikeCount)
        assertTrue("$bogus: expected within 1 %", abs(bogus.errorPct) <= 1.0)
        val honest = run("F4 6 m + Doppler, every 30th fix 25 m off reporting 3 m/s", SyntheticRun.fixes(6f, 3.0f, spikeEvery = 30, spikeOffsetM = 25.0))
        assertTrue("$honest: expected within 1 %", abs(honest.errorPct) <= 1.0)
        val dense = run("F4 6 m + Doppler, every 10th fix 40 m off reporting 10 m/s", SyntheticRun.fixes(6f, 3.0f, spikeEvery = 10, spikeOffsetM = 40.0, spikeReportedMps = 10f))
        assertEquals(119, dense.t.rejectedSpikeCount)
        assertTrue("$dense: expected within 1 %", abs(dense.errorPct) <= 1.0)
        val walk = run("F4 walk 1.2 m/s, every 30th fix 25 m off reporting 10 m/s", SyntheticRun.fixes(6f, 1.2f, speedMps = 1.2, spikeEvery = 30, spikeOffsetM = 25.0, spikeReportedMps = 10f), truthM = 1.2 * (SyntheticRun.DURATION_S - 1))
        assertTrue("$walk: expected within 1 %", abs(walk.errorPct) <= 1.0)
    }

    /**
     * Known trade-offs of the per-fix integral, reported with loose bounds so a change shows up:
     *  - a *sub-threshold* Doppler dropout (one fix in 20 reporting 0.3 m/s on a 3 m/s run) costs the missing
     *    second's speed, about 2.7 m per dropout: −4.4 % here (−8.7 % with one in 10; the baseline, which credited
     *    the last speed over the whole interval, −0.1 %; the same rule turned 33 s of 0–0.5 m/s fixes on the real walk
     *    into 38 m, which is why it went). A fix reporting *exactly* 0 is a `hasSpeed() == false` dropout and is
     *    bridged with the last speed (rule 4c, dopplerDropoutsAreBridgedWithTheLastSpeed);
     *  - a walker whose reported speed straddles the 1 m/s threshold (1.0 m/s reported 0.9 / 1.1, 5 m accuracy)
     *    is measured mostly in hop mode: +9 % (baseline +20 %), −0.8 % at 25 m (baseline +10 %); a 0.8 m/s walker
     *    with a 1.05 m/s fix every 10 s at 25 m accuracy −2.4 % (baseline +5 %);
     *  - an under-reporting receiver (a 1.2 m/s walker reported as 0.99 m/s with 1.2 every 3rd fix, or 1.2 / 0.9
     *    alternating) is believed at 10–35 m accuracy: −16 % / −14 % (the baseline, in hop mode across the
     *    sub-threshold fixes, within 0.2 %); at 5 m within 1–6 %. Symmetric speed noise (N(1.0, 0.3) on a 1.0 m/s
     *    walker) is within 1.2 % at 25 m against the baseline's +22 %.
     */
    @Test
    fun perFixIntegralTradeOffsReported() {
        val dropout = run("T3 3 m/s at 6 m, Doppler dropout to 0.3 m/s every 20th fix", SyntheticRun.fixes(6f, 3.0f).mapIndexed { i, f -> f.copy(speedMps = if (i % 20 == 0) 0.3f else 3.0f) })
        assertTrue("$dropout: expected within 6 %", abs(dropout.errorPct) <= 6.0)
        for (acc in floatArrayOf(5f, 25f)) {
            val straddling = run("T1 walk 1.0 m/s reported 0.9 / 1.1 alternating, acc $acc m", SyntheticRun.fixes(acc, 1.0f, speedMps = 1.0).mapIndexed { i, f -> f.copy(speedMps = if (i % 2 == 0) 0.9f else 1.1f) }, truthM = 1.0 * (SyntheticRun.DURATION_S - 1))
            assertTrue("$straddling: expected within 15 %", abs(straddling.errorPct) <= 15.0)
        }
        val slow = run("T2 walk 0.8 m/s reported 0.8, 1.05 every 10th fix, acc 25 m", SyntheticRun.fixes(25f, 0.8f, speedMps = 0.8).mapIndexed { i, f -> f.copy(speedMps = if (i % 10 == 0) 1.05f else 0.8f) }, truthM = 0.8 * (SyntheticRun.DURATION_S - 1))
        assertTrue("$slow: expected within 5 %", abs(slow.errorPct) <= 5.0)
        val truthWalk = 1.2 * (SyntheticRun.DURATION_S - 1)
        for (acc in floatArrayOf(5f, 10f, 25f)) {
            val under = run("T4 walk 1.2 m/s reported 0.99, 1.2 every 3rd fix, acc $acc m", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).mapIndexed { i, f -> f.copy(speedMps = if (i % 3 == 0) 1.2f else 0.99f) }, truthWalk)
            assertTrue("$under: expected within 20 %", abs(under.errorPct) <= 20.0)
            val alternating = run("T4 walk 1.2 m/s reported 1.2 / 0.9 alternating, acc $acc m", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).mapIndexed { i, f -> f.copy(speedMps = if (i % 2 == 0) 1.2f else 0.9f) }, truthWalk)
            assertTrue("$alternating: expected within 20 %", abs(alternating.errorPct) <= 20.0)
        }
        val rnd = java.util.Random(7L)
        val noisy = run("T5 walk 1.0 m/s reported N(1.0, 0.3), acc 25 m", SyntheticRun.fixes(25f, 1.0f, speedMps = 1.0).map { f -> f.copy(speedMps = (1.0 + rnd.nextGaussian() * 0.3).coerceAtLeast(0.0).toFloat()) }, truthM = 1.0 * (SyntheticRun.DURATION_S - 1))
        assertTrue("$noisy: expected within 5 %", abs(noisy.errorPct) <= 5.0)
    }

    /**
     * Rule 4c: WorkoutService sends speed 0 when `Location.hasSpeed()` is false. The per-fix integral credited 0 for
     * those seconds and rule 2 held them as jitter (the baseline credited the last speed over the interval): a 3 m/s
     * run with the speed missing for 1 s every 20 s was −4.9 %, 10 s every 60 s −15.5 %, 30 % of its fixes −30 %.
     * An exact 0 after a reported speed is now folded at that speed (bridged), believed only when the position is
     * consistent with the claim: every pattern here at 6 / 15 / 25 m is within 1 % of the truth for the run and the
     * walk (as the baseline), a NaN every 50th fix likewise, while a walker who *stops* with the receiver saying 0
     * (STOPS, 20–60 s stops at 25 / 50 m) is exact — the 72 m a minute the bridge would claim is contradicted by the
     * hop of a few metres and dropped. The bridge also carries a receiver that loses its speed for good, and a 0
     * reported before any speed (a dropout at the very start) is filled in by the first speed reported.
     */
    @Test
    fun dopplerDropoutsAreBridgedWithTheLastSpeed() {
        val truthWalk = 1.2 * (SyntheticRun.DURATION_S - 1)
        fun List<SyntheticFix>.dropout(every: Int, len: Int) = mapIndexed { i, f -> if (i % every < len) f.copy(speedMps = 0f) else f }
        val patterns = listOf(Triple(20, 1, "1 s every 20 s"), Triple(30, 2, "2 s every 30 s"), Triple(30, 3, "3 s every 30 s"), Triple(60, 5, "5 s every 60 s"),
            Triple(60, 10, "10 s every 60 s"), Triple(120, 10, "10 s every 120 s"), Triple(120, 20, "20 s every 120 s"), Triple(10, 1, "10 % of the fixes"), Triple(10, 3, "30 % of the fixes"))
        for ((every, len, label) in patterns) for (acc in floatArrayOf(6f, 15f, 25f)) {
            val r = run("F8 3 m/s $acc m + Doppler, speed 0 for $label", SyntheticRun.fixes(acc, 3.0f).dropout(every, len))
            assertTrue("$r: expected within 1 %", abs(r.errorPct) <= 1.0)
            assertTrue("$r: the dropouts must be bridged", r.t.bridgedSpeedCount > 0)
            val w = run("F8 walk 1.2 m/s $acc m + Doppler, speed 0 for $label", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).dropout(every, len), truthWalk)
            assertTrue("$w: expected within 1 %", abs(w.errorPct) <= 1.0)
        }
        val nan = run("F8 6 m + Doppler, NaN speed every 50th fix", SyntheticRun.fixes(6f, 3.0f).mapIndexed { i, f -> if (i % 50 == 0) f.copy(speedMps = Float.NaN) else f })
        assertTrue("$nan: expected within 1 %", abs(nan.errorPct) <= 1.0)
        val lost = run("F8 25 m + Doppler, speed 0 from t=300 on (the receiver loses its speed)", SyntheticRun.fixes(25f, 3.0f).mapIndexed { i, f -> if (i >= 300) f.copy(speedMps = 0f) else f })
        assertTrue("$lost: expected within 1 %", abs(lost.errorPct) <= 1.0)
        for (acc in floatArrayOf(25f, 50f)) for (stop in intArrayOf(20, 30, 60)) {
            val (fixes, truth) = SyntheticRun.walkWithStops(accuracyM = acc, stopS = stop, standingReportedMps = 0f)
            val r = run("F8 walk 1.2 m/s with $stop s stops at $acc m, standing receiver reports 0 (not a dropout)", fixes, truth)
            assertEquals("$r: a stop the receiver reports as 0 must not be bridged", truth, r.t.distanceMeters, 0.05)
        }
        val (fixes5, truth5) = SyntheticRun.walkWithStops(accuracyM = 5f, standingReportedMps = 0f)
        val stops5 = run("F8 walk 1.2 m/s with 60 s stops at 5 m, standing receiver reports 0", fixes5, truth5)
        assertTrue("$stops5: expected within 15 % (hop-mode jitter at 5 m, the baseline is +13.7 %)", abs(stops5.errorPct) <= 15.0)
    }

    /**
     * Rule 3 with Doppler: a multipath excursion that ramps away below the 18 m/s chain-noise allowance and drifts
     * on at ≤ 12 m/s satisfied "three consecutive agreeing spikes on a consistent chain", and the escape re-anchored
     * on the excursion crediting its raw chain against the receiver's honest 3 m/s: +10 % on the run and +51 % on
     * the walk with one such excursion a minute at 5–6 m. A receiver that reports speeds never deadlocks (its bound
     * carries the last speed), so that escape is now for speed-less receivers only; with Doppler the excursion is
     * ridden out as spikes and the first fix back on the path is accepted from the old anchor with the integral —
     * the baseline's figures: within 1 % at 5 / 6 m for excursions a minute (the six-fix ramp +0.5 / +0.7 %), the
     * baseline's own over-count when they come every 20 s (up to +2 % for four-fix ramps, +8 % for six), and at
     * 10 m, where the first 15 m step is inside the 3 + 20 m bound and is *accepted* as a hop by both trackers, the
     * baseline's +1–20 % (reported, with 0 escapes). Without Doppler the chain escape still measures the rider.
     */
    @Test
    fun rampedMultipathExcursionsAreRiddenOutWithDoppler() {
        val degLonM = SyntheticRun.DEG_LAT_M * kotlin.math.cos(Math.toRadians(SyntheticRun.LAT0))
        fun List<SyntheticFix>.ramp(every: Int, offsets: DoubleArray) = mapIndexed { i, f -> if (i >= every && i % every < offsets.size) f.copy(lon = f.lon + offsets[i % every] / degLonM) else f }
        val truthWalk = 1.2 * (SyntheticRun.DURATION_S - 1)
        val ramps = listOf("15/23/31/39" to doubleArrayOf(15.0, 23.0, 31.0, 39.0), "16/26/36/46" to doubleArrayOf(16.0, 26.0, 36.0, 46.0), "14/22/30/38/46/54" to doubleArrayOf(14.0, 22.0, 30.0, 38.0, 46.0, 54.0))
        for ((label, offsets) in ramps) for (acc in floatArrayOf(5f, 6f)) {
            val minute = run("F9 3 m/s $acc m + Doppler, ramp $label m east every 60 s", SyntheticRun.fixes(acc, 3.0f).ramp(60, offsets))
            assertEquals("$minute", 0, minute.t.escapedSpikeCount)
            assertTrue("$minute: expected within 1 %", abs(minute.errorPct) <= 1.0)
            val walk = run("F9 walk 1.2 m/s $acc m + Doppler, ramp $label m east every 60 s", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).ramp(60, offsets), truthWalk)
            assertEquals("$walk", 0, walk.t.escapedSpikeCount)
            assertTrue("$walk: expected within 1.5 %", abs(walk.errorPct) <= 1.5)
            val often = run("F9 3 m/s $acc m + Doppler, ramp $label m east every 20 s", SyntheticRun.fixes(acc, 3.0f).ramp(20, offsets))
            assertEquals("$often", 0, often.t.escapedSpikeCount)
            assertTrue("$often: expected within ${if (offsets.size == 4) 2.5 else 8.0} %", abs(often.errorPct) <= if (offsets.size == 4) 2.5 else 8.0)
        }
        for ((label, offsets) in ramps) for (every in intArrayOf(60, 20)) {
            val r = run("F9 3 m/s 10 m + Doppler, ramp $label m east every $every s (the baseline accepts the 15 m step as a hop)", SyntheticRun.fixes(10f, 3.0f).ramp(every, offsets))
            assertEquals("$r", 0, r.t.escapedSpikeCount)
            assertTrue("$r: expected within 21 % (the baseline's own figure)", abs(r.errorPct) <= 21.0)
        }
        for ((label, offsets) in ramps) run("F9 3 m/s 5 m NO Doppler, ramp $label m east every 60 s (reported: the chain escape measures the rider)", SyntheticRun.fixes(5f, 0f).ramp(60, offsets))
    }

    /** A fix with the timestamp of the previous one (dt = 0) used to be accepted with its scatter as a hop: +13.8 % with every 10th duplicated. */
    @Test
    fun duplicatedTimestampsAreRejected() {
        fun List<SyntheticFix>.dupEvery(every: Int) = mapIndexed { i, f -> if (i > 0 && i % every == 0) f.copy(time = this[i - 1].time) else f }
        for (acc in floatArrayOf(6f, 25f)) {
            val r = run("F10 $acc m + Doppler, every 10th timestamp duplicated", SyntheticRun.fixes(acc, 3.0f).dupEvery(10))
            assertTrue("$r: expected within 1 %", abs(r.errorPct) <= 1.0)
            assertEquals("$r: the duplicates are rejected", 119, r.t.rejectedJitterCount)
        }
        val hop = run("F10 8 m no Doppler, every 10th timestamp duplicated", SyntheticRun.fixes(8f, 0f).dupEvery(10))
        assertTrue("$hop: expected within 5 %", abs(hop.errorPct) <= 5.0)
    }

    /**
     * A 12 m/s cyclist with Doppler at 5 m: half the noisy 1 s hops exceed the 12 m/s cap and are spikes, and the
     * anchor's noise ratchets so the runs of spikes last longer than 30 s. The time escape then credits the last
     * credible speed over the interval, capped by the hop (the baseline, which never re-anchored, −9.7 %; crediting
     * only the integral of folded fixes, i.e. nothing, −55 %). Without Doppler the same rider is a known failure
     * (−43 %, see speedlessReceiverAtRunningAndCyclingPaceDoesNotDeadlock).
     */
    @Test
    fun cyclistAtTheSpeedCapWithDopplerIsMeasuredByTheTimeEscape() {
        val r = run("F11 12 m/s + Doppler at 5 m (at the speed cap)", SyntheticRun.fixes(5f, 12f, speedMps = 12.0), 12.0 * (SyntheticRun.DURATION_S - 1))
        assertTrue("$r", r.t.escapedSpikeCount > 0)
        assertTrue("$r: expected within 5 %", abs(r.errorPct) <= 5.0)
    }
}
