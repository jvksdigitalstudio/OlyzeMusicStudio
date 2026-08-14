package com.yeivikas.olyze.eliner.api

/**
 * A point-in-time snapshot of Audio Engine metrics — what a future
 * diagnostics screen would render. Every field starts at zero and is only
 * ever incremented by real code; nothing here is fabricated.
 *
 * Lives in `eliner.api` for the same reason as [AudioEngineState] — it's
 * part of [AudioEngineApi]'s public surface, so it needs to be resolvable
 * without depending on `eliner.modules.audio`.
 */
data class AudioMetricsSnapshot(
    val xruns: Long,
    val underruns: Long,
    val overruns: Long,
    val bufferMisses: Long,
    val lastCallbackDurationNanos: Long,
    val lastProcessingDurationNanos: Long,
    val audioCpuLoadPercent: Float,
)
