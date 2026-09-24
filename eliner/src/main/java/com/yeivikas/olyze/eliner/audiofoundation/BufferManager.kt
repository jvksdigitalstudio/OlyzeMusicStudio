package com.yeivikas.olyze.eliner.audiofoundation

import com.yeivikas.olyze.eliner.services.CapabilityProvider
import com.yeivikas.olyze.eliner.services.PerformanceProfile
import com.yeivikas.olyze.eliner.services.PerformanceProfileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the audio buffer size (in frames). This is the concrete
 * "Audio Capability Integration" requirement: buffer size is derived from
 * [PerformanceProfileProvider]'s active profile and
 * [CapabilityProvider]'s reported native buffer size — both interfaces,
 * never the concrete `PerformanceProfileManager`/`DeviceCapabilityManager`.
 *
 * "Debe permitir que el motor pueda utilizar posteriormente distintos
 * tamaños de buffer según: perfil de rendimiento, hardware, latencia,
 * configuración del usuario" — profile and hardware are wired here;
 * latency is [LatencyManager]'s concern (which this class doesn't need to
 * know about); user configuration is a future
 * `Configuration`-backed override, not added speculatively in this phase.
 */
class BufferManager(
    private val capabilityProvider: CapabilityProvider,
    private val performanceProfileProvider: PerformanceProfileProvider,
) {
    private val nativeFramesPerBuffer = capabilityProvider.detect().nativeFramesPerBuffer

    private val _bufferSizeFrames = MutableStateFlow(recommendedBufferSizeFrames())
    val bufferSizeFrames: StateFlow<Int> = _bufferSizeFrames.asStateFlow()

    /** Explicitly overrides the buffer size. */
    fun setBufferSizeFrames(frames: Int) {
        require(frames > 0) { "Buffer size must be positive, got $frames." }
        _bufferSizeFrames.value = frames
    }

    /** Recomputes the buffer size from the current performance profile and reapplies it. */
    fun applyRecommended() {
        _bufferSizeFrames.value = recommendedBufferSizeFrames()
    }

    /**
     * Frames per buffer recommended for [PerformanceProfileProvider.activeProfile]:
     * - [PerformanceProfile.ULTRA]: the device's own native buffer size
     *   (smallest safe value the hardware itself reports) — lowest latency.
     * - [PerformanceProfile.COMPATIBILITY]: double the native size (or a
     *   safe fallback) — trades latency for stability headroom.
     * - [PerformanceProfile.AUTOMATIC]/[PerformanceProfile.MANUAL]: the
     *   native size as-is, the same value Oboe itself would default to.
     */
    private fun recommendedBufferSizeFrames(): Int {
        val native = nativeFramesPerBuffer ?: FALLBACK_BUFFER_FRAMES
        return when (performanceProfileProvider.activeProfile.value) {
            PerformanceProfile.ULTRA -> native
            PerformanceProfile.COMPATIBILITY -> native * 2
            PerformanceProfile.AUTOMATIC, PerformanceProfile.MANUAL -> native
        }
    }

    private companion object {
        /** Used only when the platform doesn't report a native buffer size. */
        const val FALLBACK_BUFFER_FRAMES = 256
    }
}
