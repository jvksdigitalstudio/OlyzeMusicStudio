package com.yeivikas.olyze

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yeivikas.olyze.eliner.api.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.EliNerMidiApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI state y comandos de UI — transporte, teclado en pantalla, FX en
 * caliente. NO construye infraestructura de audio/MIDI (ver
 * [AppServices] — Fase 1 de estabilización, Objetivo H): este ViewModel
 * consume [services], no ensambla sus piezas.
 *
 * Límite de responsabilidad, ahora real y no solo documentado: si un
 * método de esta clase necesita construir un `ThreadManager`, un
 * `EventBus`, o cualquier otra pieza de infraestructura del motor, eso es
 * una señal de que la pieza pertenece a [AppServices], no aquí.
 */
@RequiresApi(Build.VERSION_CODES.M)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Application Services — construcción de TODA la infraestructura de
    //    audio/MIDI vive ahí, no aquí. Ver el límite honesto documentado
    //    en la propia clase [AppServices] sobre por qué esto sigue
    //    construyéndose desde el ViewModel en vez de una Application
    //    custom (trabajo de seguimiento explícito, no oculto). ──
    private val services = AppServices(application)

    val audio: EliNerAudioApi get() = services.audio
    val midi: EliNerMidiApi get() = services.midi
    val midiManager get() = services.midiManager

    // ── State ──
    private val _bpm            = MutableStateFlow(120)
    val bpm: StateFlow<Int>     = _bpm

    private val _isPlaying      = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isRecording    = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _keyboardVisible = MutableStateFlow(true)
    val keyboardVisible: StateFlow<Boolean> = _keyboardVisible

    private val _midiChannel   = MutableStateFlow(0)
    val midiChannel: StateFlow<Int> = _midiChannel

    private val _velocity      = MutableStateFlow(100)
    val velocity: StateFlow<Int> = _velocity

    /** Notas actualmente sonando porque un controlador MIDI EXTERNO las
     *  mantiene presionadas — ver [com.yeivikas.olyze.eliner.bridge.MidiToSynthBridge.externalActiveNotes]. */
    val externalActiveNotes: StateFlow<Set<Int>> = services.midiToSynth.externalActiveNotes

    private var clockJob: Job? = null

    init {
        services.start()
    }

    // ── Transport ──────────────────────────────────────────────────────────

    fun togglePlay() {
        _isPlaying.value = !_isPlaying.value
        if (_isPlaying.value) {
            midiManager.sendStart()
            startClock()
        } else {
            stopClock()
            midiManager.sendStop()
            audio.allNotesOff()
        }
    }

    fun toggleRecord() { _isRecording.value = !_isRecording.value }

    fun rewind() {
        _isPlaying.value = false
        stopClock()
        midiManager.sendStop()
        audio.allNotesOff()
    }

    fun setBpm(value: Int) {
        _bpm.value = value.coerceIn(20, 300)
        if (_isPlaying.value) { stopClock(); startClock() }
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (isActive) {
                midiManager.sendClock()
                val ms = (60_000.0 / _bpm.value / 24).toLong().coerceAtLeast(1L)
                delay(ms)
            }
        }
    }

    private fun stopClock() { clockJob?.cancel(); clockJob = null }

    // ── Keyboard ───────────────────────────────────────────────────────────

    fun toggleKeyboard() { _keyboardVisible.value = !_keyboardVisible.value }

    fun noteOn(midiNote: Int) {
        // Fire to both: internal Oboe synth + external MIDI
        audio.noteOn(_midiChannel.value, midiNote, _velocity.value)
        midiManager.sendNoteOn(_midiChannel.value, midiNote, _velocity.value)
    }

    fun noteOff(midiNote: Int) {
        audio.noteOff(_midiChannel.value, midiNote)
        midiManager.sendNoteOff(_midiChannel.value, midiNote)
    }

    // ── FX ─────────────────────────────────────────────────────────────────

    fun setReverbMix(mix: Float)     = audio.setReverbMix(mix)
    fun setDelayMix(mix: Float)      = audio.setDelayMix(mix)
    fun setMasterVolume(vol: Float)  = audio.setMasterVolume(vol)

    override fun onCleared() {
        super.onCleared()
        stopClock()
        // El orden correcto (MIDI antes que audio — Crítico 3 de esta
        // fase) vive ahora en AppServices.shutdown(), un único lugar,
        // reusable si en el futuro otro ViewModel necesita el mismo
        // ciclo de apagado — ver su propio comentario para el detalle.
        services.shutdown()
    }
}
