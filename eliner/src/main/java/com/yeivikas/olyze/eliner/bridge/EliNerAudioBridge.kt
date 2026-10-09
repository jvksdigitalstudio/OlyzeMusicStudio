package com.yeivikas.olyze.eliner.bridge

import android.util.Log
import com.yeivikas.olyze.eliner.api.audio.DspModuleType
import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.audio.EngineErrorFlags
import com.yeivikas.olyze.eliner.api.audio.PerformanceProfile
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

    private val _droppedCommands = MutableStateFlow(0L)
    override val droppedCommands: StateFlow<Long> = _droppedCommands

    private val _xrunCount = MutableStateFlow(0)
    override val xrunCount: StateFlow<Int> = _xrunCount

    private val _lastError = MutableStateFlow(EngineErrorFlags.NONE)
    override val lastError: StateFlow<EngineErrorFlags> = _lastError

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Se invoca tras CADA creación exitosa del motor nativo (primer arranque o
     * cualquier `stop()` + `start()` posterior hecho a través de la API pública).
     *
     * Existe porque `nativeCreate` construye un `AudioEngine` NUEVO y el estado
     * del dominio de transporte (tempo, metrónomo, sync del delay…) vive en el
     * motor nativo: sin esta notificación, reiniciar el audio lo perdería en
     * silencio. Ver `TransportController.syncToNative` (ADR 0028).
     *
     * Se llama en el hilo que ejecuta `start()` (el despachador), con el motor
     * ya creado y `gEngineMutex` liberado. El listener debe ser RÁPIDO y no
     * bloquear: el arranque espera a que termine (el de transporte solo marca el
     * estado como pendiente y deja el envío a su propio hilo). Una excepción del
     * listener se registra y NO hace fallar el arranque.
     */
    @Volatile private var engineCreatedListener: (() -> Unit)? = null

    internal fun setEngineCreatedListener(listener: (() -> Unit)?) {
        engineCreatedListener = listener
    }

    override fun start(profile: PerformanceProfile): Boolean {
        // MANUAL has no per-field override plumbing yet on the native side
        // (see AudioEngine.h) — falls back to the same buffer strategy as
        // AUTOMATIC rather than silently picking an arbitrary one.
        val nativeProfile = when (profile) {
            PerformanceProfile.AUTOMATIC    -> 0
            PerformanceProfile.COMPATIBILITY -> 1
            PerformanceProfile.ULTRA        -> 2
            PerformanceProfile.MANUAL       -> 3
        }
        val ok = nativeCreate(nativeProfile)
        _isRunning.value = ok
        if (ok) {
            _sampleRate.value = nativeGetSampleRate()
            _bufferSize.value = nativeGetBufferSize()
            Log.i(TAG, "Oboe engine started — profile=$profile SR=${_sampleRate.value} Buffer=${_bufferSize.value}")
            runCatching { engineCreatedListener?.invoke() }
                .onFailure { Log.e(TAG, "engine-created listener failed", it) }
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
        _cpuLoad.value         = nativeGetCpuLoad()
        _activeVoices.value    = nativeGetActiveVoices()
        _bufferSize.value      = nativeGetBufferSize()
        _droppedCommands.value = nativeGetDroppedCommands()
        _xrunCount.value       = nativeGetXrunCount()
        _lastError.value       = EngineErrorFlags(nativeGetLastError())
    }

    override fun clearErrors() {
        nativeClearError()
        _lastError.value = EngineErrorFlags.NONE
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
    // Fase 1.1 §22 (ADR 0023): completa la superficie pública para dos
    // parámetros nativos que ya existían pero eran inalcanzables — ver
    // AudioEngine.h para el detalle completo del hallazgo.
    override fun setReverbRoom   (r: Float)  = nativeSetReverbRoom(r)
    override fun setReverbDamp   (d: Float)  = nativeSetReverbDamp(d)
    override fun setDelayMix     (m: Float)  = nativeSetDelayMix(m)
    override fun setDelayTime    (s: Float)  = nativeSetDelayTime(s)
    override fun setDelayFeedback(f: Float)  = nativeSetDelayFeedback(f)

    // ── Dynamic FX chain (Fase 7 — DSP Graph real) ───────────────────────────
    // maxChainSlots never changes at runtime (it's a compile-time constant
    // on the native side — see AudioEngine::kMaxChainSlots) so it's cached
    // after the first JNI round-trip rather than queried every access.
    override val maxChainSlots: Int by lazy { nativeGetMaxChainSlots() }

    override fun insertModule(slot: Int, type: DspModuleType): Boolean =
        nativeInsertModule(slot, type.nativeId)

    override fun removeModule(slot: Int): Boolean =
        nativeRemoveModule(slot)

    override fun moveModule(fromSlot: Int, toSlot: Int): Boolean =
        nativeMoveModule(fromSlot, toSlot)

    override fun setModuleParameter(slot: Int, paramId: Int, value: Float) =
        nativeSetModuleParameter(slot, paramId, value)

    override fun getModuleType(slot: Int): DspModuleType =
        DspModuleType.fromNativeId(nativeGetModuleType(slot))

    // ── JNI declarations ───────────────────────────────────────────────────

    private external fun nativeCreate(performanceProfile: Int): Boolean
    private external fun nativeDestroy()

    private external fun nativeNoteOn(channel: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(channel: Int, note: Int)
    private external fun nativeAllNotesOff()
    private external fun nativeSendCC(channel: Int, cc: Int, value: Int)
    private external fun nativeSetPitchBend(channel: Int, semitones: Float)

    private external fun nativeSetMasterVolume(volume: Float)
    private external fun nativeSetReverbMix(mix: Float)
    private external fun nativeSetReverbRoom(room: Float)
    private external fun nativeSetReverbDamp(damp: Float)
    private external fun nativeSetDelayMix(mix: Float)
    private external fun nativeSetDelayTime(seconds: Float)
    private external fun nativeSetDelayFeedback(feedback: Float)

    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetBufferSize(): Int
    private external fun nativeGetCpuLoad(): Float
    private external fun nativeGetActiveVoices(): Int
    private external fun nativeGetXrunCount(): Int
    private external fun nativeGetDroppedCommands(): Long
    private external fun nativeGetLastError(): Int
    private external fun nativeClearError()

    private external fun nativeInsertModule(slot: Int, moduleType: Int): Boolean
    private external fun nativeRemoveModule(slot: Int): Boolean
    private external fun nativeMoveModule(fromSlot: Int, toSlot: Int): Boolean
    private external fun nativeSetModuleParameter(slot: Int, paramId: Int, value: Float)
    private external fun nativeGetModuleType(slot: Int): Int
    private external fun nativeGetMaxChainSlots(): Int

    companion object {
        private const val TAG = "EliNerAudioBridge"

        // Singleton — exposed through the EliNerAudioApi contract so callers
        // never depend on this concrete implementation.
        @Volatile private var instance: EliNerAudioBridge? = null
        fun getInstance(): EliNerAudioApi = getInstanceForComposition()

        /**
         * Mismo singleton, pero con el tipo CONCRETO. Solo para la raíz de composición
         * (`eliner.composition`), que necesita `setEngineCreatedListener` (ADR 0028) y es la
         * única capa autorizada a conocer las implementaciones. El resto sigue usando
         * [getInstance] y el contrato [EliNerAudioApi].
         */
        internal fun getInstanceForComposition(): EliNerAudioBridge = instance ?: synchronized(this) {
            instance ?: EliNerAudioBridge().also { instance = it }
        }

        init {
            System.loadLibrary("eliner_audio_core")
        }
    }
}
