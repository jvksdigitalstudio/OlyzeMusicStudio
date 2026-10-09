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
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import com.yeivikas.olyze.eliner.composition.EliNerEngineFactory
import com.yeivikas.olyze.transport.MidiClockGenerator
import com.yeivikas.olyze.transport.TapTempo
import com.yeivikas.olyze.transport.TransportCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

/**
 * UI state y comandos de UI — transporte, teclado en pantalla, FX en
 * caliente. NO construye infraestructura de audio/MIDI ni conoce los
 * internos de EliNer: consume únicamente la API pública [EliNerEngine]
 * (ADR 0027). El reloj MIDI vive en [MidiClockGenerator].
 *
 * El tempo, el estado de reproducción y el metrónomo NO se guardan aquí:
 * son el estado del transporte del motor ([EliNerEngine.transport], ADR 0028)
 * y este ViewModel solo lo expone a la UI y le envía órdenes. Una única
 * fuente de verdad: lo que muestra la UI es lo que el motor tiene.
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
    val transport: EliNerTransportApi get() = engine.transport

    /** Estado observable del arranque asíncrono del motor (Fase 1.1 §15)
     *  — ver [EngineInitState]. La UI puede usarlo para mostrar
     *  "arrancando..."/un error real en vez de asumir silenciosamente que
     *  el motor ya está listo. */
    val engineInitState: StateFlow<EngineInitState> get() = engine.state

    // ── State ──
    /** Tempo para mostrar: el del transporte del motor, en BPM enteros. */
    val bpm: StateFlow<Int> = engine.transport.tempoBpm
        .map { it.roundToInt() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, engine.transport.tempoBpm.value.roundToInt())

    /** `true` mientras el transporte del motor está en marcha. */
    val isPlaying: StateFlow<Boolean> get() = engine.transport.isRunning

    val metronomeEnabled: StateFlow<Boolean> get() = engine.transport.metronomeEnabled

    /** Volumen del click del metrónomo, 0.0–1.0 (estado del motor). */
    val metronomeVolume: StateFlow<Float> get() = engine.transport.metronomeVolume

    /** Tempo exacto del motor, con decimales (el `bpm` entero de arriba es solo para el header). */
    val tempoBpm: StateFlow<Float> get() = engine.transport.tempoBpm

    /** Sonido del click del metrónomo (estado del motor). */
    val metronomeSound: StateFlow<MetronomeSound> get() = engine.transport.metronomeSound

    /** Si el primer tiempo del compás suena acentuado (estado del motor). */
    val metronomeAccent: StateFlow<Boolean> get() = engine.transport.metronomeAccent

    /** Clicks por pulso del metrónomo (estado del motor). */
    val clickSubdivision: StateFlow<ClickSubdivision> get() = engine.transport.clickSubdivision

    /** Pulsos por compás (negras): 3 = 3/4, 4 = 4/4… (estado del motor). */
    val beatsPerBar: StateFlow<Int> get() = engine.transport.beatsPerBar

    /** Delay sincronizado al tempo: activo/inactivo y división de nota (estado del motor). */
    val delaySync: StateFlow<DelayTempoSync> get() = engine.transport.delaySync

    /**
     * Pulso REAL del motor para indicadores visuales (ADR 0029). Flujo frío: el motor solo se
     * sondea mientras la UI lo recolecta (con `collectAsStateWithLifecycle`, solo en primer plano).
     */
    val pulse: Flow<BeatPulse> get() = engine.transport.pulse

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

    private val clock = MidiClockGenerator(engine.midiOutput, engine.transport.tempoBpm, viewModelScope)

    // La SECUENCIA de reproducir/parar (motor, MIDI de transporte, reloj, silenciar notas)
    // vive en TransportCoordinator, no aquí: este ViewModel solo expone estado y reenvía órdenes.
    private val transportCoordinator = TransportCoordinator(
        transport = engine.transport,
        midiOutput = engine.midiOutput,
        midiClock = clock,
        silenceAllNotes = { engine.audio.allNotesOff() },
    )

    init {
        // Fase 1.1 §15: start() NO bloquea este hilo — lanza el arranque
        // real en segundo plano y publica su progreso en [engineInitState].
        engine.start()
    }

    // ── Transport ──────────────────────────────────────────────────────────

    fun togglePlay() = transportCoordinator.togglePlay()

    fun toggleRecord() { _isRecording.value = !_isRecording.value }

    fun rewind() = transportCoordinator.rewind()

    /**
     * Fija el tempo en el motor (límites 20–300 BPM aplicados por el propio
     * transporte). Lo consumen el metrónomo, el delay sincronizado y el reloj
     * MIDI de salida, que lo lee en cada pulso.
     */
    fun setBpm(value: Int) = engine.transport.setTempo(value.toFloat())

    /**
     * Suma [delta] BPM al tempo ACTUAL del motor (no al que muestre la UI en ese
     * instante): los controles de paso (+/− con repetición al mantener pulsado)
     * envían pasos, no valores absolutos, así que no pueden aplicar un valor viejo.
     */
    fun stepBpm(delta: Int) =
        engine.transport.setTempo(engine.transport.tempoBpm.value + delta)

    fun toggleMetronome() =
        engine.transport.setMetronomeEnabled(!engine.transport.metronomeEnabled.value)

    fun setMetronomeVolume(volume: Float) = engine.transport.setMetronomeVolume(volume)

    /** Fija el tempo EXACTO (con decimales); el motor lo limita a 20–300 BPM. */
    fun setTempoBpm(bpm: Float) = engine.transport.setTempo(bpm)

    /**
     * Suma [delta] BPM (±0,1, ±1, ±10…) al tempo ACTUAL DEL MOTOR, redondeado a décimas. Se calcula
     * aquí y no en la UI a propósito: mantener pulsado un botón repite el paso cada ~90 ms y
     * sumar sobre el valor que la UI tenga en ese instante (aún sin recomponer) perdería pasos.
     */
    fun nudgeTempo(delta: Float) {
        val next = engine.transport.tempoBpm.value + delta
        engine.transport.setTempo((next * 10f).roundToInt() / 10f)
    }

    // El cálculo vive en TapTempo (estadística pura, reloj inyectado); aquí solo se le da la hora.
    private val tapper = TapTempo()

    /** Una pulsación de "tap tempo": con 2 o más pulsaciones fija el tempo medido. */
    fun tapTempo() {
        tapper.tap(System.nanoTime() / 1_000_000L)?.let { engine.transport.setTempo(it) }
    }

    fun setMetronomeSound(sound: MetronomeSound) = engine.transport.setMetronomeSound(sound)

    fun setMetronomeAccent(enabled: Boolean) = engine.transport.setMetronomeAccent(enabled)

    fun setClickSubdivision(subdivision: ClickSubdivision) = engine.transport.setClickSubdivision(subdivision)

    fun setBeatsPerBar(beats: Int) = engine.transport.setBeatsPerBar(beats)

    /** Elegir una división ACTIVA el sync del delay (tocar una división es pedir el sync). */
    fun setDelayDivision(division: NoteDivision) =
        engine.transport.setDelayTempoSync(enabled = true, division = division)

    /** Activa/desactiva el sync conservando la división elegida. */
    fun setDelaySyncEnabled(enabled: Boolean) =
        engine.transport.setDelayTempoSync(enabled, engine.transport.delaySync.value.division)

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
