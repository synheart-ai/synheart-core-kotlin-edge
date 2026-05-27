package ai.synheart.core.edge.engine

import ai.synheart.core.edge.sensor.MotionSample
import kotlin.math.sqrt

/**
 * Thread-safe accumulator for motion data within a frame interval.
 * Tracks sum of squared magnitudes and sample count, then computes
 * RMS acceleration on demand and resets.
 *
 * Useful when a host emits its own session-level frame summaries (e.g.
 * a wear app showing live motion intensity alongside HSI) and needs an
 * efficient O(1)-per-sample reducer rather than buffering every sample.
 * The engine itself doesn't currently consume this — motion is piped
 * directly into the runtime via `MotionSensor` → `RuntimeBridge.pushAccel`
 * — but it's a small utility worth shipping next to the sensor types
 * since accumulation is the obvious next step after "got a sample".
 */
class MotionAccumulator {

    private var sumSq: Double = 0.0
    private var count: Int = 0

    /** Add a motion sample — accumulates x² + y² + z² into running sum. */
    @Synchronized
    fun add(sample: MotionSample) {
        val magSq = sample.x * sample.x + sample.y * sample.y + sample.z * sample.z
        sumSq += magSq
        count++
    }

    /** Compute RMS g-force and reset. Returns null if no samples accumulated. */
    @Synchronized
    fun computeAndReset(): Pair<Double, Int>? {
        if (count == 0) return null
        val rms = sqrt(sumSq / count)
        val n = count
        sumSq = 0.0
        count = 0
        return Pair(rms, n)
    }
}
