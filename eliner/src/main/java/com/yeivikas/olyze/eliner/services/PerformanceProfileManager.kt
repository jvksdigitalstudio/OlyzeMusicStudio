package com.yeivikas.olyze.eliner.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The four profiles named explicitly in the Fase 2 spec — no more, no fewer. */
enum class PerformanceProfile {
    /** Engine picks automatically, based on [DeviceCapabilities]. */
    AUTOMATIC,

    /** Favors stability over raw performance on lower-end/older devices. */
    COMPATIBILITY,

    /** Favors maximum performance on high-end devices, at higher resource cost. */
    ULTRA,

    /** User has overridden individual settings directly — this manager stops recommending. */
    MANUAL,
}

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

/**
 * Internal service backing the future performance-profile picker UI (not
 * built in this phase — "la interfaz gráfica NO pertenece a esta fase").
 *
 * Depends on [CapabilityProvider] (the interface [DeviceCapabilityManager]
 * implements), not the concrete class — same DIP treatment as
 * [TaskScheduler] depending on [TaskExecutor], and for the same reason:
 * this relationship (recommend a profile *from* detected capabilities) is
 * explicitly what the spec asks for, so it's not an arbitrary
 * service-to-service shortcut, and depending on the interface keeps this
 * testable without a real [android.content.Context].
 *
 * Recommendation logic is a [PerformanceStrategy] list, not an inline
 * `when` — see `PerformanceStrategy.kt` for why (Fase 2.5's "Performance
 * Strategy" requirement). [strategies] defaults to the three built-in
 * strategies plus [CustomStrategy]; a caller can pass a different list
 * (e.g. with an additional custom strategy) without subclassing this
 * manager.
 */
class PerformanceProfileManager(
    private val capabilityProvider: CapabilityProvider,
    private val strategies: List<PerformanceStrategy> = listOf(
        CompatibilityStrategy(),
        UltraStrategy(),
        CustomStrategy(),
        AutomaticStrategy(), // last: always matches, guaranteed fallback
    ),
) : PerformanceProfileProvider {
    private val _activeProfile = MutableStateFlow(PerformanceProfile.AUTOMATIC)
    override val activeProfile: StateFlow<PerformanceProfile> = _activeProfile.asStateFlow()

    /** Explicitly sets the active profile — e.g. from a future settings screen. */
    override fun setProfile(profile: PerformanceProfile) {
        _activeProfile.value = profile
    }

    /**
     * Computes which profile [strategies] would pick, without changing
     * [activeProfile]. Exposed separately so a future settings UI can show
     * "Recommended: Ultra" even while a different profile is active.
     *
     * Evaluates [strategies] in order and returns the first match. The
     * default order matters: [CustomStrategy] never matches
     * ([CustomStrategy.isRecommended] is always `false`), so it's inert
     * here regardless of position — it only ever takes effect via
     * [setProfile]. [AutomaticStrategy] is last on purpose: it always
     * matches, so anything after it would be unreachable.
     */
    override fun recommendedProfile(): PerformanceProfile {
        val capabilities = capabilityProvider.detect()
        val match = strategies.firstOrNull { it.isRecommended(capabilities) }
        return match?.profile ?: PerformanceProfile.AUTOMATIC
    }

    /** Sets [activeProfile] to [recommendedProfile]'s result and returns it. */
    override fun applyRecommended(): PerformanceProfile {
        val recommended = recommendedProfile()
        setProfile(recommended)
        return recommended
    }
}
