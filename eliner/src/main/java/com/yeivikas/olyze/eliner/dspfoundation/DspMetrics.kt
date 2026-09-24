package com.yeivikas.olyze.eliner.dspfoundation

import com.yeivikas.olyze.eliner.api.DspMetricsSnapshot

/**
 * Tracks DSP processing time, node count, buffer count, and estimated
 * usage — "sin interfaz gráfica. Solo infraestructura." Every field
 * starts at zero/default; nothing here is simulated. A future real
 * scheduler/executor would call [recordProcessingDuration]/[recordCpuUsage];
 * nothing does yet.
 *
 * `@Volatile`, not `AtomicLong`, for the two mutable fields — a single
 * expected writer (the eventual DSP processing path) and many readers,
 * same discipline already used by
 * `com.yeivikas.olyze.eliner.modules.audio.AudioMetrics` (Fase 4).
 */
class DspMetrics {
    @Volatile private var lastProcessingDurationNanos: Long = 0
    @Volatile private var estimatedCpuUsagePercent: Float = 0f

    fun recordProcessingDuration(nanos: Long) { lastProcessingDurationNanos = nanos }
    fun recordCpuUsage(percent: Float) { estimatedCpuUsagePercent = percent }

    /** A snapshot combining this class's own counters with [nodeCount]/[pooledBufferCount] read from elsewhere. */
    fun snapshot(nodeCount: Int, pooledBufferCount: Int): DspMetricsSnapshot = DspMetricsSnapshot(
        nodeCount = nodeCount,
        pooledBufferCount = pooledBufferCount,
        lastProcessingDurationNanos = lastProcessingDurationNanos,
        estimatedCpuUsagePercent = estimatedCpuUsagePercent,
    )

    fun reset() {
        lastProcessingDurationNanos = 0
        estimatedCpuUsagePercent = 0f
    }
}
