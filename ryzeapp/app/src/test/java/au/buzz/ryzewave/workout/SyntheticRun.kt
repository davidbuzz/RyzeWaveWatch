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
            val north = SPEED_MPS * i + nN
            val east = nE
            if (gapFromS >= 0 && i >= gapFromS && i < gapFromS + gapLengthS) continue
            out += SyntheticFix(
                time = T0 + i * 1000L,
                lat = LAT0 + north / DEG_LAT_M,
                lon = LON0 + east / degLonM,
                accuracyM = accuracyM,
                speedMps = dopplerSpeedMps,
            )
        }
        return out
    }
}
