package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Implementación de [EliNerTransportApi] (ADR 0028): conserva el estado
 * DESEADO del transporte, lo valida y lo envía al motor a través de
 * [TransportNative] SIN bloquear nunca al llamador.
 *
 * ## Por qué guarda estado si el motor ya lo tiene
 *
 * `nativeCreate` construye un `AudioEngine` NUEVO en cada arranque, así que el
 * estado nativo no sobrevive a un reinicio del motor, y un cambio hecho antes
 * de que el motor exista (la UI ya está viva mientras el arranque es
 * asíncrono) se descartaría. Este controlador es la fuente de verdad del
 * estado deseado: los [StateFlow] lo reflejan al instante y [syncToNative] lo
 * reaplica cada vez que el motor nativo se crea.
 *
 * ## Por qué NO llama a JNI en el hilo del llamador
 *
 * Las funciones JNI toman `gEngineMutex`, y `nativeCreate` lo mantiene
 * mientras Oboe abre el stream de audio (cientos de ms, hasta el timeout de 5 s
 * en un dispositivo lento). Si un setter llamara a JNI directamente, tocar BPM,
 * play o el metrónomo durante el arranque congelaría el hilo de la UI. Por eso
 * los setters solo actualizan el estado y marcan qué cambió; un [executor] de UN
 * solo hilo (el del motor: `eliner-transport-commands`) vacía esos cambios
 * hacia JNI, igual que `AudioCommandDispatcher` hace con el resto del audio.
 *
 * ## Vaciado con coalescencia
 *
 * Cada vaciado lee el estado MÁS RECIENTE en el momento de ejecutarse y envía
 * solo los campos marcados, en orden fijo (configuración primero, transporte en
 * marcha al final). Consecuencias, todas deseadas:
 *  - Nunca hay más de un vaciado pendiente: mantener pulsado `+` mientras el
 *    hilo del motor está bloqueado se fusiona en UN envío del último tempo (sin
 *    cola creciente).
 *  - Un valor viejo nunca llega después de uno nuevo: el [executor] es de un solo
 *    hilo y cada vaciado parte del estado actual, no del que había al encolar.
 *  - Límite conocido: `stop(); start()` fusionados en un mismo vaciado envían solo
 *    "en marcha" y, como el motor ignora un start repetido, no vuelven al primer
 *    tiempo. Solo ocurre si el hilo del motor estaba bloqueado en ese instante.
 *
 * ## Concurrencia
 *
 * Un único candado ([lock]) protege el estado deseado y las marcas de pendiente.
 * Nunca se llama a JNI con el candado tomado, de modo que ni la UI ni
 * [syncToNative] esperan a un JNI bloqueado.
 */
