package ai.synheart.core.edge.sensor

data class MotionSample(
    val timestampMs: Long,
    val x: Double,  // g-force (divided by 9.81 from m/s²)
    val y: Double,
    val z: Double,
)
