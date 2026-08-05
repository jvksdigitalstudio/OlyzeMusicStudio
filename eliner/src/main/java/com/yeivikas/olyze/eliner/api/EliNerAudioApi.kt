package com.yeivikas.olyze.eliner.api

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

    // ── Lifecycle ──
    fun start(): Boolean
    fun stop()
    fun refreshStats()

    // ── Note / MIDI-level control ──
    fun noteOn(channel: Int, note: Int, velocity: Int)
    fun noteOff(channel: Int, note: Int)
    fun allNotesOff()
    fun sendCC(channel: Int, cc: Int, value: Int)
    fun setPitchBend(channel: Int, semitones: Float)

    // ── Master / FX controls ──
    fun setMasterVolume(volume: Float)
    fun setReverbMix(mix: Float)
    fun setDelayMix(mix: Float)
    fun setDelayTime(seconds: Float)
    fun setDelayFeedback(feedback: Float)
}
