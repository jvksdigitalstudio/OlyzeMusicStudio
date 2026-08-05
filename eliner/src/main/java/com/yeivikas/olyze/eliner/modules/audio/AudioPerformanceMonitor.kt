package com.yeivikas.olyze.eliner.modules.audio

import com.yeivikas.olyze.eliner.audiofoundation.BufferManager
import com.yeivikas.olyze.eliner.audiofoundation.LatencyManager
import com.yeivikas.olyze.eliner.configuration.Configuration
import com.yeivikas.olyze.eliner.services.PerformanceProfile
import com.yeivikas.olyze.eliner.services.PerformanceProfileProvider

/** A read-only combination of what the active performance profile currently implies for audio. */
data class AudioPerformanceRecommendation(
    val profile: PerformanceProfile,
    val recommendedBufferFrames: Int,
    val estimatedLatencyMillis: Float?,
)

/**
 * Monitors — never modifies — the relationship between the active
 * [com.yeivikas.olyze.eliner.services.PerformanceProfile] and the Audio
 * Engine's current configuration. "El objetivo será adaptar
 * automáticamente el comportamiento del motor según el perfil activo...
 * no modificar todavía buffers dinámicamente. Solo preparar la
 * arquitectura."
 *
 * [bufferManager]/[latencyManager] are held concretely, matching the same
 * choice already made by
 * [com.yeivikas.olyze.eliner.audiofoundation.AudioFoundationContext] in
 * Fase 3 (both live in the same composition, no second implementation of
 * either exists to justify an extracted interface).
 * [performanceProfileProvider]/[configuration] stay interfaces, consistent
 * with how every other Foundation Service integration in this project is
 * done.
 */
class AudioPerformanceMonitor(
    private val performanceProfileProvider: PerformanceProfileProvider,
    private val bufferManager: BufferManager,
    private val latencyManager: LatencyManager,
    private val configuration: Configuration,
) {
    /** Current recommendation — read-only, changes nothing. */
    fun currentRecommendation(): AudioPerformanceRecommendation = AudioPerformanceRecommendation(
        profile = performanceProfileProvider.activeProfile.value,
        recommendedBufferFrames = bufferManager.bufferSizeFrames.value,
        estimatedLatencyMillis = latencyManager.currentReport().estimatedOutputLatencyMillis,
    )

    /**
     * Whether automatic adaptation is enabled — reads
     * `"audio.performance.autoAdapt"` from [Configuration], defaulting to
     * `true` since no explicit value has ever been set. A future phase
     * that actually adapts buffer size in response to profile changes
     * would gate that behavior on this flag; nothing in this phase acts
     * on it yet.
     */
    fun isAutoAdaptEnabled(): Boolean =
        configuration.getBoolean(AUTO_ADAPT_CONFIG_KEY, default = true)

    private companion object {
        const val AUTO_ADAPT_CONFIG_KEY = "audio.performance.autoAdapt"
    }
}
