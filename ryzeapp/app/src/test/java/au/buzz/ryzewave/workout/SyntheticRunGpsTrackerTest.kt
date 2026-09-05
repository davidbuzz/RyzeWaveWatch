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

    private fun run(name: String, fixes: List<SyntheticFix>, truthM: Double = SyntheticRun.TRUE_DISTANCE_M, gapAt: Long = -1L): Result {
        val t = DefaultGpsDistanceTracker()
        var minPace = Double.MAX_VALUE
        var zeroPace = 0
        for (f in fixes) {
            if (f.time == gapAt) t.markGap()          // a pause/resume, as the controller does
            t.addFix(f.time, f.lat, f.lon, f.accuracyM, f.speedMps)
            if (f.time - SyntheticRun.T0 >= 60_000L) {
                if (t.paceSecPerKm == 0.0) zeroPace++ else minPace = minOf(minPace, t.paceSecPerKm)
            }
        }
        val r = Result(name, t, fixes.size, t.paceSecPerKm, t.speedMps, if (minPace == Double.MAX_VALUE) 0.0 else minPace, zeroPace, truthM)
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
     * Regression scenarios from the second review of the tracker (docs/APP.md "Tracker refinements"). The review
     * replayed the build-14 tracker against the build-8 (git HEAD c7871c0) one and found four regressions; the
     * fixed numbers are asserted here against the truth (HEAD itself cannot be part of a permanent test).
     *
     * F1: the first-anchor wait (rule 1b) dropped the Doppler integral on three of its four exits — a ≤ 20 m fix
     * arriving mid-wait, a better candidate mid-wait, the current fix being the best at expiry — losing up to
     * (15 s + one interval) × speed at every start and every resume after a pause: −45 m at 3 m/s, −18 m at 1.2 m/s
     * (−4.7 % of a 5-minute walk). Now every exit credits the capped integral since the first held fix, and each of
     * these runs equals the constant-accuracy one (−0.08 %: the very first interval of the run has no fix before it).
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
            check(run("F1c $label, 30 m for 15 s then 25 m (current fix best at expiry)", SyntheticRun.fixes(30f, sp, speedMps = speed, accuracyAt = { i -> if (i < 15) 30f else 25f }), truth), 15)
            check(run("F1d $label, 25 m constant (candidate best at expiry)", SyntheticRun.fixes(25f, sp, speedMps = speed), truth), 15)
            check(run("F1e $label, 25 m, pause/resume at t=600", SyntheticRun.fixes(25f, sp, speedMps = speed), truth, gapAt = SyntheticRun.T0 + 600_000L), 30)
            check(run("F1f $label, 25 m, pause/resume at t=600, 15 m fixes from t=610", SyntheticRun.fixes(25f, sp, speedMps = speed, accuracyAt = { i -> if (i in 610..1199) 15f else 25f }), truth, gapAt = SyntheticRun.T0 + 600_000L), 25)
            check(run("F1g $label, 50 m first fix then 15 m", SyntheticRun.fixes(50f, sp, speedMps = speed, accuracyAt = { i -> if (i == 0) 50f else 15f }), truth), 1)
        }
    }

    /**
     * F2: a receiver with no Doppler speed at running / cycling pace. Two deadlocks: (a) with poor accuracy the
     * wait pushed the first judged hop to dt = 15 s, where rule 3 allowed only 2.5 × 15 + two radii — a 6 m/s
     * rider is 90 m away, the hop was rejected, and every later fix was farther still (0.0 m after 20 minutes);
     * (b) at 5 m accuracy a 6 m/s hop is 12 m over 2 s against an allowance of 15 m, position noise rejected one
     * hop in seven, and after a rejection the allowance grew 2.5 m per second while the rider moved 6 (HEAD: 851 of
     * 7194 m). Now the wait never ends in a rejection (the implausible fix anchors), the plausibility bound
     * includes the speed the tracker last measured, and spikes that go on for 10 s (or three that agree with each
     * other) re-anchor without credit. At 21–35 m the runs are within 2 % (the one wait's hop, 90–105 m, is the
     * only loss: a speed-less receiver has nothing to credit for it); at 5 m the residual is hop mode's own
     * over-count of 1–2 s hops with 2 m independent noise (HEAD's 4 m/s run at 5 m, which never deadlocked, is
     * +4.95 %): +3.1 % at 6 m/s, +1.3 % at 7 m/s, and within 1 % with Kalman-like (phi 0.9) noise.
     */
    @Test
    fun speedlessReceiverAtRunningAndCyclingPaceDoesNotDeadlock() {
        for (speed in doubleArrayOf(6.0, 7.0)) {
            val truth = speed * (SyntheticRun.DURATION_S - 1)
            for (acc in floatArrayOf(5f, 21f, 25f, 30f)) {
                val bound = if (acc <= 5f) 3.5 else 3.0
                val r = run("F2 $speed m/s no Doppler acc=$acc", SyntheticRun.fixes(acc, 0.0f, speedMps = speed), truth)
                assertTrue("$r: expected within $bound % of ${"%.1f".format(truth)} m", abs(r.errorPct) <= bound)
                val poorStart = run("F2 $speed m/s no Doppler acc=$acc, 40 m for the first 20 s", SyntheticRun.fixes(acc, 0.0f, speedMps = speed, accuracyAt = { i -> if (i < 20) 40f else acc }), truth)
                assertTrue("$poorStart: expected within $bound % of ${"%.1f".format(truth)} m", abs(poorStart.errorPct) <= bound)
            }
            val smooth = run("F2 $speed m/s no Doppler acc=5, phi=0.9 noise", SyntheticRun.fixes(5f, 0.0f, speedMps = speed, phi = 0.9), truth)
            assertTrue("$smooth: expected within 1 %", abs(smooth.errorPct) <= 1.0)
            val doppler = run("F2 $speed m/s Doppler acc=25", SyntheticRun.fixes(25f, speed.toFloat(), speedMps = speed), truth)
            assertTrue("$doppler: expected within 0.5 %", abs(doppler.errorPct) <= 0.5)
        }
        run("F2 4 m/s no Doppler acc=5 (hop mode's own over-count, unchanged since build 4)", SyntheticRun.fixes(5f, 0.0f, speedMps = 4.0), 4.0 * (SyntheticRun.DURATION_S - 1))
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
     *  - a Doppler dropout (one fix in 20 reporting 0.3 m/s on a 3 m/s run) costs the missing second's speed,
     *    about 2.7 m per dropout: −4.5 % here (HEAD, which credited the last speed over the whole interval, −0.1 %;
     *    the same rule turned 33 s of 0–0.5 m/s fixes on the real walk into 38 m, which is why it went);
     *  - a walker whose reported speed straddles the 1 m/s threshold (1.0 m/s reported 0.9 / 1.1, 5 m accuracy)
     *    is measured mostly in hop mode: +9 % (HEAD +20 %); a 0.8 m/s walker with a 1.05 m/s fix every 10 s at
     *    25 m accuracy −2.4 % (HEAD +5 %).
     */
    @Test
    fun perFixIntegralTradeOffsReported() {
        val dropout = run("T3 3 m/s at 6 m, Doppler dropout to 0.3 m/s every 20th fix", SyntheticRun.fixes(6f, 3.0f).mapIndexed { i, f -> f.copy(speedMps = if (i % 20 == 0) 0.3f else 3.0f) })
        assertTrue("$dropout: expected within 6 %", abs(dropout.errorPct) <= 6.0)
        val straddling = run("T1 walk 1.0 m/s reported 0.9 / 1.1 alternating, acc 5 m", SyntheticRun.fixes(5f, 1.0f, speedMps = 1.0).mapIndexed { i, f -> f.copy(speedMps = if (i % 2 == 0) 0.9f else 1.1f) }, truthM = 1.0 * (SyntheticRun.DURATION_S - 1))
        assertTrue("$straddling: expected within 15 %", abs(straddling.errorPct) <= 15.0)
        val slow = run("T2 walk 0.8 m/s reported 0.8, 1.05 every 10th fix, acc 25 m", SyntheticRun.fixes(25f, 0.8f, speedMps = 0.8).mapIndexed { i, f -> f.copy(speedMps = if (i % 10 == 0) 1.05f else 0.8f) }, truthM = 0.8 * (SyntheticRun.DURATION_S - 1))
        assertTrue("$slow: expected within 5 %", abs(slow.errorPct) <= 5.0)
    }
}
