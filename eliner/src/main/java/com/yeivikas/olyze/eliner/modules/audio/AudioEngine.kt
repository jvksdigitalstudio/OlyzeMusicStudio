package com.yeivikas.olyze.eliner.modules.audio

import com.yeivikas.olyze.eliner.api.AudioEngineApi
import com.yeivikas.olyze.eliner.api.AudioEngineState
import com.yeivikas.olyze.eliner.api.AudioMetricsSnapshot
import com.yeivikas.olyze.eliner.api.DspApi
import com.yeivikas.olyze.eliner.api.RuntimeContext
import com.yeivikas.olyze.eliner.audiofoundation.AudioFoundationContext
import com.yeivikas.olyze.eliner.core.EliNerModule
import com.yeivikas.olyze.eliner.core.EngineErrorSeverity
import kotlinx.coroutines.flow.StateFlow

/**
 * EliNer's real Audio Engine — the first functional engine module, built
 * on Core Foundation (Fase 1), Foundation Services (Fase 2), Runtime
 * Foundation (Fase 2.5), and Audio Foundation (Fase 3).
 *
 * Responsible for: initializing the audio system, opening the session,
 * maintaining the processing cycle, stopping the engine correctly,
 * releasing resources. **No DSP** — nothing here processes a single
 * sample; that's explicitly future work.
 *
 * Implements two contracts:
 * - [EliNerModule] (Core Foundation, Fase 1) — this is the **first real
 *   module** ever registered through `ModuleRegistry`/`ModuleLoader`,
 *   which until now only had generic infrastructure and zero real
 *   content to manage.
 * - [AudioEngineApi] (`eliner.api`) — the public contract UI/app code
 *   will use, never this class directly.
 *
 * Composes (never recreates) everything it needs from two existing
 * contexts: [audioFoundation] (Fase 3 — session, backend, format, sample
 * rate, buffer, clock, latency, routing, channels) and [runtimeContext]
 * (Fase 2.5 — logger, events, thread manager, performance profile,
 * capability, configuration). No duplicate references, no new interfaces
 * invented where an existing one already covers the need.
 *
 * Runs its own work on [com.yeivikas.olyze.eliner.services.ExecutionLane.AUDIO]
 * — the dedicated single-thread executor `ThreadManager` (Fase 2) already
 * provides. **This phase deliberately does not create a second "Audio
 * Thread" mechanism** — that lane already is a hilo dedicado, independiente
 * del resto, que nunca comparte responsabilidades con otros servicios,
 * exactly matching the spec's "Audio Thread" requirement. A second
 * implementation would have been the duplication this phase's own audit
 * explicitly forbids.
 */
class AudioEngine(
    private val audioFoundation: AudioFoundationContext,
    private val runtimeContext: RuntimeContext,
) : EliNerModule, AudioEngineApi {

    override val id: String = "audio-engine"
    override val displayName: String = "EliNer Audio Engine"

    private val streamController = AudioStreamController(runtimeContext.events)

    /** Backend-agnostic callback registration — see [AudioCallback]. Not dispatched by anything in this phase. */
    val callbacks = AudioCallbackRegistry()

    /** The fixed signal-flow stage order — see [AudioPipeline]. */
    val pipeline = AudioPipeline()

    private val bufferPool = AudioBufferPool(
        bufferSizeFrames = audioFoundation.buffer.bufferSizeFrames.value,
        channelCount = audioFoundation.channels.layout.value.channelCount,
    )

    private val errorManager = AudioErrorManager(runtimeContext.logger)
    private val metrics = AudioMetrics()

    val performanceMonitor = AudioPerformanceMonitor(
        performanceProfileProvider = runtimeContext.performanceProfileProvider,
        bufferManager = audioFoundation.buffer,
        latencyManager = audioFoundation.latency,
        configuration = runtimeContext.configuration,
    )

    /**
     * Optional link to DSP Foundation, added in Fase 5. Nullable and
     * additive — nothing about `AudioEngine`'s constructor, existing
     * methods, or behavior for callers that never set this changes.
     * When set: [initialize] also initializes it (best-effort — a DSP
     * Foundation failure is reported via [AudioErrorManager] but does not
     * block the audio engine's own startup), and [shutdown] also shuts it
     * down. See `docs/adr/0009-dsp-foundation.md`.
     */
    var dsp: DspApi? = null

    override val state: StateFlow<AudioEngineState> = streamController.state

    override fun initialize(): Boolean {
        if (!audioFoundation.session.initialize()) {
            errorManager.reportError(
                code = "AUDIO_SESSION_INIT_FAILED",
                message = "Audio session failed to initialize.",
                severity = EngineErrorSeverity.ERROR,
            )
            return false
        }
        dsp?.let { dspApi ->
            if (!dspApi.initialize()) {
                errorManager.reportError(
                    code = "AUDIO_DSP_INIT_FAILED",
                    message = "DSP Foundation failed to initialize; continuing without it.",
                    severity = EngineErrorSeverity.WARNING,
                )
            }
        }
        return streamController.initialize()
    }

    override fun start(): Boolean = streamController.start()

    override fun pause(): Boolean = streamController.pause()

    override fun resume(): Boolean = streamController.resume()

    override fun stop(): Boolean = streamController.stop()

    override fun flush(): Boolean {
        if (!streamController.canFlush()) return false
        audioFoundation.clock.reset()
        bufferPool.clear()
        return true
    }

    override fun restart(): Boolean {
        metrics.reset()
        return streamController.restart()
    }

    override fun shutdown(): Boolean {
        if (streamController.state.value != AudioEngineState.STOPPED) {
            streamController.stop()
        }
        dsp?.shutdown()
        bufferPool.clear()
        return audioFoundation.session.close()
    }

    override fun metricsSnapshot(): AudioMetricsSnapshot = metrics.snapshot()

    // ── EliNerModule ──────────────────────────────────────────────────

    override fun onStart() {
        check(initialize()) { "AudioEngine failed to initialize." }
        check(start()) { "AudioEngine failed to start." }
    }

    override fun onStop() {
        stop()
        shutdown()
    }
}
