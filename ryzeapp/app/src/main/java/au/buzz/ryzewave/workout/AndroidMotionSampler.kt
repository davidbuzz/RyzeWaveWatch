package au.buzz.ryzewave.workout

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * [StuckWorkoutMonitor.MotionSource] on the phone's accelerometer, sampled at a few Hz with sensor batching
 * (the hardware FIFO is drained at most every [MAX_REPORT_LATENCY_US]) to keep the cost negligible over a
 * whole workout. Every [BLOCK_SAMPLES] readings (~5 s) the variance of |a| over the block is reported through
 * [MotionVariance]; the monitor treats a block at or above [ActivitySignals.MOTION_STILL_THRESHOLD] as the phone
 * moving with the body. A missing sensor just leaves the MOTION indicator UNKNOWN.
 */
class AndroidMotionSampler(private val context: Context) : StuckWorkoutMonitor.MotionSource {
    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null

    override fun start(onVariance: (time: Long, variance: Double) -> Unit) {
        stop()
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) {
            Log.i(TAG, "no accelerometer: motion stays unknown")
            return
        }
        val variance = MotionVariance(BLOCK_SAMPLES)
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                if (v.size < 3) return
                val magnitude = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
                variance.add(magnitude)?.let { onVariance(System.currentTimeMillis(), it) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            val ok = sm.registerListener(l, sensor, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US)
            if (!ok) {
                Log.w(TAG, "accelerometer register refused: motion stays unknown")
                return
            }
            manager = sm
            listener = l
            Log.i(TAG, "accelerometer sampling started (${1_000_000 / SAMPLING_PERIOD_US} Hz, blocks of $BLOCK_SAMPLES)")
        } catch (e: Exception) {
            Log.w(TAG, "accelerometer register failed: ${e.message}")
        }
    }

    override fun stop() {
        val l = listener ?: return
        try {
            manager?.unregisterListener(l)
            Log.i(TAG, "accelerometer sampling stopped")
        } catch (e: Exception) {
            Log.d(TAG, "accelerometer unregister: ${e.message}")
        }
        listener = null
        manager = null
    }

    companion object {
        const val TAG = "MotionSampler"
        /** 4 Hz: plenty to see whether the phone is moving at all. */
        const val SAMPLING_PERIOD_US = 250_000
        /** Let the sensor hub batch up to 10 s of readings before waking the CPU. */
        const val MAX_REPORT_LATENCY_US = 10_000_000
        /** 20 readings at 4 Hz = one 5 s block per variance value. */
        const val BLOCK_SAMPLES = 20
    }
}
