// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Streams accelerometer data at ~25 Hz via Android SensorManager.
 * Open class so tests can substitute a fake.
 */
open class MotionSensor {

    private companion object {
        // Explicit ~25 Hz sampling (the SDK's target rate) instead of
        // SENSOR_DELAY_GAME (~50 Hz), which over-sampled 2× beyond spec.
        const val SAMPLING_PERIOD_US = 40_000 // 25 Hz
        // Let the sensor hardware FIFO batch ~1 s of samples and deliver them in
        // a burst so the application processor can stay asleep between bursts
        // instead of waking every 40 ms. This is the real Doze/standby battery
        // win; devices without an accel FIFO ignore it and deliver live.
        const val MAX_REPORT_LATENCY_US = 1_000_000 // 1 s
    }

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    /** Initialize with application context. */
    fun init(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    /** Whether the device has an accelerometer. */
    val isAvailable: Boolean
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /** Flow of motion samples from the watch accelerometer. */
    open fun motionFlow(): Flow<MotionSample> = callbackFlow {
        val manager = sensorManager
            ?: throw IllegalStateException("MotionSensor not initialized. Call init(context) first.")

        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: throw IllegalStateException("Accelerometer not available on this device.")

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Convert from m/s² to g-force
                val x = event.values[0].toDouble() / SensorManager.GRAVITY_EARTH
                val y = event.values[1].toDouble() / SensorManager.GRAVITY_EARTH
                val z = event.values[2].toDouble() / SensorManager.GRAVITY_EARTH
                trySend(
                    MotionSample(
                        // Derive each sample's wall-clock time from the hardware
                        // event timestamp (boot-nanos), NOT delivery time. With
                        // FIFO batching a whole burst arrives at once, so
                        // System.currentTimeMillis() at delivery would collapse
                        // the batch onto ~one instant and corrupt the time series.
                        timestampMs = eventTimestampToEpochMs(event.timestamp),
                        x = x,
                        y = y,
                        z = z,
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Accuracy changes handled implicitly
            }
        }

        listener = sensorListener
        // ~25 Hz with up to 1 s of hardware FIFO batching (see companion).
        manager.registerListener(
            sensorListener,
            accelerometer,
            SAMPLING_PERIOD_US,
            MAX_REPORT_LATENCY_US,
        )

        awaitClose {
            manager.unregisterListener(sensorListener)
            listener = null
        }
    }

    /**
     * Convert a SensorEvent's hardware timestamp (nanoseconds on the
     * SystemClock.elapsedRealtimeNanos / boot timebase) to epoch millis by
     * anchoring against the current wall clock. Keeps batched samples correctly
     * spaced in real time.
     */
    private fun eventTimestampToEpochMs(eventNanos: Long): Long {
        val bootNanos = SystemClock.elapsedRealtimeNanos()
        val ageMs = (bootNanos - eventNanos) / 1_000_000
        return System.currentTimeMillis() - ageMs
    }

    /** Stop streaming. */
    open fun stopStreaming() {
        val manager = sensorManager ?: return
        val l = listener ?: return
        manager.unregisterListener(l)
        listener = null
    }
}
