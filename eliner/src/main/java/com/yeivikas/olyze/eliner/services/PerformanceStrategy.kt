package com.yeivikas.olyze.eliner.services

/**
 * A pluggable rule for whether [profile] is the right choice given a
 * device's [DeviceCapabilities]. [PerformanceProfileManager] holds an
 * ordered list of these instead of one inline `when` block — the concrete
 * benefit is [CustomStrategy]: a future white-label build (or a future
 * settings screen) can inject its own strategy without
 * [PerformanceProfileManager] itself changing.
 */
interface PerformanceStrategy {
    val profile: PerformanceProfile

    /** Whether this strategy's [profile] is recommended for [capabilities]. */
    fun isRecommended(capabilities: DeviceCapabilities): Boolean
}

/**
 * Favors stability on lower-end/older devices. Checked first — if a device
 * is this constrained, no other strategy should override the
 * recommendation, regardless of registration order.
 */
class CompatibilityStrategy : PerformanceStrategy {
    override val profile = PerformanceProfile.COMPATIBILITY

    override fun isRecommended(capabilities: DeviceCapabilities): Boolean {
        val ramGigabytes = capabilities.totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        return capabilities.cpuCoreCount <= 4 || ramGigabytes < 3.0
    }
}

/** Favors maximum performance on high-end devices, at higher resource cost. */
class UltraStrategy : PerformanceStrategy {
    override val profile = PerformanceProfile.ULTRA

    override fun isRecommended(capabilities: DeviceCapabilities): Boolean {
        val ramGigabytes = capabilities.totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        return capabilities.cpuCoreCount >= 8 &&
            ramGigabytes >= 6.0 &&
            capabilities.audioLowLatencySupported
    }
}

/**
 * The fallback strategy — recommended whenever no more specific strategy
 * claims a device. Always returns `true` so it can sit last in the
 * evaluation order and catch everything [CompatibilityStrategy]/
 * [UltraStrategy] didn't.
 */
class AutomaticStrategy : PerformanceStrategy {
    override val profile = PerformanceProfile.AUTOMATIC
    override fun isRecommended(capabilities: DeviceCapabilities): Boolean = true
}

/**
 * User- or integrator-defined strategy. Never auto-recommended
 * ([isRecommended] always `false`) — [PerformanceProfile.MANUAL] is only
 * ever reached via [PerformanceProfileManager.setProfile], not via
 * [PerformanceProfileManager.recommendedProfile]. Exists so a future
 * settings screen (or a future white-label integrator) has a real,
 * documented extension point instead of needing to modify
 * [PerformanceProfileManager] itself to add a fifth profile.
 */
class CustomStrategy : PerformanceStrategy {
    override val profile = PerformanceProfile.MANUAL
    override fun isRecommended(capabilities: DeviceCapabilities): Boolean = false
}
