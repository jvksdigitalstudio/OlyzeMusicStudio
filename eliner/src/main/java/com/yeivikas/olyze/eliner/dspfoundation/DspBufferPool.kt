package com.yeivikas.olyze.eliner.dspfoundation

import com.yeivikas.olyze.eliner.core.FloatBufferPool

/**
 * Pool of reusable [DspFrame]s — "debe reutilizar el sistema de
 * AudioBufferPool cuando sea posible. Evitar copias innecesarias." Built
 * on `com.yeivikas.olyze.eliner.core.FloatBufferPool` (this phase's own
 * extraction of the same lock-free pooling pattern
 * `com.yeivikas.olyze.eliner.modules.audio.AudioBufferPool` established in
 * Fase 4 — see that class's doc comment for why this isn't a direct
 * import of `AudioBufferPool` itself).
 *
 * Pools one channel-sized `FloatArray` per channel — [acquireFrame] draws
 * [channelCount] arrays from the underlying pool and wraps them in a
 * [DspFrame]; [releaseFrame] returns all of them.
 */
class DspBufferPool(
    private val frameCount: Int,
    private val channelCount: Int,
) {
    private val channelPool = FloatBufferPool(frameCount)

    fun acquireFrame(): DspFrame =
        DspFrame(frameCount, channelCount, Array(channelCount) { channelPool.acquire() })

    fun releaseFrame(frame: DspFrame) {
        for (index in 0 until channelCount) {
            channelPool.release(frame.channel(index))
        }
    }

    /** Number of channel-sized buffers currently pooled, ready for reuse. */
    fun pooledCount(): Int = channelPool.pooledCount()

    fun clear() = channelPool.clear()
}
