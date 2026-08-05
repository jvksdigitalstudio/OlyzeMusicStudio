package com.yeivikas.olyze.eliner.api

/**
 * A point-in-time snapshot of DSP Foundation metrics — "tiempo de
 * procesamiento, cantidad de nodos, cantidad de buffers, uso estimado."
 * No graphical interface reads this in this phase; it's the shape a
 * future one would.
 */
data class DspMetricsSnapshot(
    val nodeCount: Int,
    val pooledBufferCount: Int,
    val lastProcessingDurationNanos: Long,
    val estimatedCpuUsagePercent: Float,
)
