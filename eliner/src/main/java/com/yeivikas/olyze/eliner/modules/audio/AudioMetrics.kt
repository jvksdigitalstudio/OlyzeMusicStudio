package com.yeivikas.olyze.eliner.modules.audio

import com.yeivikas.olyze.eliner.api.AudioMetricsSnapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks XRuns, underruns, overruns, buffer misses, and timing — "no
 * mostrar interfaz. Solo infraestructura." Every counter starts at zero;
 * nothing here is simulated. A future real audio callback would call the
 * `record*` methods; nothing does yet, since no real callback exists in
 * this phase.
 *
 * [AtomicLong] for counters (safe increments from any thread without
 * blocking) and `@Volatile` for the two duration/CPU fields (single
 * expected writer — the eventual audio thread — many readers), matching
 * the same "never block a real-time path" discipline as [AudioBufferPool].
 */
class AudioMetrics {
    private val xruns = AtomicLong(0)
    private val underruns = AtomicLong(0)
    private val overruns = AtomicLong(0)
    private val bufferMisses = AtomicLong(0)

    @Volatile private var lastCallbackDurationNanos: Long = 0
    @Volatile private var lastProcessingDurationNanos: Long = 0
    @Volatile private var audioCpuLoadPercent: Float = 0f

    fun recordXrun() { xruns.incrementAndGet() }
    fun recordUnderrun() { underruns.incrementAndGet() }
    fun recordOverrun() { overruns.incrementAndGet() }
    fun recordBufferMiss() { bufferMisses.incrementAndGet() }
    fun recordCallbackDuration(nanos: Long) { lastCallbackDurationNanos = nanos }
    fun recordProcessingDuration(nanos: Long) { lastProcessingDurationNanos = nanos }
    fun recordCpuLoad(percent: Float) { audioCpuLoadPercent = percent }

    /** A consistent point-in-time snapshot of every metric. */
    fun snapshot(): AudioMetricsSnapshot = AudioMetricsSnapshot(
        xruns = xruns.get(),
        underruns = underruns.get(),
        overruns = overruns.get(),
        bufferMisses = bufferMisses.get(),
        lastCallbackDurationNanos = lastCallbackDurationNanos,
        lastProcessingDurationNanos = lastProcessingDurationNanos,
        audioCpuLoadPercent = audioCpuLoadPercent,
    )

    /** Resets every counter to zero — e.g. after [AudioStreamController.restart]. */
    fun reset() {
        xruns.set(0)
        underruns.set(0)
        overruns.set(0)
        bufferMisses.set(0)
        lastCallbackDurationNanos = 0
        lastProcessingDurationNanos = 0
        audioCpuLoadPercent = 0f
    }
}
