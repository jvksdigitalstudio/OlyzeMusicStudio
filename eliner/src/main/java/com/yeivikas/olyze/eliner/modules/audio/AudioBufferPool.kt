package com.yeivikas.olyze.eliner.modules.audio

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Pool of reusable `FloatArray` buffers — "Audio Buffer Flow... evitar
 * copias innecesarias. Preparar intercambio eficiente entre módulos
 * futuros."
 *
 * Backed by [ConcurrentLinkedQueue], not `synchronized` — deliberately.
 * Every other registry in this project ([com.yeivikas.olyze.eliner.core.ModuleRegistry],
 * [com.yeivikas.olyze.eliner.resources.ResourceManager], etc.) uses a
 * blocking `synchronized` lock, which is fine because none of them are
 * ever touched from a real-time path. **This one is different on purpose**:
 * it exists specifically to be used from the eventual audio callback path,
 * where Fase 4's own rule #2 ("cero bloqueos... nunca bloquear el Audio
 * Thread") applies directly. `ConcurrentLinkedQueue.poll()`/`.offer()` are
 * lock-free, so [acquire]/[release] never block, even under contention.
 *
 * Fixed dimensions at construction — if the buffer size changes (e.g. via
 * [com.yeivikas.olyze.eliner.audiofoundation.BufferManager.setBufferSizeFrames]),
 * a new pool should be constructed rather than resized in place. Handling
 * a live resize safely on a real-time path is real DSP-adjacent
 * complexity, explicitly out of scope this phase.
 */
class AudioBufferPool(
    private val bufferSizeFrames: Int,
    private val channelCount: Int,
) {
    private val pool = ConcurrentLinkedQueue<FloatArray>()

    /** Total samples per buffer — `frames × channels`, interleaved layout. */
    val samplesPerBuffer: Int = bufferSizeFrames * channelCount

    /**
     * Returns a buffer of exactly [samplesPerBuffer] samples — reused from
     * the pool if one is available, freshly allocated otherwise. Never
     * blocks.
     */
    fun acquire(): FloatArray = pool.poll() ?: FloatArray(samplesPerBuffer)

    /**
     * Returns [buffer] to the pool for reuse. Silently discards it (does
     * not throw) if its size doesn't match [samplesPerBuffer] — accepting
     * a mismatched buffer into the pool would hand a wrongly-sized array
     * to the next [acquire] caller, a much worse failure than dropping it.
     */
    fun release(buffer: FloatArray) {
        if (buffer.size != samplesPerBuffer) return
        pool.offer(buffer)
    }

    /** Number of buffers currently sitting in the pool, ready for reuse. */
    fun pooledCount(): Int = pool.size

    /** Discards every pooled buffer. */
    fun clear() {
        pool.clear()
    }
}
