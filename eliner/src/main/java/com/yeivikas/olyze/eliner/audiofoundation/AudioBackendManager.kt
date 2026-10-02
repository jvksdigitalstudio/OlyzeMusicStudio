package com.yeivikas.olyze.eliner.audiofoundation

import com.yeivikas.olyze.eliner.services.DeviceCapabilities

/**
 * The native backends EliNer's audio engine can eventually run on. All
 * three are accessed through Oboe (already the native dependency, see
 * `eliner/CMakeLists.txt`) — Oboe itself picks between AAudio and OpenSL
 * ES at the native layer when asked for [AUTO]. [AudioBackendManager]
 * exists to decide *which one to ask Oboe for*, based on device
 * capabilities, before any native code runs.
 */
enum class AudioBackend {
    /** AAudio — Android's modern low-latency audio API (API 26+). */
    AAUDIO,

    /** OpenSL ES — available on every supported Android version (back to API 16). */
    OPENSL_ES,

    /** Let Oboe decide at runtime (its own internal AAudio-with-OpenSL-ES-fallback logic). */
    AUTO,
}

/**
 * Decides which [AudioBackend] to request, without forcing one — "la
 * selección deberá ser automática según la versión de Android y las
 * capacidades del dispositivo... no forzar todavía un backend."
 *
 * Pure policy: this class holds no state and touches no native code. It
 * answers one question — nothing here opens a stream or loads a backend.
 */
class AudioBackendManager {

    /**
     * Recommends a backend for a device reporting [androidSdkInt] and
     * [capabilities].
     *
     * - Below API 26 (AAudio's minimum): [AudioBackend.OPENSL_ES] — AAudio
     *   doesn't exist yet on these devices.
     * - API 26+ with low-latency audio support
     *   ([DeviceCapabilities.audioLowLatencySupported]): [AudioBackend.AAUDIO]
     *   — the modern path, and the one Oboe itself prefers when available.
     * - API 26+ without confirmed low-latency support: [AudioBackend.AUTO]
     *   — let Oboe's own runtime fallback logic decide rather than
     *   guessing wrong in either direction.
     */
    fun recommendedBackend(androidSdkInt: Int, capabilities: DeviceCapabilities): AudioBackend = when {
        androidSdkInt < AAUDIO_MIN_SDK -> AudioBackend.OPENSL_ES
        capabilities.audioLowLatencySupported -> AudioBackend.AAUDIO
        else -> AudioBackend.AUTO
    }

    private companion object {
        const val AAUDIO_MIN_SDK = 26
    }
}
