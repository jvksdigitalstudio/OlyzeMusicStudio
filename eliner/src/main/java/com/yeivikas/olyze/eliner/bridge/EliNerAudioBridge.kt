package com.yeivikas.olyze.eliner.bridge

import android.util.Log
import com.yeivikas.olyze.eliner.api.EliNerAudioApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kotlin/JNI bridge to the native EliNer audio engine core (Oboe C++).
 * All audio processing runs on the native audio thread at ultra-low latency.
 *
 * This class is an implementation detail of the EliNer Engine Core layer.
 * Outside code should depend on [EliNerAudioApi], not on this class directly.
 */
class EliNerAudioBridge private constructor() : EliNerAudioApi {

    private val _isRunning    = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning

    private val _sampleRate   = MutableStateFlow(48000)
    override val sampleRate: StateFlow<Int> = _sampleRate

    private val _bufferSize   = MutableStateFlow(0)
    override val bufferSize: StateFlow<Int> = _bufferSize

    private val _cpuLoad      = MutableStateFlow(0f)
    override val cpuLoad: StateFlow<Float> = _cpuLoad

    private val _activeVoices = MutableStateFlow(0)
    override val activeVoices: StateFlow<Int> = _activeVoices

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun start(): Boolean {
        val ok = nativeCreate()
        _isRunning.value = ok
        if (ok) {
            _sampleRate.value = nativeGetSampleRate()
            _bufferSize.value = nativeGetBufferSize()
            Log.i(TAG, "Oboe engine started — SR=${_sampleRate.value} Buffer=${_bufferSize.value}")
        } else {
            Log.e(TAG, "Failed to start Oboe engine")
        }
        return ok
    }

    override fun stop() {
        nativeDestroy()
        _isRunning.value = false
        Log.i(TAG, "Oboe engine stopped")
    }

    override fun refreshStats() {
        _cpuLoad.value      = nativeGetCpuLoad()
        _activeVoices.value = nativeGetActiveVoices()
        _bufferSize.value   = nativeGetBufferSize()
    }

    // ── MIDI ───────────────────────────────────────────────────────────────

    override fun noteOn (channel: Int, note: Int, velocity: Int) = nativeNoteOn(channel, note, velocity)
    override fun noteOff(channel: Int, note: Int)                = nativeNoteOff(channel, note)
    override fun allNotesOff()                                   = nativeAllNotesOff()
    override fun sendCC (channel: Int, cc: Int, value: Int)      = nativeSendCC(channel, cc, value)
    override fun setPitchBend(channel: Int, semitones: Float)    = nativeSetPitchBend(channel, semitones)

    // ── Master controls ────────────────────────────────────────────────────

    override fun setMasterVolume (v: Float)  = nativeSetMasterVolume(v)
    override fun setReverbMix    (m: Float)  = nativeSetReverbMix(m)
    override fun setDelayMix     (m: Float)  = nativeSetDelayMix(m)
    override fun setDelayTime    (s: Float)  = nativeSetDelayTime(s)
    override fun setDelayFeedback(f: Float)  = nativeSetDelayFeedback(f)

    // ── JNI declarations ───────────────────────────────────────────────────

    private external fun nativeCreate(): Boolean
    private external fun nativeDestroy()

    private external fun nativeNoteOn(channel: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(channel: Int, note: Int)
    private external fun nativeAllNotesOff()
    private external fun nativeSendCC(channel: Int, cc: Int, value: Int)
    private external fun nativeSetPitchBend(channel: Int, semitones: Float)

    private external fun nativeSetMasterVolume(volume: Float)
    private external fun nativeSetReverbMix(mix: Float)
    private external fun nativeSetDelayMix(mix: Float)
    private external fun nativeSetDelayTime(seconds: Float)
    private external fun nativeSetDelayFeedback(feedback: Float)

    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetBufferSize(): Int
    private external fun nativeGetCpuLoad(): Float
    private external fun nativeGetActiveVoices(): Int

    companion object {
        private const val TAG = "EliNerAudioBridge"

        // Singleton — exposed through the EliNerAudioApi contract so callers
        // never depend on this concrete implementation.
        @Volatile private var instance: EliNerAudioBridge? = null
        fun getInstance(): EliNerAudioApi = instance ?: synchronized(this) {
            instance ?: EliNerAudioBridge().also { instance = it }
        }

        init {
            System.loadLibrary("eliner_audio_core")
        }
    }
}
