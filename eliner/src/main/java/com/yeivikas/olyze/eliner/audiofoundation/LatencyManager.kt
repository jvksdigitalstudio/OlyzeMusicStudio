package com.yeivikas.olyze.eliner.audiofoundation

import com.yeivikas.olyze.eliner.services.CapabilityProvider
import com.yeivikas.olyze.eliner.services.PerformanceProfile
import com.yeivikas.olyze.eliner.services.PerformanceProfileProvider

/** Latency profiles this phase distinguishes — matching [PerformanceProfile]'s tiers conceptually, but scoped to audio latency specifically. */
enum class LatencyProfile { LOW, BALANCED, HIGH_STABILITY }

/**
 * A read-only report of what latency the current configuration implies —
 * not a target being enforced, just information. "No optimizar todavía.
 * Solo construir la infraestructura."
 */
data class LatencyReport(
    val profile: LatencyProfile,
    val estimatedOutputLatencyMillis: Float?,
)

/**
 * Reports the current [LatencyProfile] and its estimated real-world
 * latency, derived from [PerformanceProfileProvider] (which profile is
 * active) and [CapabilityProvider] (what that means in milliseconds on
 * this device — reusing
 * [com.yeivikas.olyze.eliner.services.DeviceCapabilities.estimatedOutputLatencyMillis],
 * already computed in Fase 2, not recomputed here).
 *
 * Both dependencies are interfaces, never the concrete
 * `PerformanceProfileManager`/`DeviceCapabilityManager`.
 */
class LatencyManager(
    private val capabilityProvider: CapabilityProvider,
    private val performanceProfileProvider: PerformanceProfileProvider,
) {
    /** Current latency profile and its estimated latency on this device. */
    fun currentReport(): LatencyReport {
        val profile = when (performanceProfileProvider.activeProfile.value) {
            PerformanceProfile.ULTRA -> LatencyProfile.LOW
            PerformanceProfile.COMPATIBILITY -> LatencyProfile.HIGH_STABILITY
            PerformanceProfile.AUTOMATIC, PerformanceProfile.MANUAL -> LatencyProfile.BALANCED
        }
        return LatencyReport(
            profile = profile,
            estimatedOutputLatencyMillis = capabilityProvider.detect().estimatedOutputLatencyMillis,
        )
    }
}
