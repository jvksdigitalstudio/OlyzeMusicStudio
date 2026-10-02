package com.yeivikas.olyze

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yeivikas.olyze.eliner.api.EliNerEngine
import com.yeivikas.olyze.eliner.api.EngineInitState
import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.midi.EliNerMidiApi
import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import com.yeivikas.olyze.eliner.composition.EliNerEngineFactory
import com.yeivikas.olyze.transport.MidiClockGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI state y comandos de UI — transporte, teclado en pantalla, FX en
 * caliente. NO construye infraestructura de audio/MIDI ni conoce los
 * internos de EliNer: consume únicamente la API pública [EliNerEngine]
 * (ADR 0027). El reloj MIDI vive en [MidiClockGenerator].
 *
 * Límite de responsabilidad: si un método de esta clase necesita construir
 * un hilo, un bus de eventos o cualquier pieza de infraestructura del
 * motor, esa pieza pertenece a EliNer (`eliner.composition`), no aquí.
 *
 * Límite honesto (heredado de `AppServices`, documentado): este
 * ViewModel sigue siendo quien crea el motor, en vez de una subclase de
 * `Application` creada una vez por proceso. Mover esa línea a una
 * `Application` custom requiere tocar el manifiesto y no se pudo
 * verificar por compilación; ver `DefaultEliNerEngine`.
 */
@RequiresApi(Build.VERSION_CODES.M)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine: EliNerEngine = EliNerEngineFactory.create(application)

    val audio: EliNerAudioApi get() = engine.audio
    val midi: EliNerMidiApi get() = engine.midi
    val midiOutput: MidiOutputApi get() = engine.midiOutput

    /** Estado observable del arranque asíncrono del motor (Fase 1.1 §15)
     *  — ver [EngineInitState]. La UI puede usarlo para mostrar
     *  "arrancando..."/un error real en vez de asumir silenciosamente que
     *  el motor ya está listo. */
    val engineInitState: StateFlow<EngineInitState> get() = engine.state

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
     *  mantiene presionadas — ver [EliNerEngine.externalActiveNotes]. */
    val externalActiveNotes: StateFlow<Set<Int>> = engine.externalActiveNotes

    private val clock = MidiClockGenerator(engine.midiOutput, _bpm, viewModelScope)

    init {
        // Fase 1.1 §15: start() NO bloquea este hilo — lanza el arranque
        // real en segundo plano y publica su progreso en [engineInitState].
        engine.start()
    }

    // ── Transport ──────────────────────────────────────────────────────────

    fun togglePlay() {
        _isPlaying.value = !_isPlaying.value
        if (_isPlaying.value) {
            midiOutput.sendStart()
            clock.start()
        } else {
            clock.stop()
            midiOutput.sendStop()
            audio.allNotesOff()
        }
    }

    fun toggleRecord() { _isRecording.value = !_isRecording.value }

    fun rewind() {
        _isPlaying.value = false
        clock.stop()
        midiOutput.sendStop()
        audio.allNotesOff()
    }

    /** El reloj lee [bpm] en cada pulso: el nuevo tempo se aplica solo. */
    fun setBpm(value: Int) { _bpm.value = value.coerceIn(20, 300) }

    // ── Keyboard ───────────────────────────────────────────────────────────

    fun toggleKeyboard() { _keyboardVisible.value = !_keyboardVisible.value }

    fun noteOn(midiNote: Int) {
        // Fire to both: internal Oboe synth + external MIDI
        audio.noteOn(_midiChannel.value, midiNote, _velocity.value)
        midiOutput.sendNoteOn(_midiChannel.value, midiNote, _velocity.value)
    }

    fun noteOff(midiNote: Int) {
        audio.noteOff(_midiChannel.value, midiNote)
        midiOutput.sendNoteOff(_midiChannel.value, midiNote)
    }

    // ── FX ─────────────────────────────────────────────────────────────────

    fun setReverbMix(mix: Float)     = audio.setReverbMix(mix)
    fun setDelayMix(mix: Float)      = audio.setDelayMix(mix)
    fun setMasterVolume(vol: Float)  = audio.setMasterVolume(vol)

    override fun onCleared() {
        super.onCleared()
        clock.stop()
        // El orden correcto (MIDI antes que audio — Crítico 3 de esa
        // fase) vive en EliNerEngine.shutdown(), un único lugar.
        engine.shutdown()
    }
}
