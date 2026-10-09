package com.yeivikas.olyze.eliner.services

import com.yeivikas.olyze.eliner.api.audio.PerformanceProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * The contract [com.yeivikas.olyze.eliner.runtime.RuntimeContext] should
 * depend on instead of [PerformanceProfileManager] directly. Added in
 * Fase 2.5, same reasoning as `eliner.diagnostics.Logger`.
 */
interface PerformanceProfileProvider {
    val activeProfile: StateFlow<PerformanceProfile>
    fun setProfile(profile: PerformanceProfile)
    fun recommendedProfile(): PerformanceProfile
    fun applyRecommended(): PerformanceProfile
}
