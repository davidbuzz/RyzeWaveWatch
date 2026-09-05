package au.buzz.ryzewave.workout

import java.util.Random
import kotlin.math.cos

/**
 * A synthetic 20-minute run for the GPS tests: 1 Hz fixes along a straight line due north at 3.0 m/s
 * (1200 fixes, 3600 m true distance) with Gaussian position jitter (sigma 2 m on each axis, seeded so the
 * numbers are reproducible). Optionally a contiguous block of fixes is dropped (a GPS outage).
 */
data class SyntheticFix(val time: Long, val lat: Double, val lon: Double, val accuracyM: Float, val speedMps: Float)

object SyntheticRun {
    const val LAT0 = -27.4698
    const val LON0 = 153.0251
    const val DEG_LAT_M = 111_194.927
    const val SPEED_MPS = 3.0
    const val DURATION_S = 1200
    const val TRUE_DISTANCE_M = SPEED_MPS * DURATION_S      // 3600 m
    const val JITTER_SIGMA_M = 2.0
    const val T0 = 1_700_000_000_000L

    fun fixes(
        accuracyM: Float,
        dopplerSpeedMps: Float,
        gapFromS: Int = -1,
        gapLengthS: Int = 0,
        seed: Long = 42L,
        jitterSigmaM: Double = JITTER_SIGMA_M,
        /**
         * Temporal correlation of the jitter, AR(1): 0.0 = every fix has fresh independent noise (worst case for a
         * hop-summing tracker), 0.9 = the noise drifts slowly the way a Kalman-smoothed provider output does.
         * The stationary sigma is [jitterSigmaM] either way.
         */
        phi: Double = 0.0,
        /** True speed along the line (the reported Doppler speed is [dopplerSpeedMps], 0 = no receiver speed). */
        speedMps: Double = SPEED_MPS,
        /**
         * Reported accuracy of fix i: the default is the constant [accuracyM]; a pattern models a phone stepping
         * between accuracy bands (every 10th fix better, alternating bands, 5 s bands…).
         */
        accuracyAt: (Int) -> Float = { accuracyM },
        /**
         * Multipath spikes: every [spikeEvery]-th fix (i > 0) is displaced [spikeOffsetM] east of the line and, when
         * [spikeReportedMps] is given, reports that speed instead of [dopplerSpeedMps]. 0 = no spikes.
         */
        spikeEvery: Int = 0,
        spikeOffsetM: Double = 25.0,
        spikeReportedMps: Float? = null,
    ): List<SyntheticFix> {
        val rnd = Random(seed)
        val degLonM = DEG_LAT_M * cos(Math.toRadians(LAT0))
        val out = ArrayList<SyntheticFix>(DURATION_S)
        val innovation = jitterSigmaM * kotlin.math.sqrt(1.0 - phi * phi)
        var nN = rnd.nextGaussian() * jitterSigmaM
        var nE = rnd.nextGaussian() * jitterSigmaM
        for (i in 0 until DURATION_S) {
            if (i > 0) {
                nN = phi * nN + rnd.nextGaussian() * innovation
                nE = phi * nE + rnd.nextGaussian() * innovation
            }
            val spike = spikeEvery > 0 && i > 0 && i % spikeEvery == 0
            val north = speedMps * i + nN
            val east = nE + if (spike) spikeOffsetM else 0.0
            if (gapFromS >= 0 && i >= gapFromS && i < gapFromS + gapLengthS) continue
            out += SyntheticFix(
                time = T0 + i * 1000L,
                lat = LAT0 + north / DEG_LAT_M,
                lon = LON0 + east / degLonM,
                accuracyM = accuracyAt(i),
                speedMps = if (spike) spikeReportedMps ?: dopplerSpeedMps else dopplerSpeedMps,
            )
        }
        return out
    }

    /**
     * A walk with stops: [walkS] seconds at [walkSpeedMps], then [stopS] seconds standing still, repeated for
     * 20 minutes. While standing the receiver reports [standingReportedMps] (a hand-held phone under trees says
     * 0.5–0.9 m/s while nothing moves) and the position scatters with the same jitter as when walking. The truth
     * is the walking time × speed; the list's true length is returned with the fixes.
     */
    fun walkWithStops(
        accuracyM: Float,
        walkSpeedMps: Double = 1.2,
        walkS: Int = 60,
        stopS: Int = 60,
        standingReportedMps: Float = 0.7f,
        seed: Long = 42L,
        jitterSigmaM: Double = JITTER_SIGMA_M,
    ): Pair<List<SyntheticFix>, Double> {
        val rnd = Random(seed)
        val degLonM = DEG_LAT_M * cos(Math.toRadians(LAT0))
        val out = ArrayList<SyntheticFix>(DURATION_S)
        var north = 0.0
        var truth = 0.0
        for (i in 0 until DURATION_S) {
            val walking = (i % (walkS + stopS)) < walkS
            if (i > 0 && walking) {
                north += walkSpeedMps
                truth += walkSpeedMps
            }
            out += SyntheticFix(
                time = T0 + i * 1000L,
                lat = LAT0 + (north + rnd.nextGaussian() * jitterSigmaM) / DEG_LAT_M,
                lon = LON0 + (rnd.nextGaussian() * jitterSigmaM) / degLonM,
                accuracyM = accuracyM,
                speedMps = if (walking) walkSpeedMps.toFloat() else standingReportedMps,
            )
        }
        return out to truth
    }
}
