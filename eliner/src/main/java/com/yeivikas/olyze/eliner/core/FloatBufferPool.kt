package com.yeivikas.olyze.eliner.core

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Lock-free pool of reusable fixed-size `FloatArray`s.
 *
 * Extracted in Fase 5 (DSP Foundation), which needs the exact same
 * pooling mechanic `com.yeivikas.olyze.eliner.modules.audio.AudioBufferPool`
 * (Fase 4) already implements. Two constraints ruled out simply importing
 * that class directly:
 * - `AudioBufferPool` lives in `eliner.modules.audio` (Audio Engine). The
 *   architecture places DSP Foundation *below* Audio Engine
 *   ("UI → API → Runtime → Audio Engine → DSP Foundation") — if DSP
 *   Foundation imported something from `modules.audio` now, and a future
 *   phase (correctly) has Audio Engine import from DSP Foundation to
 *   actually use it, that would be a guaranteed package cycle.
 * - Retrofitting `AudioBufferPool` to move or extend it touches
 *   already-shipped Fase 4 code without a compiler available to
 *   re-verify the change — the same reasoning `StateMachine<S>` and
 *   `ConnectionGraph<N>` already established for not touching earlier
 *   phases' code.
 *
 * So the underlying *pattern* (lock-free `ConcurrentLinkedQueue`, exactly
 * because a real-time audio/DSP path must never block) is reused via this
 * new shared utility in `eliner.core`, the one layer nothing else needs
 * to avoid depending on. `AudioBufferPool` itself is left untouched.
 */
class FloatBufferPool(private val bufferSize: Int) {
    private val pool = ConcurrentLinkedQueue<FloatArray>()

    /** Returns a buffer of exactly [bufferSize] samples — reused if available, freshly allocated otherwise. Never blocks. */
    fun acquire(): FloatArray = pool.poll() ?: FloatArray(bufferSize)

    /** Returns [buffer] to the pool. Silently discarded if its size doesn't match [bufferSize]. */
    fun release(buffer: FloatArray) {
        if (buffer.size != bufferSize) return
        pool.offer(buffer)
    }

    /** Number of buffers currently pooled, ready for reuse. */
    fun pooledCount(): Int = pool.size

    /** Discards every pooled buffer. */
    fun clear() {
        pool.clear()
    }
}
