package com.yeivikas.olyze

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yeivikas.olyze.eliner.api.EliNerEngine
import com.yeivikas.olyze.eliner.api.EngineInitState
import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.midi.EliNerMidiApi
import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickMode
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.ClickSwing
import com.yeivikas.olyze.eliner.api.transport.CountInLength
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import com.yeivikas.olyze.eliner.composition.EliNerEngineFactory
import com.yeivikas.olyze.transport.MetronomePreset
import com.yeivikas.olyze.transport.MetronomePresetLibrary
import com.yeivikas.olyze.transport.MidiClockGenerator
import com.yeivikas.olyze.transport.SharedPreferencesPresetStore
import com.yeivikas.olyze.transport.TapMute
import com.yeivikas.olyze.transport.TapStatus
import com.yeivikas.olyze.transport.TapTempo
import com.yeivikas.olyze.transport.TempoNudge
import com.yeivikas.olyze.transport.applyPreset
import com.yeivikas.olyze.transport.quarterNoteBpmFor
import com.yeivikas.olyze.transport.TransportCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
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

    /** Intensidad de cada tiempo del compás (estado del motor). */
    val beatPattern: StateFlow<BeatPattern> get() = engine.transport.beatPattern

    /** Unidad de pulso = denominador del compás (estado del motor). */
    val beatUnit: StateFlow<BeatUnit> get() = engine.transport.beatUnit

    /** Clicks por pulso del metrónomo (estado del motor). */
    val clickSubdivision: StateFlow<ClickSubdivision> get() = engine.transport.clickSubdivision

    /** Pulsos por compás: el numerador del compás (3 = 3/4, 6 = 6/8…; la figura de cada pulso es [beatUnit]). Estado del motor. */
    val beatsPerBar: StateFlow<Int> get() = engine.transport.beatsPerBar

    /** Delay sincronizado al tempo: activo/inactivo y división de nota (estado del motor). */
    val delaySync: StateFlow<DelayTempoSync> get() = engine.transport.delaySync

    /**
     * Pulso REAL del motor para indicadores visuales (ADR 0029). Flujo frío: el motor solo se
     * sondea mientras la UI lo recolecta (con `collectAsStateWithLifecycle`, solo en primer plano).
     */
    val pulse: Flow<BeatPulse> get() = engine.transport.pulse

    /** Posición del transporte en ticks, para mostrarla (ADR 0036). Se sondea solo mientras se recoge. */
    val position: Flow<Long> get() = engine.transport.positionUpdates

    /** Grabación armada: la deriva del motor, que es quien decide si hay conteo previo (ADR 0035). */
    val isRecording: StateFlow<Boolean> get() = engine.transport.recordArmed

    /** Swing de la subdivisión del click (ADR 0039). */
    val clickSwing: StateFlow<ClickSwing> get() = engine.transport.clickSwing

    /** Compases de conteo previo a grabar. */
    val countInLength: StateFlow<CountInLength> get() = engine.transport.countInLength

    /** Cuándo suena el click: reproduciendo y grabando, solo grabando o solo el conteo. */
    val clickMode: StateFlow<ClickMode> get() = engine.transport.clickMode

    private val _keyboardVisible = MutableStateFlow(true)
    val keyboardVisible: StateFlow<Boolean> = _keyboardVisible

    private val _midiChannel   = MutableStateFlow(0)
    val midiChannel: StateFlow<Int> = _midiChannel

    private val _velocity      = MutableStateFlow(100)
    val velocity: StateFlow<Int> = _velocity

    /** Notas actualmente sonando porque un controlador MIDI EXTERNO las
     *  mantiene presionadas — ver [EliNerEngine.externalActiveNotes]. */
    val externalActiveNotes: StateFlow<Set<Int>> = engine.externalActiveNotes

    // El reloj MIDI tiene su PROPIO hilo con prioridad de audio: en el hilo principal un fotograma largo de la UI
    // retrasaría los pulsos hacia el dispositivo externo. El envío es seguro entre hilos (MidiOutputBridge).
    private val clockExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            task.run()
        }, "olyze-midi-clock").apply { isDaemon = true }
    }
    private val clockDispatcher = clockExecutor.asCoroutineDispatcher()
    private val clockScope = CoroutineScope(SupervisorJob() + clockDispatcher)
    private val clock = MidiClockGenerator(engine.midiOutput, engine.transport.tempoBpm, clockScope)

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

    fun toggleRecord() = transportCoordinator.toggleRecord()

    fun setClickSwing(swing: ClickSwing) = engine.transport.setClickSwing(swing)

    fun setCountInLength(length: CountInLength) = engine.transport.setCountInLength(length)

    fun setClickMode(mode: ClickMode) = engine.transport.setClickMode(mode)

    fun rewind() = transportCoordinator.rewind()

    fun toggleMetronome() =
        engine.transport.setMetronomeEnabled(!engine.transport.metronomeEnabled.value)

    fun setMetronomeVolume(volume: Float) = engine.transport.setMetronomeVolume(volume)

    /**
     * Fija el tempo EXACTO (con decimales) en el motor, que lo limita a 20–300 BPM. Lo consumen el
     * metrónomo, el delay sincronizado y el reloj MIDI de salida (este último lo lee en cada pulso).
     */
    fun setTempoBpm(bpm: Float) {
        abandonTapSession()
        nudgeBase = null // un cambio permanente anula el nudge en curso SIN restaurar el tempo viejo
        engine.transport.setTempo(bpm)
    }

    /**
     * Suma [delta] BPM (±0,1, ±1, ±10…) al tempo ACTUAL DEL MOTOR, redondeado a décimas: un paso PERMANENTE
     * (no confundir con el nudge, que es temporal: [nudgeStart]/[nudgeEnd]). Es el ÚNICO
     * camino de pasos relativos (header y panel): se calcula aquí y no en la UI a propósito, porque
     * mantener pulsado un botón repite el paso cada ~90 ms y sumar sobre el valor que la UI tenga en
     * ese instante (aún sin recomponer) perdería pasos.
     */
    fun stepTempo(delta: Float) {
        abandonTapSession()
        nudgeBase = null
        val next = engine.transport.tempoBpm.value + delta
        engine.transport.setTempo((next * 10f).roundToInt() / 10f)
    }

    // ── Tap tempo ──────────────────────────────────────────────────────────
    // El cálculo vive en TapTempo (estadística pura, reloj inyectado); aquí solo se conecta con el motor y
    // se publica el estado de la medición para la UI.
    private val tapper = TapTempo()
    private val _tapStatus = MutableStateFlow(TapStatus.IDLE)
    private var tapExpiry: Job? = null

    /** Medición de tap tempo en curso: cuántos toques, qué tempo estima y si ya es fiable. */
    val tapStatus: StateFlow<TapStatus> = _tapStatus.asStateFlow()

    /**
     * Un toque del pad de TAP. [eventUptimeMs] es la hora REAL del toque (`PointerInputChange.uptimeMillis`),
     * no la de su procesado: Android agrupa los toques por fotograma y eso metería hasta ~16 ms de ruido en
     * cada intervalo. Con 2 o más toques fija el tempo medido (la estimación es provisional hasta que
     * [TapStatus.isStable]). Los toques son del PULSO del compás: se convierten a negras por minuto según
     * la figura vigente ([quarterNoteBpmFor]).
     */
    fun tapTempo(eventUptimeMs: Long) {
        nudgeBase = null // tocar un tempo nuevo anula cualquier nudge: el tapper manda
        val acceptedBefore = tapper.acceptedTaps
        // El tapper mide PULSOS por minuto; el tempo del motor es en negras (con pulso de corchea, 240 toques/min = 120).
        tapper.tap(eventUptimeMs)?.let { engine.transport.setTempo(quarterNoteBpmFor(it, engine.transport.beatUnit.value)) }
        val accepted = tapper.acceptedTaps != acceptedBefore
        _tapStatus.value = tapper.status()
        // Sync: cada toque ACEPTADO reinicia el tiempo 1 (solo con el transporte en marcha; un rebote ignorado no).
        // Se pide DESPUÉS de fijar el tempo: el motor los recibe en ese orden.
        if (accepted && _tapSync.value && engine.transport.isRunning.value) engine.transport.resyncBar()
        // Mute: el click vuelve a entrar gradualmente a medida que la medición se afianza.
        if (_tapMute.value) applyTapDuck(TapMute.levelFor(tapper.tapCount))
        // La medición caduca sola: pasada la pausa, la UI vuelve a «toca al ritmo» sin esperar otro toque.
        tapExpiry?.cancel()
        tapExpiry = viewModelScope.launch {
            delay(tapper.resetAfterMs)
            resetTapSession()
        }
    }

    private fun resetTapSession() {
        tapper.reset()
        _tapStatus.value = TapStatus.IDLE
        applyTapDuck(1f) // la medición terminó: el click vuelve a su nivel normal
    }

    // ── Opciones del tapper (manual de FL Studio: Sync y Mute) ──────────────
    private val _tapSync = MutableStateFlow(false)
    private val _tapMute = MutableStateFlow(false)

    /** Sync: cada toque reinicia el tiempo 1. Apagado por defecto: el manual de FL lo da como el uso más común. */
    val tapSync: StateFlow<Boolean> = _tapSync.asStateFlow()

    /** Mute: el click se silencia al empezar a tocar y vuelve gradualmente (ver [TapMute]). */
    val tapMute: StateFlow<Boolean> = _tapMute.asStateFlow()

    fun setTapSync(enabled: Boolean) { _tapSync.value = enabled }

    fun setTapMute(enabled: Boolean) {
        _tapMute.value = enabled
        // Al activarlo a mitad de medición se aplica el nivel que toca; al apagarlo, el click vuelve entero.
        applyTapDuck(if (enabled) TapMute.levelFor(tapper.tapCount).takeIf { tapper.tapCount > 0 } ?: 1f else 1f)
    }

    private var tapDuckActive = false

    /** Envía el duck solo cuando cambia de «normal» a «atenuado» o al revés, no en cada llamada. */
    private fun applyTapDuck(level: Float) {
        if (level >= 1f && !tapDuckActive) return
        engine.transport.setMetronomeDuck(level)
        tapDuckActive = level < 1f
    }

    // ── Nudge (manual de FL Studio): tempo TEMPORAL mientras se mantiene pulsado ──
    // Distinto de ±0,1/±1/±10, que son cambios permanentes. nudgeBase es el tempo al que se vuelve al soltar.
    private var nudgeBase: Float? = null

    /** Empieza el nudge en la dirección [direction] (1 = acelerar, −1 = frenar). El tempo base se recuerda. */
    fun nudgeStart(direction: Int) {
        if (direction == 0) return
        val base = nudgeBase ?: engine.transport.tempoBpm.value.also { nudgeBase = it }
        engine.transport.setTempo(TempoNudge.target(base, direction))
    }

    /** Termina el nudge y devuelve el tempo al que había antes de empezarlo (si no lo anuló otro cambio). */
    fun nudgeEnd() {
        nudgeBase?.let { engine.transport.setTempo(it) }
        nudgeBase = null
    }

    /** Un ajuste manual del tempo (dial, ±1, ±0,1…) da por terminada la medición: no debe mezclarse con ella. */
    private fun abandonTapSession() {
        tapExpiry?.cancel()
        resetTapSession()
    }

    fun setMetronomeSound(sound: MetronomeSound) = engine.transport.setMetronomeSound(sound)

    // ── Presets del metrónomo ("Crear / Cargar") ───────────────────────────
    // Las reglas (sin duplicados, tope) y la persistencia están en MetronomePresetLibrary; aplicar un
    // preset es applyPreset. Aquí solo se conecta con el motor y con SharedPreferences.
    private val presetLibrary = MetronomePresetLibrary(
        SharedPreferencesPresetStore(application.getSharedPreferences("olyze_metronome_presets", Context.MODE_PRIVATE)),
    )

    /** Presets guardados, del más antiguo al más reciente. */
    val presets: StateFlow<List<MetronomePreset>> get() = presetLibrary.presets

    /** Máximo de presets que admite la biblioteca. */
    val maxPresets: Int get() = presetLibrary.maxPresets

    /** Guarda la configuración [preset] (la actual del metrónomo). No hace nada si ya existe o está llena. */
    fun savePreset(preset: MetronomePreset) { presetLibrary.add(preset) }

    /**
     * Aplica [preset] al motor: cambia cómo suena, no si el metrónomo o el transporte están en marcha. Fija un
     * tempo, así que cuenta como un ajuste manual: termina la medición de tap en curso (y con ella el Mute) y
     * anula un nudge pendiente, igual que [setTempoBpm] y [stepTempo]. Sin esto, los toques viejos se mezclaban
     * con los siguientes y el click podía quedar en silencio hasta que caducase la medición.
     */
    fun loadPreset(preset: MetronomePreset) {
        abandonTapSession()
        nudgeBase = null
        engine.transport.applyPreset(preset)
    }

    fun deletePreset(preset: MetronomePreset) = presetLibrary.remove(preset)

    fun setBeatPattern(pattern: BeatPattern) = engine.transport.setBeatPattern(pattern)

    fun setClickSubdivision(subdivision: ClickSubdivision) = engine.transport.setClickSubdivision(subdivision)

    /**
     * Cambia el compás ([beatsPerBar]/[unit], p. ej. 6/8) y RESTABLECE el patrón de acentos al propio de
     * ese compás ([BeatPattern.defaultFor]): el patrón de un 4/4 no tiene sentido en un 6/8. Es el ÚNICO
     * camino de cambio de compás desde la UI; así el compás y su patrón nunca quedan desacordes.
     */
    fun setTimeSignature(beatsPerBar: Int, unit: BeatUnit) {
        engine.transport.setBeatsPerBar(beatsPerBar)
        engine.transport.setBeatUnit(unit)
        engine.transport.setBeatPattern(BeatPattern.defaultFor(beatsPerBar, unit))
    }

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
        clockScope.cancel()
        clockDispatcher.close()
        // El orden correcto (MIDI antes que audio — Crítico 3 de esa
        // fase) vive en EliNerEngine.shutdown(), un único lugar.
        engine.shutdown()
    }
}
