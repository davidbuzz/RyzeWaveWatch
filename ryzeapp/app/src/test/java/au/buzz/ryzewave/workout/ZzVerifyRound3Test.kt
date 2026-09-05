package au.buzz.ryzewave.workout

import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** SCRATCH verifier harness (round 3): base (1c28d2e) vs WIP HEAD (99669dd) vs working tree. Deleted afterwards. */
class ZzVerifyRound3Test {

    private interface Trk {
        fun add(f: SyntheticFix): Boolean
        fun gap()
        val dist: Double
        val diag: String
    }

    private class BaseT : Trk {
        val t = ZzBaseTracker()
        override fun add(f: SyntheticFix) = t.addFix(f.time, f.lat, f.lon, f.accuracyM, f.speedMps)
        override fun gap() = t.markGap()
        override val dist get() = t.distanceMeters
        override val diag get() = "acc=${t.acceptedCount} jit=${t.rejectedJitterCount} spk=${t.rejectedSpikeCount}"
    }

    private class WipT : Trk {
        val t = ZzWipTracker()
        override fun add(f: SyntheticFix) = t.addFix(f.time, f.lat, f.lon, f.accuracyM, f.speedMps)
        override fun gap() = t.markGap()
        override val dist get() = t.distanceMeters
        override val diag get() = "acc=${t.acceptedCount} jit=${t.rejectedJitterCount} spk=${t.rejectedSpikeCount} esc=${t.escapedSpikeCount}"
    }

    private class NewT : Trk {
        val t = DefaultGpsDistanceTracker()
        override fun add(f: SyntheticFix) = t.addFix(f.time, f.lat, f.lon, f.accuracyM, f.speedMps)
        override fun gap() = t.markGap()
        override val dist get() = t.distanceMeters
        override val diag get() = "acc=${t.acceptedCount} jit=${t.rejectedJitterCount} spk=${t.rejectedSpikeCount} esc=${t.escapedSpikeCount} def=${t.deferredFirstFixCount} reA=${t.reAnchoredCount} brg=${t.bridgedSpeedCount}"
    }

    private val T0 = SyntheticRun.T0
    private val DEG_LAT = SyntheticRun.DEG_LAT_M
    private val DEG_LON = SyntheticRun.DEG_LAT_M * cos(Math.toRadians(SyntheticRun.LAT0))

    private var flagged = 0
    private var rows = 0

    /**
     * Runs the fixes through the three trackers (markGap at [gapAt] times / every [gapEveryMs], and at the end as
     * the controller's stop() does) and prints one row. FLAG when new is worse than base by more than 1 % of the
     * truth and not closer to the truth than base.
     */
    private fun scenario(name: String, fixes: List<SyntheticFix>, truth: Double, gapAt: Set<Long> = emptySet(), gapEveryMs: Long = 0L, flushEnd: Boolean = true): Triple<Double, Double, Double> {
        fun <T : Trk> runOn(t: T): T {
            for (f in fixes) {
                if (f.time in gapAt || (gapEveryMs > 0L && (f.time - T0) % gapEveryMs == 0L)) t.gap()
                t.add(f)
            }
            if (flushEnd) t.gap()
            return t
        }
        val b = runOn(BaseT())
        val w = runOn(WipT())
        val n = runOn(NewT())
        fun pct(x: Double) = if (truth > 0.0) (x - truth) / truth * 100.0 else 0.0
        val vsBase = if (truth > 0.0) (n.dist - b.dist) / truth * 100.0 else 0.0
        val closer = abs(n.dist - truth) < abs(b.dist - truth) - 1e-9
        val worse = abs(n.dist - truth) > abs(b.dist - truth) + 0.01 * truth
        val flag = if (worse && !closer) "FLAG" else "ok"
        if (flag == "FLAG") flagged++
        rows++
        println("ROW %-92s truth=%8.1f | base=%8.1f (%+6.2f%%) | wip=%8.1f (%+6.2f%%) | new=%8.1f (%+6.2f%%, vs base %+6.2f%%) %s | new: %s | base: %s".format(
            name, truth, b.dist, pct(b.dist), w.dist, pct(w.dist), n.dist, pct(n.dist), vsBase, flag, n.diag, b.diag))
        return Triple(b.dist, w.dist, n.dist)
    }

    /** General generator: straight line north, 1 Hz, [moving] seconds advance [speedAt]; truth = sum of the advances. */
    private fun gen(
        durationS: Int = 1200,
        speed: Double = 3.0,
        acc: (Int) -> Float = { 6f },
        doppler: (Int) -> Float = { 3f },
        moving: (Int) -> Boolean = { true },
        east: (Int) -> Double = { 0.0 },
        timeMs: (Int) -> Long = { it * 1000L },
        drop: (Int) -> Boolean = { false },
        sigma: Double = 2.0,
        phi: Double = 0.0,
        seed: Long = 42L,
        speedAt: (Int) -> Double = { speed },
    ): Pair<List<SyntheticFix>, Double> {
        val rnd = Random(seed)
        val out = ArrayList<SyntheticFix>(durationS)
        val innovation = sigma * sqrt(1.0 - phi * phi)
        var nN = rnd.nextGaussian() * sigma
        var nE = rnd.nextGaussian() * sigma
        var north = 0.0
        var truth = 0.0
        for (i in 0 until durationS) {
            if (i > 0) {
                nN = phi * nN + rnd.nextGaussian() * innovation
                nE = phi * nE + rnd.nextGaussian() * innovation
                if (moving(i)) { north += speedAt(i); truth += speedAt(i) }
            }
            if (drop(i)) continue
            out += SyntheticFix(T0 + timeMs(i), SyntheticRun.LAT0 + (north + nN) / DEG_LAT, SyntheticRun.LON0 + (nE + east(i)) / DEG_LON, acc(i), doppler(i))
        }
        return out to truth
    }

    /** Number of consecutive fed fixes 1 s apart: the intervals a paused-and-resumed workout can measure. */
    private fun fedPairs(fx: List<SyntheticFix>) = fx.zipWithNext().count { (a, b) -> b.time - a.time == 1000L }

    private val truthRun = 3.0 * 1199
    private val truthWalk = 1.2 * 1199

