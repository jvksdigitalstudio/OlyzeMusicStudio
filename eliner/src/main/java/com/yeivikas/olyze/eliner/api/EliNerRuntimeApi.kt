package com.yeivikas.olyze.eliner.api

import kotlinx.coroutines.flow.StateFlow

/**
 * EliNer Runtime API — the only surface UI/app code is allowed to depend
 * on for controlling the engine's overall lifecycle, per Fase 2.5's
 * architecture rule:
 *
 * ```
 * UI
 *     ↓
 * EliNer API      ← this file
 *     ↓
 * Runtime         ← com.yeivikas.olyze.eliner.runtime.EliNerRuntime (implementation)
 *     ↓
 * Foundation Services
 *     ↓
 * Motores (sin implementar)
 * ```
 *
 * Same pattern already established by [EliNerAudioApi] in Fase 1: the UI
 * depends on this interface, never on `EliNerRuntime` the concrete class,
 * and never reaches into Core Foundation or any Foundation Service
 * directly — everything is reached through [context].
 */
interface EliNerRuntimeApi {
    /** Current runtime lifecycle state. */
    val state: StateFlow<RuntimeState>

    /** Shared service references — the only path to Logger/Configuration/Resources/etc. */
    val context: RuntimeContext

    /** Where future modules and other code discover services by contract. */
    val services: ServiceRegistry

    /** Where future modules (Audio, DSP, MIDI, ...) will be registered. */
    val modules: ModuleLoader

    /** Boots the engine: Core Foundation, then every registered module. */
    fun initialize(): Boolean

    /** Suspends a running engine. */
    fun pause(): Boolean

    /** Resumes a paused engine. */
    fun resume(): Boolean

    /** Shuts down the engine and releases its execution resources. */
    fun shutdown(): Boolean
}
