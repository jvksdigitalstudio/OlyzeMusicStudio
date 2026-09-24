package com.yeivikas.olyze.eliner.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The only surface UI/app code is allowed to depend on for controlling
 * the Audio Engine, per Fase 4's architecture rule:
 *
 * ```
 * UI
 *     ↓
 * EliNer API      ← this file
 *     ↓
 * Runtime
 *     ↓
 * Audio Engine    ← com.yeivikas.olyze.eliner.modules.audio.AudioEngine (implementation)
 * ```
 *
 * Same pattern as [EliNerAudioApi] (Fase 1) and [EliNerRuntimeApi]
 * (Fase 2.5): the UI depends on this interface, never on `AudioEngine` the
 * concrete class.
 *
 * Deliberately does **not** expose the underlying
 * `com.yeivikas.olyze.eliner.audiofoundation.AudioFoundationContext` (session,
 * devices, backend, etc.) — this API is scoped to engine *control*, not to
 * every Audio Foundation internal. It also keeps `eliner.api` from needing
 * to depend on `eliner.audiofoundation`, which would recreate the same
 * kind of package cycle already fixed once (see ADR 0006).
 */
interface AudioEngineApi {
    /** Current engine lifecycle state. */
    val state: StateFlow<AudioEngineState>

    /** Prepares the engine: opens the audio session, brings it to [AudioEngineState.READY]. Does not start audio flow. */
    fun initialize(): Boolean

    /** Starts the processing cycle. */
    fun start(): Boolean

    /** Suspends processing without releasing resources. */
    fun pause(): Boolean

    /** Resumes a paused engine. */
    fun resume(): Boolean

    /** Stops the processing cycle. Resources remain allocated — see [shutdown] to release them. */
    fun stop(): Boolean

    /**
     * Clears any in-flight buffered audio and resets the audio clock.
     * Only valid while [AudioEngineState.RUNNING] or [AudioEngineState.PAUSED]
     * — returns `false` otherwise.
     */
    fun flush(): Boolean

    /** Stops (if needed) and starts a fresh processing cycle. */
    fun restart(): Boolean

    /** Stops the engine (if needed) and releases every resource it holds. */
    fun shutdown(): Boolean

    /** Current metrics — xruns, underruns, overruns, buffer misses, timing, CPU load. */
    fun metricsSnapshot(): AudioMetricsSnapshot
}
