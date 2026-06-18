// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Streams accelerometer data at ~25 Hz via Android SensorManager.
 * Open class so tests can substitute a fake.
 */
open class MotionSensor {

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
                        timestampMs = System.currentTimeMillis(),
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
        // SENSOR_DELAY_GAME ≈ 20ms (~50 Hz), closest standard rate to 25 Hz
        manager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)

        awaitClose {
            manager.unregisterListener(sensorListener)
            listener = null
        }
    }

    /** Stop streaming. */
    open fun stopStreaming() {
        val manager = sensorManager ?: return
        val l = listener ?: return
        manager.unregisterListener(l)
        listener = null
    }
}
