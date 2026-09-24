package com.yeivikas.olyze.eliner.dspfoundation

/**
 * A block of audio to be processed by a [DspProcessor] — "una
 * representación profesional de un bloque de procesamiento... permitir
 * futuras operaciones vectoriales."
 *
 * Planar layout on purpose: [channel] returns a contiguous `FloatArray`
 * for one channel at a time, rather than an interleaved single array.
 * Contiguous same-channel samples are exactly what future SIMD/NEON
 * vectorization needs to operate on efficiently — this phase doesn't use
 * any vector instructions, but the memory layout is chosen now so a
 * future DSP algorithm can, without this class changing shape.
 *
 * `Modo Studio` (64-bit float) is intentionally not modeled as a second
 * code path here — `DspFrame` only ever holds 32-bit `FloatArray`s.
 * Kotlin's `Float`/`FloatArray` mean genuine 64-bit support would need a
 * parallel `DoubleArray`-based type, which is real implementation work,
 * not infrastructure — deferred to whichever phase actually builds Modo
 * Studio processing, reusing
 * `com.yeivikas.olyze.eliner.audiofoundation.ProcessingSampleFormat`
 * (Fase 3) as the format tag rather than inventing a second one now.
 */
class DspFrame(
    val frameCount: Int,
    val channelCount: Int,
    private val channels: Array<FloatArray>,
) {
    init {
        require(channelCount == channels.size) {
            "channelCount ($channelCount) must match the number of channel arrays (${channels.size})."
        }
        channels.forEach { channel ->
            require(channel.size == frameCount) {
                "Every channel array must have exactly $frameCount samples, found ${channel.size}."
            }
        }
    }

    /** The samples for [index] (0-based), as a contiguous array of [frameCount] samples. */
    fun channel(index: Int): FloatArray = channels[index]

    /** Zeroes every sample in every channel — used by flush/reset paths. */
    fun clear() {
        channels.forEach { it.fill(0f) }
    }

    companion object {
        /** Allocates a fresh [DspFrame] with [channelCount] freshly-zeroed channels of [frameCount] samples each. */
        fun allocate(frameCount: Int, channelCount: Int): DspFrame =
            DspFrame(frameCount, channelCount, Array(channelCount) { FloatArray(frameCount) })
    }
}
