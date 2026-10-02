package com.yeivikas.olyze.eliner.audiofoundation

import com.yeivikas.olyze.eliner.services.CapabilityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The contract [AudioClock] depends on instead of [SampleRateManager] directly. */
interface SampleRateProvider {
    val currentSampleRateHz: StateFlow<Int>
}

/**
 * Manages the engine's current sample rate. "Debe permitir trabajar
 * posteriormente con diferentes Sample Rates sin modificar el resto del
 * motor" — the mechanism for that is: everything else depends on
 * [SampleRateProvider] (an interface), never reads a hardcoded rate.
 *
 * Depends on [CapabilityProvider] (Fase 2 interface, not the concrete
 * `DeviceCapabilityManager`) to seed the initial rate from
 * [com.yeivikas.olyze.eliner.services.DeviceCapabilities.nativeOutputSampleRateHz]
 * — the device's actual native rate — falling back to
 * [DEFAULT_SAMPLE_RATE_HZ] if the platform didn't report one.
 */
class SampleRateManager(capabilityProvider: CapabilityProvider) : SampleRateProvider {
    private val nativeRate = capabilityProvider.detect().nativeOutputSampleRateHz

    private val _currentSampleRateHz = MutableStateFlow(nativeRate ?: DEFAULT_SAMPLE_RATE_HZ)
    override val currentSampleRateHz: StateFlow<Int> = _currentSampleRateHz.asStateFlow()

    /**
     * Attempts to change the sample rate to [hz]. Only rates in
     * [SUPPORTED_SAMPLE_RATES_HZ] are accepted — an unsupported value is
     * rejected (returns `false`) rather than silently applied, since
     * asking the audio hardware for an arbitrary rate is far more likely
     * to fail or resample unexpectedly than to succeed.
     */
    fun setSampleRate(hz: Int): Boolean {
        if (hz !in SUPPORTED_SAMPLE_RATES_HZ) return false
        _currentSampleRateHz.value = hz
        return true
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000

        /** Sample rates in common professional/consumer use — not exhaustive by design, just the realistic set. */
        val SUPPORTED_SAMPLE_RATES_HZ = setOf(44_100, 48_000, 88_200, 96_000)
    }
}