internal class TransportController(
    private val native: TransportNative,
    private val executor: Executor,
) : EliNerTransportApi {

    private val lock = Any()

    private val _tempoBpm = MutableStateFlow(EliNerTransportApi.DEFAULT_TEMPO_BPM)
    override val tempoBpm: StateFlow<Float> = _tempoBpm.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _beatsPerBar = MutableStateFlow(EliNerTransportApi.DEFAULT_BEATS_PER_BAR)
    override val beatsPerBar: StateFlow<Int> = _beatsPerBar.asStateFlow()

    private val _metronomeEnabled = MutableStateFlow(false)
    override val metronomeEnabled: StateFlow<Boolean> = _metronomeEnabled.asStateFlow()

    private val _metronomeVolume = MutableStateFlow(EliNerTransportApi.DEFAULT_METRONOME_VOLUME)
    override val metronomeVolume: StateFlow<Float> = _metronomeVolume.asStateFlow()

    private val _metronomeSound = MutableStateFlow(EliNerTransportApi.DEFAULT_METRONOME_SOUND)
    override val metronomeSound: StateFlow<MetronomeSound> = _metronomeSound.asStateFlow()

    private val _metronomeAccent = MutableStateFlow(EliNerTransportApi.DEFAULT_METRONOME_ACCENT)
    override val metronomeAccent: StateFlow<Boolean> = _metronomeAccent.asStateFlow()

    private val _clickSubdivision = MutableStateFlow(EliNerTransportApi.DEFAULT_CLICK_SUBDIVISION)
    override val clickSubdivision: StateFlow<ClickSubdivision> = _clickSubdivision.asStateFlow()

    private val _delaySync = MutableStateFlow(DelayTempoSync(enabled = true, division = NoteDivision.EIGHTH_DOTTED))
    override val delaySync: StateFlow<DelayTempoSync> = _delaySync.asStateFlow()

    /**
     * Sondeo del pulso REAL (ADR 0029). Flujo frío: solo trabaja mientras alguien lo recolecta.
     *
     * Corre en [Dispatchers.Default], nunca en el hilo del motor de comandos ni en el de la UI. La
     * lectura nativa no espera ([TransportNative.readPulse] usa try-lock), así que ni siquiera un
     * arranque lento de Oboe puede bloquear al sondeador: simplemente no hay dato nuevo ese ciclo.
     * Solo emite cuando el valor cambia (como mucho 1 vez por pulso o por parada/arranque).
     */
    override val pulse: Flow<BeatPulse> = flow {
        var last = NO_DATA
        while (true) {
            val raw = native.readPulse()
            if (raw >= 0L && raw != last) {
                last = raw
                emit(BeatPulse.fromPacked(raw))
            }
            delay(PULSE_POLL_MS)
        }
    }.flowOn(Dispatchers.Default)

    // Campos pendientes de enviar al motor (máscara de bits) y si ya hay un vaciado encolado.
    // Ambos bajo [lock].
    private var dirty = 0
    private var flushPending = false

    override fun setTempo(bpm: Float) {
        if (!bpm.isFinite()) return
        val clamped = bpm.coerceIn(EliNerTransportApi.MIN_TEMPO_BPM, EliNerTransportApi.MAX_TEMPO_BPM)
        synchronized(lock) {
            _tempoBpm.value = clamped
            markDirty(TEMPO)
        }
    }

    override fun start() = setRunning(true)

    override fun stop() = setRunning(false)

    private fun setRunning(running: Boolean) = synchronized(lock) {
        if (_isRunning.value == running) return@synchronized
        _isRunning.value = running
        markDirty(RUNNING)
    }

    override fun setBeatsPerBar(beats: Int) {
        val clamped = beats.coerceIn(EliNerTransportApi.MIN_BEATS_PER_BAR, EliNerTransportApi.MAX_BEATS_PER_BAR)
        synchronized(lock) {
            _beatsPerBar.value = clamped
            markDirty(BAR)
        }
    }

    override fun setMetronomeEnabled(enabled: Boolean) {
        synchronized(lock) {
            _metronomeEnabled.value = enabled
            markDirty(METRONOME)
        }
    }

    override fun setMetronomeVolume(volume: Float) {
        if (!volume.isFinite()) return
        val clamped = volume.coerceIn(0f, 1f)
        synchronized(lock) {
            _metronomeVolume.value = clamped
            markDirty(VOLUME)
        }
    }

    override fun setMetronomeSound(sound: MetronomeSound) {
        synchronized(lock) {
            _metronomeSound.value = sound
            markDirty(CLICK_SOUND)
        }
    }

    override fun setMetronomeAccent(enabled: Boolean) {
        synchronized(lock) {
            _metronomeAccent.value = enabled
            markDirty(CLICK_ACCENT)
        }
    }

    override fun setClickSubdivision(subdivision: ClickSubdivision) {
        synchronized(lock) {
            _clickSubdivision.value = subdivision
            markDirty(CLICK_SUBDIVISION)
        }
    }

    override fun setDelayTempoSync(enabled: Boolean, division: NoteDivision) {
        synchronized(lock) {
            _delaySync.value = DelayTempoSync(enabled, division)
            markDirty(DELAY_SYNC)
        }
    }

    /**
     * Marca TODO el estado deseado como pendiente de enviar. Lo invoca el puente
     * de audio tras cada creación exitosa del motor nativo (el nativo se crea
     * desde cero en cada arranque, también tras un stop()/start() de la API de
     * audio). No bloquea: el envío lo hace el hilo del motor.
     */
    fun syncToNative() = synchronized(lock) { markDirty(ALL) }

    /** Debe llamarse con [lock] tomado. */
    private fun markDirty(bits: Int) {
        dirty = dirty or bits
        if (flushPending) return
        flushPending = true
        try {
            executor.execute { flush() }
        } catch (e: RejectedExecutionException) {
            // El motor ya se apagó: el estado deseado sigue siendo consultable, pero no hay a dónde
            // enviarlo. Es un estado esperado (p. ej. un toque de UI justo al cerrar), no un error.
            flushPending = false
        }
    }

    /** Se ejecuta en [executor] (un solo hilo). Nunca con [lock] tomado al llamar a JNI. */
    private fun flush() {
        val s = synchronized(lock) {
            Snapshot(dirty, _tempoBpm.value, _beatsPerBar.value, _metronomeVolume.value,
                _metronomeEnabled.value, _delaySync.value, _isRunning.value,
                _metronomeSound.value, _metronomeAccent.value, _clickSubdivision.value)
                .also { dirty = 0; flushPending = false }
        }
        // Orden deliberado: configuración primero y transporte en marcha al final, de modo que
        // el primer pulso ya suena con todo configurado.
        if (s.bits and TEMPO != 0)      native.setTempo(s.tempo)
        if (s.bits and BAR != 0)        native.setBeatsPerBar(s.beatsPerBar)
        if (s.bits and VOLUME != 0)     native.setMetronomeVolume(s.volume)
        if (s.bits and METRONOME != 0)  native.setMetronomeEnabled(s.metronome)
        if (s.bits and CLICK_SOUND != 0)       native.setMetronomeSound(s.sound.nativeId)
        if (s.bits and CLICK_ACCENT != 0)      native.setMetronomeAccent(s.accent)
        if (s.bits and CLICK_SUBDIVISION != 0) native.setMetronomeSubdivision(s.subdivision.perBeat)
        if (s.bits and DELAY_SYNC != 0) native.setDelayTempoSync(s.delaySync.enabled, s.delaySync.division.beats)
        if (s.bits and RUNNING != 0)    native.setTransportRunning(s.running)
    }

    private class Snapshot(
        val bits: Int,
        val tempo: Float,
        val beatsPerBar: Int,
        val volume: Float,
        val metronome: Boolean,
        val delaySync: DelayTempoSync,
        val running: Boolean,
        val sound: MetronomeSound,
        val accent: Boolean,
        val subdivision: ClickSubdivision,
    )

    private companion object {
        /** ~60 Hz: por debajo de un fotograma, muy por encima del pulso más rápido (300 BPM = 5 Hz). */
        const val PULSE_POLL_MS = 16L
        const val NO_DATA = -1L

        const val TEMPO = 1 shl 0
        const val BAR = 1 shl 1
        const val VOLUME = 1 shl 2
        const val METRONOME = 1 shl 3
        const val DELAY_SYNC = 1 shl 4
        const val RUNNING = 1 shl 5
        const val CLICK_SOUND = 1 shl 6
        const val CLICK_ACCENT = 1 shl 7
        const val CLICK_SUBDIVISION = 1 shl 8
        const val ALL = TEMPO or BAR or VOLUME or METRONOME or DELAY_SYNC or RUNNING or
            CLICK_SOUND or CLICK_ACCENT or CLICK_SUBDIVISION
    }
}
