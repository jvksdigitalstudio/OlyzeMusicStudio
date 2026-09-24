package com.yeivikas.olyze.eliner.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The only surface allowed to control DSP Foundation, per Fase 5's
 * architecture rule:
 *
 * ```
 * UI
 *     ↓
 * EliNer API      ← this file
 *     ↓
 * Runtime
 *     ↓
 * Audio Engine
 *     ↓
 * DSP Foundation  ← com.yeivikas.olyze.eliner.dspfoundation.DspFoundation (implementation)
 * ```
 *
 * "La UI nunca deberá comunicarse directamente con el DSP" — same pattern
 * already established by [EliNerAudioApi], [EliNerRuntimeApi], and
 * [AudioEngineApi]: depend on this interface, never on the concrete
 * implementation.
 */
interface DspApi {
    /** Current DSP lifecycle state. */
    val state: StateFlow<DspState>

    /** Prepares the DSP subsystem, bringing it to [DspState.READY]. Registers no processors — that's future work. */
    fun initialize(): Boolean

    /** Stops (if needed) and releases every resource DSP Foundation holds. */
    fun shutdown(): Boolean

    /** Current metrics — node count, pooled buffer count, timing, estimated CPU usage. */
    fun metricsSnapshot(): DspMetricsSnapshot
}
