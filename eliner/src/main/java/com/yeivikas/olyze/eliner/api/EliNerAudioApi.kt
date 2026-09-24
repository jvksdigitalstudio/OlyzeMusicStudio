package com.yeivikas.olyze.eliner.api

import com.yeivikas.olyze.eliner.services.PerformanceProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * EliNer API Layer — Audio Contract
 * ──────────────────────────────────
 * This is the ONLY surface the UI / ViewModel layer is allowed to depend on
 * for audio functionality. It hides the concrete engine implementation
 * (currently [com.yeivikas.olyze.eliner.bridge.EliNerAudioBridge], a JNI
 * bridge into the native Oboe-based engine core) behind a stable interface.
 *
 * Why this exists (Fase 3 — Preparación para arquitectura profesional):
 *   UI → EliNer API Layer → EliNer Engine Core → Módulos independientes
 *
 * Consequences of this contract:
 *   - The engine core (native C++, DSP modules, mixer, etc.) can be
 *     rewritten, replaced, or extended without touching UI/ViewModel code,
 *     as long as it keeps honoring this interface.
 *   - New engine backends (e.g. a future pure-Kotlin/AAudio fallback, or a
 *     mocked implementation for tests/previews) can be swapped in simply by
 *     providing another implementation of [EliNerAudioApi].
 *
 * This interface intentionally mirrors the feature set already implemented
 * by the existing native engine (voices, master FX, transport-adjacent
 * queries). It does NOT add new engine capabilities — it only formalizes
 * the boundary that already existed implicitly.
 */
interface EliNerAudioApi {

    // ── Engine status (read-only state exposed to UI) ──
    val isRunning: StateFlow<Boolean>
    val sampleRate: StateFlow<Int>
    val bufferSize: StateFlow<Int>
    val cpuLoad: StateFlow<Float>
    val activeVoices: StateFlow<Int>

    /**
     * Cumulative dropped control-thread commands (Fase 6 §3-6) — a healthy
     * engine stays at 0. Non-zero doesn't mean audio glitched (dropped
     * commands are typically superseded parameter changes), but sustained
     * growth is worth investigating.
     */
    val droppedCommands: StateFlow<Long>

    /** Oboe's own xrun counter for the current stream. */
    val xrunCount: StateFlow<Int>

    /**
     * Bitmask of conditions the audio thread has flagged since the last
     * [clearErrors] (Fase 6 §24) — never a thrown exception, always a
     * polled flag. Refreshed by [refreshStats].
     */
    val lastError: StateFlow<EngineErrorFlags>

    /** Clears [lastError] back to [EngineErrorFlags.NONE]. */
    fun clearErrors()

    // ── Lifecycle ──
    // profile: reused from com.yeivikas.olyze.eliner.services.PerformanceProfile
    // (Fase 2.5) rather than a new engine-local enum — see Fase 6 §14-15.
    // It selects the native buffer-size strategy (Compatibility = more
    // headroom, Ultra = minimum latency); it does NOT change sample rate or
    // format. Defaults to AUTOMATIC so existing call sites (`audio.start()`)
    // keep compiling unchanged. Callers that care about device-appropriate
    // buffer sizing should pass the result of a
    // PerformanceProfileManager.applyRecommended() call — see MainViewModel.
    fun start(profile: PerformanceProfile = PerformanceProfile.AUTOMATIC): Boolean
    fun stop()
    fun refreshStats()

    // ── Note / MIDI-level control ──
    fun noteOn(channel: Int, note: Int, velocity: Int)
    fun noteOff(channel: Int, note: Int)
    fun allNotesOff()
    fun sendCC(channel: Int, cc: Int, value: Int)
    fun setPitchBend(channel: Int, semitones: Float)

    // ── Master / FX controls ──
    // Legacy fixed-target API (pre-Fase-7) — still works exactly as
    // before. See the native AudioEngine::setReverbMix/etc. doc comments:
    // internally these now route through the dynamic FX chain below by
    // module type, and are a safe no-op if that module has been removed
    // via [removeModule].
    fun setMasterVolume(volume: Float)
    fun setReverbMix(mix: Float)
    fun setDelayMix(mix: Float)
    fun setDelayTime(seconds: Float)
    fun setDelayFeedback(feedback: Float)

    // ── Dynamic FX chain (Fase 7 — DSP Graph real) ──
    // This is the vertical slice from the Fase 6 → Fase 7 handoff (ADR
    // 0010): a single channel (the engine's one existing signal path —
    // see AudioEngine.cpp) with a REAL, dynamically loadable/reorderable
    // effect chain. Calling [insertModule] here changes what the native
    // audio thread actually renders on its next callback — this is not a
    // UI-only mock to be wired up later, unlike the disconnected
    // `eliner.modules.audio`/`eliner.dspfoundation` stack (ADR 0010).
    //
    // Slots are ordered (slot 0 processes first) and asynchronous: a call
    // here queues a command for the audio thread, same as every other
    // control here (e.g. [setMasterVolume]) — it does not block, and does
    // not guarantee the change is audible before the function returns.
    // Defaults to Reverb@0 + Delay@1 (identical behavior to before this
    // phase); every other slot starts empty.

    /** Fixed slot count for the dynamic FX chain — see [DspModuleType]. */
    val maxChainSlots: Int

    /**
     * Loads a new [type] module into [slot], replacing whatever was
     * there. Returns false without changing engine state if [slot] is out
     * of range, or if [type] has no native implementation yet (only
     * [DspModuleType.REVERB]/[DspModuleType.DELAY] exist today — see
     * `DspModuleFactory.cpp`).
     */
    fun insertModule(slot: Int, type: DspModuleType): Boolean

    /** Clears whatever module occupies [slot], if any. */
    fun removeModule(slot: Int): Boolean

    /**
     * Relocates the module at [fromSlot] to [toSlot] — this is how a
     * FL-Studio-Mobile-style channel rack UI would implement reordering
     * a chain by drag/drop.
     */
    fun moveModule(fromSlot: Int, toSlot: Int): Boolean

    /**
     * Sets a module-local parameter (see [ReverbParam]/[DelayParam]) on
     * whichever module currently occupies [slot].
     */
    fun setModuleParameter(slot: Int, paramId: Int, value: Float)

    /**
     * What module type currently occupies [slot], from the caller's own
     * most-recently-issued perspective (see the native
     * `AudioEngine::getModuleType` doc comment for the exact consistency
     * guarantee — it's the same one every other async control here has).
     */
    fun getModuleType(slot: Int): DspModuleType
}