    @Test
    fun replayEverything() {
        println("=== A. round-1/2 table ===")
        scenario("A 6m doppler", SyntheticRun.fixes(6f, 3f), truthRun)
        scenario("B 25m doppler", SyntheticRun.fixes(25f, 3f), truthRun)
        scenario("B2 35m doppler", SyntheticRun.fixes(35f, 3f), truthRun)
        scenario("E 25m every10th=8m doppler", SyntheticRun.fixes(25f, 3f, accuracyAt = { i -> if (i % 10 == 0) 8f else 25f }), truthRun)
        scenario("E 25m every10th=8m (from 5) doppler", SyntheticRun.fixes(25f, 3f, accuracyAt = { i -> if (i % 10 == 5) 8f else 25f }), truthRun)
        scenario("E 30/9 alternating doppler", SyntheticRun.fixes(30f, 3f, accuracyAt = { i -> if (i % 2 == 0) 9f else 30f }), truthRun)
        scenario("E 9/30 alternating doppler", SyntheticRun.fixes(30f, 3f, accuracyAt = { i -> if (i % 2 == 0) 30f else 9f }), truthRun)
        scenario("E2 walk 1.2 25<->8 every 5s doppler", SyntheticRun.fixes(25f, 1.2f, speedMps = 1.2, accuracyAt = { i -> if ((i / 5) % 2 == 0) 25f else 8f }), truthWalk)
        scenario("E2 walk 1.2 8<->25 every 5s doppler", SyntheticRun.fixes(25f, 1.2f, speedMps = 1.2, accuracyAt = { i -> if ((i / 5) % 2 == 0) 8f else 25f }), truthWalk)
        scenario("B3 25m no doppler", SyntheticRun.fixes(25f, 0f), truthRun)
        scenario("B3' 35m no doppler", SyntheticRun.fixes(35f, 0f), truthRun)
        scenario("C 8m no doppler", SyntheticRun.fixes(8f, 0f), truthRun)
        scenario("E3 25m every10th=8m no doppler", SyntheticRun.fixes(25f, 0f, accuracyAt = { i -> if (i % 10 == 0) 8f else 25f }), truthRun)
        scenario("E3 25m every10th=8m (from 5) no doppler", SyntheticRun.fixes(25f, 0f, accuracyAt = { i -> if (i % 10 == 5) 8f else 25f }), truthRun)
        scenario("E3 30/9 alternating no doppler", SyntheticRun.fixes(30f, 0f, accuracyAt = { i -> if (i % 2 == 0) 9f else 30f }), truthRun)
        scenario("E3 25<->8 every 5s no doppler", SyntheticRun.fixes(25f, 0f, accuracyAt = { i -> if ((i / 5) % 2 == 0) 25f else 8f }), truthRun)
        scenario("E3 8<->25 every 5s no doppler", SyntheticRun.fixes(25f, 0f, accuracyAt = { i -> if ((i / 5) % 2 == 0) 8f else 25f }), truthRun)
        scenario("E3 6/9/3 bands every 4s no doppler", SyntheticRun.fixes(6f, 0f, accuracyAt = { i -> floatArrayOf(6f, 9f, 3f)[(i / 4) % 3] }), truthRun)
        scenario("E3 walk 1.2 25<->8 every 5s no doppler", SyntheticRun.fixes(25f, 0f, speedMps = 1.2, accuracyAt = { i -> if ((i / 5) % 2 == 0) 25f else 8f }), truthWalk)
        scenario("D 30s gap 6m doppler", SyntheticRun.fixes(6f, 3f, gapFromS = 600, gapLengthS = 30), truthRun)
        for (g in intArrayOf(60, 120, 300)) scenario("D' ${g}s gap 6m doppler", SyntheticRun.fixes(6f, 3f, gapFromS = 600, gapLengthS = g), truthRun)
        for (s in doubleArrayOf(0.0, 0.5, 1.0, 3.0)) {
            scenario("A sigma=$s 6m doppler", SyntheticRun.fixes(6f, 3f, jitterSigmaM = s), truthRun)
            scenario("C sigma=$s 8m no doppler", SyntheticRun.fixes(8f, 0f, jitterSigmaM = s), truthRun)
        }
        for (p in doubleArrayOf(0.9, 0.98)) {
            scenario("A phi=$p 6m doppler", SyntheticRun.fixes(6f, 3f, phi = p), truthRun)
            scenario("C phi=$p 8m no doppler", SyntheticRun.fixes(8f, 0f, phi = p), truthRun)
        }
        for (seed in longArrayOf(1L, 7L, 99L)) scenario("A seed=$seed 6m doppler", SyntheticRun.fixes(6f, 3f, seed = seed), truthRun)
        for (s in doubleArrayOf(5.0, 10.0)) {
            scenario("B 25m doppler sigma=$s", SyntheticRun.fixes(25f, 3f, jitterSigmaM = s), truthRun)
            scenario("B2 35m doppler sigma=$s", SyntheticRun.fixes(35f, 3f, jitterSigmaM = s), truthRun)
            scenario("B3 25m no doppler sigma=$s", SyntheticRun.fixes(25f, 0f, jitterSigmaM = s), truthRun)
            scenario("B3' 35m no doppler sigma=$s", SyntheticRun.fixes(35f, 0f, jitterSigmaM = s), truthRun)
        }
        scenario("5m no doppler sigma=3", SyntheticRun.fixes(5f, 0f, jitterSigmaM = 3.0), truthRun)
        scenario("8m no doppler sigma=5", SyntheticRun.fixes(8f, 0f, jitterSigmaM = 5.0), truthRun)

        println("=== F1. wait exits, Doppler and hop ===")
        for ((speed, label) in listOf(3.0 to "run 3.0", 1.2 to "walk 1.2")) {
            val truth = speed * 1199
            val sp = speed.toFloat()
            for (dop in listOf(sp to "dop", 0f to "hop")) {
                val d = dop.first; val dl = dop.second
                scenario("F1a $label $dl, 25 m then 15 m at t=10", SyntheticRun.fixes(25f, d, speedMps = speed, accuracyAt = { i -> if (i < 10) 25f else 15f }), truth)
                scenario("F1a' $label $dl, 25 m then 19 m at t=14", SyntheticRun.fixes(25f, d, speedMps = speed, accuracyAt = { i -> if (i < 14) 25f else 19f }), truth)
                scenario("F1b $label $dl, 30 m, better cand 25 m at t=5, 28 m from 15", SyntheticRun.fixes(30f, d, speedMps = speed, accuracyAt = { i -> if (i < 5) 30f else if (i < 15) 25f else 28f }), truth)
                scenario("F1b' $label $dl, better cand at t=14", SyntheticRun.fixes(30f, d, speedMps = speed, accuracyAt = { i -> if (i < 14) 30f else if (i < 15) 25f else 28f }), truth)
                scenario("F1b'' $label $dl, 25 m, 21 m at t=14 only", SyntheticRun.fixes(25f, d, speedMps = speed, accuracyAt = { i -> if (i == 14) 21f else 25f }), truth)
                scenario("F1c $label $dl, 30 m for 15 s then 25 m", SyntheticRun.fixes(30f, d, speedMps = speed, accuracyAt = { i -> if (i < 15) 30f else 25f }), truth)
                scenario("F1d $label $dl, 25 m constant", SyntheticRun.fixes(25f, d, speedMps = speed), truth)
                scenario("F1e $label $dl, 25 m, pause/resume at t=600", SyntheticRun.fixes(25f, d, speedMps = speed), truth, gapAt = setOf(T0 + 600_000L))
                scenario("F1f $label $dl, 25 m, pause at 600, 15 m from 610", SyntheticRun.fixes(25f, d, speedMps = speed, accuracyAt = { i -> if (i in 610..1199) 15f else 25f }), truth, gapAt = setOf(T0 + 600_000L))
                scenario("F1f' $label $dl, 25 m, pause every 300 s, 15 m from 14 s after", SyntheticRun.fixes(25f, d, speedMps = speed, accuracyAt = { i -> if (i % 300 < 14) 25f else 15f }), truth, gapEveryMs = 300_000L)
                scenario("F1g $label $dl, 50 m first fix then 15 m", SyntheticRun.fixes(50f, d, speedMps = speed, accuracyAt = { i -> if (i == 0) 50f else 15f }), truth)
                scenario("F1h $label $dl, accuracy 40..26 then 25", SyntheticRun.fixes(25f, d, speedMps = speed, accuracyAt = { i -> if (i < 15) (40 - i).toFloat() else 25f }), truth)
            }
        }
        for (speed in doubleArrayOf(6.0)) {
            val truth = speed * 1199
            scenario("F1a' hop 6.0, 25 m then 19 m at t=14", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i < 14) 25f else 19f }), truth)
            scenario("F1b' hop 6.0, better cand at t=14", SyntheticRun.fixes(30f, 0f, speedMps = speed, accuracyAt = { i -> if (i < 14) 30f else if (i < 15) 25f else 28f }), truth)
            scenario("F1b'' hop 6.0, 21 m at t=14 only", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i == 14) 21f else 25f }), truth)
            scenario("F1c hop 6.0, 30 m for 15 s then 25 m", SyntheticRun.fixes(30f, 0f, speedMps = speed, accuracyAt = { i -> if (i < 15) 30f else 25f }), truth)
            scenario("F1d hop 6.0, 25 m constant", SyntheticRun.fixes(25f, 0f, speedMps = speed), truth)
            scenario("F1e hop 6.0, pause at 600", SyntheticRun.fixes(25f, 0f, speedMps = speed), truth, gapAt = setOf(T0 + 600_000L))
            scenario("F1f hop 6.0, pause at 600, 15 m from 610", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i in 610..1199) 15f else 25f }), truth, gapAt = setOf(T0 + 600_000L))
            scenario("F1f' hop 6.0, pause every 300 s, 15 m from 14 s after", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i % 300 < 14) 25f else 15f }), truth, gapEveryMs = 300_000L)
            scenario("F1h hop 6.0, accuracy 40..26 then 25", SyntheticRun.fixes(25f, 0f, speedMps = speed, accuracyAt = { i -> if (i < 15) (40 - i).toFloat() else 25f }), truth)
        }

        println("=== F2. speed-less riders ===")
        for (speed in doubleArrayOf(5.0, 6.0, 7.0, 8.0, 10.0, 12.0)) {
            val truth = speed * 1199
            for (acc in floatArrayOf(5f, 8f, 15f, 21f, 25f, 30f)) {
                scenario("F2 $speed m/s no dop acc=$acc", SyntheticRun.fixes(acc, 0f, speedMps = speed), truth)
                scenario("F2 $speed m/s no dop acc=$acc, 40 m first 20 s", SyntheticRun.fixes(acc, 0f, speedMps = speed, accuracyAt = { i -> if (i < 20) 40f else acc }), truth)
            }
            scenario("F2 $speed m/s no dop acc=5 phi=0.9", SyntheticRun.fixes(5f, 0f, speedMps = speed, phi = 0.9), truth)
            scenario("F2 $speed m/s dop acc=25", SyntheticRun.fixes(25f, speed.toFloat(), speedMps = speed), truth)
            scenario("F2 $speed m/s dop acc=5", SyntheticRun.fixes(5f, speed.toFloat(), speedMps = speed), truth)
        }
        scenario("F2 4 m/s no dop acc=5", SyntheticRun.fixes(5f, 0f, speedMps = 4.0), 4.0 * 1199)

        println("=== F3. STOPS (creeping receiver) ===")
        for (acc in floatArrayOf(5f, 25f, 50f)) for (rep in floatArrayOf(0f, 0.5f, 0.7f, 0.9f, 0.95f)) {
            val (fx, tr) = SyntheticRun.walkWithStops(accuracyM = acc, standingReportedMps = rep)
            scenario("STOPS acc=$acc 60/60 x10 standing reports $rep", fx, tr)
        }
        for (acc in floatArrayOf(25f, 50f)) for (stop in intArrayOf(20, 30, 40)) {
            val (fx, tr) = SyntheticRun.walkWithStops(accuracyM = acc, stopS = stop, standingReportedMps = 0f)
            scenario("STOPS acc=$acc 60/$stop standing reports 0", fx, tr)
        }

        println("=== F4. spikes with bogus speed ===")
        scenario("F4 6m dop every 30th 25 m off @10 m/s", SyntheticRun.fixes(6f, 3f, spikeEvery = 30, spikeOffsetM = 25.0, spikeReportedMps = 10f), truthRun)
        scenario("F4 8m dop every 30th 25 m off @10 m/s", SyntheticRun.fixes(8f, 3f, spikeEvery = 30, spikeOffsetM = 25.0, spikeReportedMps = 10f), truthRun)
        scenario("F4 6m dop every 30th 25 m off @3 m/s", SyntheticRun.fixes(6f, 3f, spikeEvery = 30, spikeOffsetM = 25.0), truthRun)
        scenario("F4 6m dop every 10th 40 m off @10 m/s", SyntheticRun.fixes(6f, 3f, spikeEvery = 10, spikeOffsetM = 40.0, spikeReportedMps = 10f), truthRun)
        scenario("F4 walk 1.2 every 30th 25 m off @10 m/s", SyntheticRun.fixes(6f, 1.2f, speedMps = 1.2, spikeEvery = 30, spikeOffsetM = 25.0, spikeReportedMps = 10f), truthWalk)

        println("=== F5. bursts ===")
        for (n in intArrayOf(1, 2, 3, 4, 5, 6, 8, 12, 16, 20, 25)) scenario("F5 6m dop burst $n x 40 m every 60 s", SyntheticRun.fixes(6f, 3f, spikeEvery = 60, spikeOffsetM = 40.0, spikeLength = n), truthRun)
        for (n in intArrayOf(4, 8)) {
            scenario("F5 walk 1.2 burst $n x 40 m every 60 s", SyntheticRun.fixes(6f, 1.2f, speedMps = 1.2, spikeEvery = 60, spikeOffsetM = 40.0, spikeLength = n), truthWalk)
            scenario("F5 8m no dop burst $n x 40 m every 60 s", SyntheticRun.fixes(8f, 0f, spikeEvery = 60, spikeOffsetM = 40.0, spikeLength = n), truthRun)
        }

        println("=== F6. pause cadences ===")
        for (s in intArrayOf(10, 14, 15, 20, 30, 60)) {
            scenario("F6 25m dop 3 m/s markGap every $s s", SyntheticRun.fixes(25f, 3f), truthRun, gapEveryMs = s * 1000L)
            scenario("F6 25m no dop 3 m/s markGap every $s s", SyntheticRun.fixes(25f, 0f), truthRun, gapEveryMs = s * 1000L)
            scenario("F6 25m no dop 6 m/s markGap every $s s", SyntheticRun.fixes(25f, 0f, speedMps = 6.0), 6.0 * 1199, gapEveryMs = s * 1000L)
            scenario("F6 6m dop 3 m/s markGap every $s s", SyntheticRun.fixes(6f, 3f), truthRun, gapEveryMs = s * 1000L)
            scenario("F6 6m no dop 3 m/s markGap every $s s", SyntheticRun.fixes(6f, 0f), truthRun, gapEveryMs = s * 1000L)
            scenario("F6 25m dop walk 1.2 markGap every $s s", SyntheticRun.fixes(25f, 1.2f, speedMps = 1.2), truthWalk, gapEveryMs = s * 1000L)
            scenario("F6 6m dop walk 1.2 markGap every $s s", SyntheticRun.fixes(6f, 1.2f, speedMps = 1.2), truthWalk, gapEveryMs = s * 1000L)
            scenario("F6 25m no dop walk 1.2 markGap every $s s", SyntheticRun.fixes(25f, 0f, speedMps = 1.2), truthWalk, gapEveryMs = s * 1000L)
        }
        for (s in intArrayOf(5, 10, 14)) scenario("F6'' 25m dop stopped after $s fixes", SyntheticRun.fixes(25f, 3f).take(s), 3.0 * (s - 1))
        for (s in intArrayOf(10, 14)) scenario("F6'' 25m no dop 6 m/s stopped after $s fixes", SyntheticRun.fixes(25f, 0f, speedMps = 6.0).take(s), 6.0 * (s - 1))

        println("=== F7. spike at wait expiry ===")
        fun spiked(range: IntRange, doppler: Float, off: Double) = SyntheticRun.fixes(25f, doppler).mapIndexed { i, f -> if (i in range) f.copy(lon = f.lon + off / DEG_LON) else f }
        for (range in listOf(15..15, 15..17, 14..16, 13..20, 15..18, 12..12, 13..13)) for (off in doubleArrayOf(40.0, 60.0, 100.0)) {
            scenario("F7 25m no dop $off m spike at $range", spiked(range, 0f, off), truthRun)
            scenario("F7 25m dop $off m spike at $range", spiked(range, 3f, off), truthRun)
        }

        println("=== F8. Doppler dropouts ===")
        fun List<SyntheticFix>.dropout(every: Int, len: Int) = mapIndexed { i, f -> if (i % every < len) f.copy(speedMps = 0f) else f }
        val patterns = listOf(Triple(20, 1, "1s/20s"), Triple(30, 2, "2s/30s"), Triple(30, 3, "3s/30s"), Triple(60, 5, "5s/60s"), Triple(60, 10, "10s/60s"), Triple(120, 10, "10s/120s"), Triple(120, 20, "20s/120s"), Triple(10, 1, "10%"), Triple(10, 3, "30%"))
        for ((every, len, label) in patterns) for (acc in floatArrayOf(6f, 15f, 25f)) {
            scenario("F8 3 m/s $acc m dop, speed 0 $label", SyntheticRun.fixes(acc, 3f).dropout(every, len), truthRun)
            scenario("F8 walk 1.2 $acc m dop, speed 0 $label", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).dropout(every, len), truthWalk)
        }
        scenario("F8 6m NaN every 50th", SyntheticRun.fixes(6f, 3f).mapIndexed { i, f -> if (i % 50 == 0) f.copy(speedMps = Float.NaN) else f }, truthRun)
        scenario("F8 25m speed 0 from t=300", SyntheticRun.fixes(25f, 3f).mapIndexed { i, f -> if (i >= 300) f.copy(speedMps = 0f) else f }, truthRun)
        scenario("F8 6m speed 0 from t=300", SyntheticRun.fixes(6f, 3f).mapIndexed { i, f -> if (i >= 300) f.copy(speedMps = 0f) else f }, truthRun)
        scenario("T3 6m dropout to 0.3 every 20th", SyntheticRun.fixes(6f, 3f).mapIndexed { i, f -> f.copy(speedMps = if (i % 20 == 0) 0.3f else 3f) }, truthRun)
        scenario("T3 6m dropout to 0.3 every 10th", SyntheticRun.fixes(6f, 3f).mapIndexed { i, f -> f.copy(speedMps = if (i % 10 == 0) 0.3f else 3f) }, truthRun)

        println("=== F9. ramps ===")
        fun List<SyntheticFix>.ramp(every: Int, offsets: DoubleArray) = mapIndexed { i, f -> if (i >= every && i % every < offsets.size) f.copy(lon = f.lon + offsets[i % every] / DEG_LON) else f }
        val ramps = listOf("15/23/31/39" to doubleArrayOf(15.0, 23.0, 31.0, 39.0), "16/26/36/46" to doubleArrayOf(16.0, 26.0, 36.0, 46.0), "14..54x6" to doubleArrayOf(14.0, 22.0, 30.0, 38.0, 46.0, 54.0))
        for ((label, offsets) in ramps) for (acc in floatArrayOf(5f, 6f, 10f)) {
            scenario("F9 3 m/s $acc m dop ramp $label every 60 s", SyntheticRun.fixes(acc, 3f).ramp(60, offsets), truthRun)
            scenario("F9 3 m/s $acc m dop ramp $label every 20 s", SyntheticRun.fixes(acc, 3f).ramp(20, offsets), truthRun)
            scenario("F9 walk 1.2 $acc m dop ramp $label every 60 s", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).ramp(60, offsets), truthWalk)
            scenario("F9 3 m/s $acc m NO dop ramp $label every 60 s", SyntheticRun.fixes(acc, 0f).ramp(60, offsets), truthRun)
        }

        println("=== F10/F11. duplicates, cyclist ===")
        fun List<SyntheticFix>.dupEvery(every: Int) = mapIndexed { i, f -> if (i > 0 && i % every == 0) f.copy(time = this[i - 1].time) else f }
        for (acc in floatArrayOf(6f, 25f)) scenario("F10 $acc m dop every 10th ts duplicated", SyntheticRun.fixes(acc, 3f).dupEvery(10), truthRun)
        scenario("F10 8 m no dop every 10th ts duplicated", SyntheticRun.fixes(8f, 0f).dupEvery(10), truthRun)
        scenario("F11 12 m/s dop 5 m", SyntheticRun.fixes(5f, 12f, speedMps = 12.0), 12.0 * 1199)
        scenario("F11 12 m/s dop 25 m", SyntheticRun.fixes(25f, 12f, speedMps = 12.0), 12.0 * 1199)

        println("=== T. under-reporting / noisy speeds ===")
        for (acc in floatArrayOf(5f, 10f, 25f, 35f)) {
            scenario("T1 walk 1.0 reported 0.9/1.1 acc $acc", SyntheticRun.fixes(acc, 1f, speedMps = 1.0).mapIndexed { i, f -> f.copy(speedMps = if (i % 2 == 0) 0.9f else 1.1f) }, 1.0 * 1199)
            scenario("T4 walk 1.2 reported 0.99, 1.2 every 3rd acc $acc", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).mapIndexed { i, f -> f.copy(speedMps = if (i % 3 == 0) 1.2f else 0.99f) }, truthWalk)
            scenario("T4 walk 1.2 reported 1.2/0.9 alternating acc $acc", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).mapIndexed { i, f -> f.copy(speedMps = if (i % 2 == 0) 1.2f else 0.9f) }, truthWalk)
            scenario("U3 walk 1.2 reported 0.8, 1.2 every 4th acc $acc", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2).mapIndexed { i, f -> f.copy(speedMps = if (i % 4 == 0) 1.2f else 0.8f) }, truthWalk)
            val rnd = Random(7L)
            scenario("T5 walk 1.0 reported N(1.0,0.3) acc $acc", SyntheticRun.fixes(acc, 1f, speedMps = 1.0).map { f -> f.copy(speedMps = (1.0 + rnd.nextGaussian() * 0.3).coerceAtLeast(0.0).toFloat()) }, 1.0 * 1199)
            val rnd2 = Random(11L)
            scenario("U6 walk 1.1 reported N(1.1,0.2) acc $acc", SyntheticRun.fixes(acc, 1.1f, speedMps = 1.1).map { f -> f.copy(speedMps = (1.1 + rnd2.nextGaussian() * 0.2).coerceAtLeast(0.0).toFloat()) }, 1.1 * 1199)
        }
        scenario("T2 walk 0.8 reported 0.8, 1.05 every 10th acc 25", SyntheticRun.fixes(25f, 0.8f, speedMps = 0.8).mapIndexed { i, f -> f.copy(speedMps = if (i % 10 == 0) 1.05f else 0.8f) }, 0.8 * 1199)

        println("=== H. new hostile scenarios ===")
        // H1 accuracy oscillating during the wait
        for (dop in listOf(3f to "dop", 0f to "hop")) for (pat in listOf("25/55" to floatArrayOf(25f, 55f), "55/25" to floatArrayOf(55f, 25f), "21/59" to floatArrayOf(21f, 59f), "60/19" to floatArrayOf(60f, 19f), "30/45/60" to floatArrayOf(30f, 45f, 60f), "58/22" to floatArrayOf(58f, 22f))) {
            scenario("H1 3 m/s ${dop.second} wait accuracy ${pat.first} alternating for 15 s then 25", SyntheticRun.fixes(25f, dop.first, accuracyAt = { i -> if (i < 15) pat.second[i % pat.second.size] else 25f }), truthRun)
            scenario("H1 3 m/s ${dop.second} accuracy ${pat.first} alternating whole run", SyntheticRun.fixes(25f, dop.first, accuracyAt = { i -> pat.second[i % pat.second.size] }), truthRun)
            val w = if (dop.first > 0f) 1.2f else 0f
            scenario("H1 walk 1.2 ${dop.second} wait accuracy ${pat.first} alternating for 15 s then 25", SyntheticRun.fixes(25f, w, speedMps = 1.2, accuracyAt = { i -> if (i < 15) pat.second[i % pat.second.size] else 25f }), truthWalk)
            scenario("H1 walk 1.2 ${dop.second} accuracy ${pat.first} alternating whole run", SyntheticRun.fixes(25f, w, speedMps = 1.2, accuracyAt = { i -> pat.second[i % pat.second.size] }), truthWalk)
        }
        scenario("H1 3 m/s dop accuracy 19/21 alternating", SyntheticRun.fixes(21f, 3f, accuracyAt = { i -> if (i % 2 == 0) 21f else 19f }), truthRun)
        scenario("H1 3 m/s hop accuracy 19/21 alternating", SyntheticRun.fixes(21f, 0f, accuracyAt = { i -> if (i % 2 == 0) 21f else 19f }), truthRun)
        scenario("H1 walk 1.2 dop accuracy 19/21 alternating", SyntheticRun.fixes(21f, 1.2f, speedMps = 1.2, accuracyAt = { i -> if (i % 2 == 0) 21f else 19f }), truthWalk)
        scenario("H1 walk 1.2 hop accuracy 19/21 alternating", SyntheticRun.fixes(21f, 0f, speedMps = 1.2, accuracyAt = { i -> if (i % 2 == 0) 21f else 19f }), truthWalk)
        // H1b: pause every 60 s with oscillating accuracy across the wait (each resume re-enters the wait)
        for (dop in listOf(3f to "dop", 0f to "hop")) scenario("H1b 3 m/s ${dop.second} 55/25 alternating whole run, pause every 60 s", SyntheticRun.fixes(25f, dop.first, accuracyAt = { i -> if (i % 2 == 0) 55f else 25f }), truthRun, gapEveryMs = 60_000L)

        // H2 spike bursts right at wait expiry (already in F7 with 15..18); add 25 m bursts with Doppler at 6 m accuracy
        fun burstAt(range: IntRange, acc: Float, doppler: Float, off: Double) = SyntheticRun.fixes(acc, doppler).mapIndexed { i, f -> if (i in range) f.copy(lon = f.lon + off / DEG_LON) else f }
        for (range in listOf(15..18, 14..18, 15..25, 15..40, 16..19)) for (off in doubleArrayOf(40.0, 100.0)) {
            scenario("H2 25m no dop burst $off m at $range", burstAt(range, 25f, 0f, off), truthRun)
            scenario("H2 25m dop burst $off m at $range", burstAt(range, 25f, 3f, off), truthRun)
            scenario("H2 30m no dop burst $off m at $range", burstAt(range, 30f, 0f, off), truthRun)
        }
        // H2b same bursts after a pause at t=600 (the resume re-enters the wait)
        for (off in doubleArrayOf(40.0, 100.0)) {
            scenario("H2b 25m no dop burst $off m at 615..618 after pause at 600", burstAt(615..618, 25f, 0f, off), truthRun, gapAt = setOf(T0 + 600_000L))
            scenario("H2b 25m dop burst $off m at 615..618 after pause at 600", burstAt(615..618, 25f, 3f, off), truthRun, gapAt = setOf(T0 + 600_000L))
        }

        // H3 speed stuck at 0.99 while walking 1.2
        for (acc in floatArrayOf(5f, 10f, 25f, 35f)) {
            scenario("H3 walk 1.2 reported 0.99 always acc $acc", SyntheticRun.fixes(acc, 0.99f, speedMps = 1.2), truthWalk)
            scenario("H3 walk 1.2 reported 0.99 always, exact 0 every 20th acc $acc", SyntheticRun.fixes(acc, 0.99f, speedMps = 1.2).mapIndexed { i, f -> if (i % 20 == 0) f.copy(speedMps = 0f) else f }, truthWalk)
            scenario("H3 walk 1.2 reported 0.99 always, 1.2 once at t=100 acc $acc", SyntheticRun.fixes(acc, 0.99f, speedMps = 1.2).mapIndexed { i, f -> if (i == 100) f.copy(speedMps = 1.2f) else f }, truthWalk)
            scenario("H3 walk 1.2 reported 1.0 always acc $acc", SyntheticRun.fixes(acc, 1.0f, speedMps = 1.2), truthWalk)
        }

        // H4 pause-resume every 30 s
        for (acc in floatArrayOf(6f, 15f, 25f, 35f)) {
            scenario("H4 3 m/s dop $acc m pause every 30 s", SyntheticRun.fixes(acc, 3f), truthRun, gapEveryMs = 30_000L)
            scenario("H4 3 m/s no dop $acc m pause every 30 s", SyntheticRun.fixes(acc, 0f), truthRun, gapEveryMs = 30_000L)
            scenario("H4 walk 1.2 dop $acc m pause every 30 s", SyntheticRun.fixes(acc, 1.2f, speedMps = 1.2), truthWalk, gapEveryMs = 30_000L)
            scenario("H4 walk 1.2 no dop $acc m pause every 30 s", SyntheticRun.fixes(acc, 0f, speedMps = 1.2), truthWalk, gapEveryMs = 30_000L)
        }
        // H4b: real pauses — fixes during a 20 s pause every 60 s are not fed (the controller ignores them); the walker keeps moving
        run {
            val (fx, tr) = gen(speed = 3.0, acc = { 25f }, doppler = { 3f }, drop = { i -> i % 60 >= 40 })
            val truthFed = 3.0 * fedPairs(fx)   // movement during the dropped fixes is not counted as workout distance
            scenario("H4b 3 m/s dop 25 m, 20 s pause every 60 s (fixes dropped, walker moving)", fx, truthFed, gapAt = fx.filter { ((it.time - T0) / 1000) % 60 == 0L }.map { it.time }.toSet())
            val (fx2, _) = gen(speed = 3.0, acc = { 25f }, doppler = { 0f }, drop = { i -> i % 60 >= 40 })
            scenario("H4b 3 m/s no dop 25 m, 20 s pause every 60 s (fixes dropped)", fx2, truthFed, gapAt = fx2.filter { ((it.time - T0) / 1000) % 60 == 0L }.map { it.time }.toSet())
            val (fx3, _) = gen(speed = 3.0, acc = { 6f }, doppler = { 3f }, drop = { i -> i % 60 >= 40 })
            scenario("H4b 3 m/s dop 6 m, 20 s pause every 60 s (fixes dropped)", fx3, truthFed, gapAt = fx3.filter { ((it.time - T0) / 1000) % 60 == 0L }.map { it.time }.toSet())
        }

        // H5 cyclist 8 m/s with 15 m accuracy, no Doppler (and with)
        for (speed in doubleArrayOf(8.0, 10.0)) for (acc in floatArrayOf(10f, 12f, 15f, 18f)) {
            scenario("H5 $speed m/s no dop acc $acc", SyntheticRun.fixes(acc, 0f, speedMps = speed), speed * 1199)
            scenario("H5 $speed m/s no dop acc $acc sigma 5", SyntheticRun.fixes(acc, 0f, speedMps = speed, jitterSigmaM = 5.0), speed * 1199)
            scenario("H5 $speed m/s dop acc $acc", SyntheticRun.fixes(acc, speed.toFloat(), speedMps = speed), speed * 1199)
        }

        // H6 2-hour hike at 10 m, 1 Hz
        run {
            val (fx, tr) = gen(durationS = 7200, speed = 1.2, acc = { 10f }, doppler = { 1.2f })
            val t0 = System.nanoTime()
            scenario("H6 2 h hike 1.2 m/s dop 10 m", fx, tr)
            println("H6 timing: %.1f ms for 3 trackers x 7200 fixes".format((System.nanoTime() - t0) / 1e6))
            val (fx2, tr2) = gen(durationS = 7200, speed = 1.2, acc = { 10f }, doppler = { 0f })
            scenario("H6 2 h hike 1.2 m/s no dop 10 m", fx2, tr2)
            val (fx3, tr3) = gen(durationS = 7200, speed = 1.2, acc = { 10f }, doppler = { i -> if ((i % 300) < 240) 1.2f else 0f }, moving = { i -> (i % 300) < 240 })
            scenario("H6 2 h hike 1.2 m/s dop 10 m, 60 s stop every 5 min (reports 0)", fx3, tr3)
            val (fx4, tr4) = gen(durationS = 7200, speed = 1.2, acc = { 10f }, doppler = { i -> if ((i % 300) < 240) 1.2f else 0.7f }, moving = { i -> (i % 300) < 240 })
            scenario("H6 2 h hike 1.2 m/s dop 10 m, 60 s stop every 5 min (reports 0.7)", fx4, tr4)
            val (fx5, tr5) = gen(durationS = 7200, speed = 1.2, acc = { 25f }, doppler = { 1.2f }, phi = 0.9)
            scenario("H6 2 h hike 1.2 m/s dop 25 m phi 0.9", fx5, tr5)
            val (fx6, tr6) = gen(durationS = 7200, speed = 3.0, acc = { 5f }, doppler = { 0f })
            scenario("H6 2 h run 3 m/s no dop 5 m", fx6, tr6)
            // internal state after the hike, by reflection: nothing should grow unbounded
            val t = DefaultGpsDistanceTracker()
            for (f in fx) t.addFix(f.time, f.lat, f.lon, f.accuracyM, f.speedMps)
            val fields = listOf("rawChainM", "dopplerIntegralM", "movingIntegralM", "bridgedIntegralM", "unknownDtS", "candidateChainM")
            val state = fields.joinToString { n -> val fld = t.javaClass.getDeclaredField(n); fld.isAccessible = true; "$n=${fld.get(t)}" }
            val wf = t.javaClass.getDeclaredField("window"); wf.isAccessible = true
            println("H6 internal state after 7200 fixes (dop): $state window=${(wf.get(t) as ArrayDeque<*>).size}")
            val t2 = DefaultGpsDistanceTracker()
            for (f in fx2) t2.addFix(f.time, f.lat, f.lon, f.accuracyM, f.speedMps)
            val state2 = fields.joinToString { n -> val fld = t2.javaClass.getDeclaredField(n); fld.isAccessible = true; "$n=${fld.get(t2)}" }
            println("H6 internal state after 7200 fixes (hop): $state2 window=${(wf.get(t2) as ArrayDeque<*>).size}")
        }

        // H7 timestamps going backwards / duplicated
        run {
            for (acc in floatArrayOf(6f, 25f)) for (dop in listOf(3f to "dop", 0f to "hop")) {
                scenario("H7 $acc m ${dop.second} every 50th ts back 2 s", SyntheticRun.fixes(acc, dop.first).mapIndexed { i, f -> if (i > 0 && i % 50 == 0) f.copy(time = f.time - 2000L) else f }, truthRun)
                scenario("H7 $acc m ${dop.second} clock jumps back 5 s at t=600 and stays", SyntheticRun.fixes(acc, dop.first).mapIndexed { i, f -> if (i >= 600) f.copy(time = f.time - 5000L) else f }, truthRun)
                scenario("H7 $acc m ${dop.second} clock jumps forward 5 s at t=600 and stays", SyntheticRun.fixes(acc, dop.first).mapIndexed { i, f -> if (i >= 600) f.copy(time = f.time + 5000L) else f }, truthRun)
                val rnd = Random(3L)
                scenario("H7 $acc m ${dop.second} ts jitter +-400 ms (non-monotonic)", SyntheticRun.fixes(acc, dop.first).map { f -> f.copy(time = f.time + (rnd.nextInt(801) - 400)) }, truthRun)
                scenario("H7 $acc m ${dop.second} every 10th ts duplicated + prev ts (pairs)", SyntheticRun.fixes(acc, dop.first).mapIndexed { i, f -> if (i % 10 == 0 && i > 0) f.copy(time = f.time - 1000L) else f }, truthRun)
                scenario("H7 $acc m ${dop.second} ts back 3 s during the wait (t=5)", SyntheticRun.fixes(acc, dop.first).mapIndexed { i, f -> if (i == 5) f.copy(time = f.time - 3000L) else f }, truthRun)
                scenario("H7 $acc m ${dop.second} every fix duplicated (2 Hz same ts)", SyntheticRun.fixes(acc, dop.first).flatMap { f -> listOf(f, f.copy(lat = f.lat + 1.5 / DEG_LAT)) }, truthRun)
            }
        }

        // H8 short stops with the receiver reporting exactly 0 (traffic lights)
        for (acc in floatArrayOf(5f, 10f, 25f)) for ((walkS, stopS) in listOf(60 to 3, 60 to 5, 60 to 7, 60 to 10, 30 to 5, 30 to 10, 60 to 15)) for (rep in floatArrayOf(0f, 0.3f)) {
            val (fx, tr) = SyntheticRun.walkWithStops(accuracyM = acc, walkS = walkS, stopS = stopS, standingReportedMps = rep)
            scenario("H8 walk 1.2 $walkS s / stop $stopS s acc $acc standing reports $rep", fx, tr)
        }
        for (acc in floatArrayOf(6f, 25f)) for ((walkS, stopS) in listOf(60 to 5, 60 to 10, 120 to 20)) {
            val (fx, tr) = SyntheticRun.walkWithStops(accuracyM = acc, walkSpeedMps = 3.0, walkS = walkS, stopS = stopS, standingReportedMps = 0f)
            scenario("H8 run 3.0 $walkS s / stop $stopS s acc $acc standing reports 0", fx, tr)
        }

        // H9 Doppler lost for good while the speed changes
        for (acc in floatArrayOf(6f, 25f)) for ((before, after) in listOf(3.0 to 2.0, 3.0 to 1.5, 3.0 to 1.2, 1.2 to 0.8, 3.0 to 4.0, 1.2 to 1.8)) {
            val (fx, tr) = gen(speed = before, acc = { acc }, doppler = { i -> if (i < 300) before.toFloat() else 0f }, speedAt = { i -> if (i < 300) before else after })
            scenario("H9 $acc m dop lost at t=300, speed $before -> $after", fx, tr)
        }
        // H9b: Doppler lost for 60 s (not for good) while slowing from 3 to 1.5
        for (acc in floatArrayOf(6f, 25f)) {
            val (fx, tr) = gen(speed = 3.0, acc = { acc }, doppler = { i -> if (i in 300..359) 0f else if (i >= 300) 1.5f else 3f }, speedAt = { i -> if (i < 300) 3.0 else 1.5 })
            scenario("H9b $acc m dop lost 300..359 while slowing 3 -> 1.5", fx, tr)
            val (fx2, tr2) = gen(speed = 3.0, acc = { acc }, doppler = { i -> if (i % 120 in 60..89) 0f else if (i % 120 < 60) 3f else 1.2f }, speedAt = { i -> if (i % 120 < 60) 3.0 else 1.2 })
            scenario("H9c $acc m run 60 s / walk 60 s, dop lost for the first 30 s of each walk", fx2, tr2)
        }

        // H10 a single Doppler glitch on a speed-less receiver
        for (acc in floatArrayOf(6f, 25f)) for (spd in doubleArrayOf(1.2, 0.8, 3.0)) {
            val (fx, tr) = gen(speed = spd, acc = { acc }, doppler = { i -> if (i == 100) 1.2f else 0f })
            scenario("H10 $acc m speed-less receiver, one 1.2 m/s report at t=100, walking $spd", fx, tr)
            val (fx2, tr2) = gen(speed = spd, acc = { acc }, doppler = { i -> if (i % 200 == 100) 1.2f else 0f })
            scenario("H10 $acc m speed-less receiver, 1.2 m/s report every 200 s, walking $spd", fx2, tr2)
        }

        // H11 standing start reported 0 then walking (fill-in), with a Doppler receiver
        for (acc in floatArrayOf(6f, 25f)) for (standS in intArrayOf(15, 60, 300)) {
            val (fx, tr) = gen(speed = 1.2, acc = { acc }, doppler = { i -> if (i < standS) 0f else 1.2f }, moving = { i -> i >= standS })
            scenario("H11 $acc m stand $standS s (reports 0) then walk 1.2 dop", fx, tr)
            val (fx2, tr2) = gen(speed = 3.0, acc = { acc }, doppler = { i -> if (i < standS) 0f else 3f }, moving = { i -> i >= standS })
            scenario("H11 $acc m stand $standS s (reports 0) then run 3.0 dop", fx2, tr2)
        }
        // H11b: dropout for the whole wait while moving (fill-in of real movement)
        for (acc in floatArrayOf(25f, 35f)) {
            val (fx, tr) = gen(speed = 3.0, acc = { acc }, doppler = { i -> if (i < 15) 0f else 3f })
            scenario("H11b $acc m speed 0 for the first 15 s while running 3.0", fx, tr)
            val (fx2, tr2) = gen(speed = 3.0, acc = { acc }, doppler = { i -> if (i < 15) 0f else 3f }, moving = { i -> i >= 15 })
            scenario("H11b $acc m speed 0 for the first 15 s while standing, then running 3.0", fx2, tr2)
        }

        // H12 bogus constant speed while standing (both trackers cap at hop + accuracy)
        for (acc in floatArrayOf(5f, 25f)) {
            val (fx, tr) = gen(speed = 0.0, acc = { acc }, doppler = { 1.2f }, moving = { false })
            scenario("H12 $acc m standing 20 min, receiver reports 1.2 m/s (truth 0)", fx, tr)
        }

        // H13 mixed: 3 m/s run at 25 m with 5 s pauses every minute AND dropouts
        run {
            val (fx, tr) = gen(speed = 3.0, acc = { 25f }, doppler = { i -> if (i % 20 == 0) 0f else 3f }, drop = { i -> i % 60 >= 55 })
            val truthFed = 3.0 * fedPairs(fx)
            scenario("H13 3 m/s dop 25 m, 5 s pause every 60 s (fixes dropped) + 0 every 20th", fx, truthFed, gapAt = fx.filter { ((it.time - T0) / 1000) % 60 == 0L }.map { it.time }.toSet())
        }

        // H14 slow walker exactly at threshold, and a 0.5 m/s stroll
        for (acc in floatArrayOf(6f, 25f)) {
            val (fx, tr) = gen(speed = 0.5, acc = { acc }, doppler = { 0.5f })
            scenario("H14 $acc m stroll 0.5 m/s reported 0.5", fx, tr)
            val (fx2, tr2) = gen(speed = 0.5, acc = { acc }, doppler = { 0f })
            scenario("H14 $acc m stroll 0.5 m/s reported 0 (no Doppler)", fx2, tr2)
        }

        // H15 runner with Doppler at 6 m through a 40 s multipath excursion 100 m off (time escape with Doppler)
        for (acc in floatArrayOf(6f, 25f)) for (len in intArrayOf(35, 45, 90)) {
            scenario("H15 $acc m dop excursion 100 m off for $len s at t=600", SyntheticRun.fixes(acc, 3f).mapIndexed { i, f -> if (i in 600 until 600 + len) f.copy(lon = f.lon + 100.0 / DEG_LON) else f }, truthRun)
            scenario("H15 $acc m no dop excursion 100 m off for $len s at t=600", SyntheticRun.fixes(acc, 0f).mapIndexed { i, f -> if (i in 600 until 600 + len) f.copy(lon = f.lon + 100.0 / DEG_LON) else f }, truthRun)
        }

        // Real walk
        run {
            fun replay(name: String, mimic: Boolean) {
                val b = ZzBaseTracker(); val w = ZzWipTracker(); val n = DefaultGpsDistanceTracker()
                for (p in RealTrack.pixelOutdoorWalk()) {
                    if (mimic && p.time == RealTrackTrackerTest.RESUME_FIX_TIME) { b.markGap(); w.markGap(); n.markGap() }
                    b.addFix(p.time, p.lat, p.lon, p.accuracyM, p.speedMps); w.addFix(p.time, p.lat, p.lon, p.accuracyM, p.speedMps); n.addFix(p.time, p.lat, p.lon, p.accuracyM, p.speedMps)
                }
                n.markGap()
                println("REAL $name base=%.1f wip=%.1f new=%.1f (acc=${n.acceptedCount} jit=${n.rejectedJitterCount} spk=${n.rejectedSpikeCount} def=${n.deferredFirstFixCount} reA=${n.reAnchoredCount} brg=${n.bridgedSpeedCount})".format(b.distanceMeters, w.distanceMeters, n.distanceMeters))
            }
            replay("mimicPause", true)
            replay("noPause", false)
        }
        println("SUMMARY rows=$rows flagged=$flagged")
    }
}
