package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickMode
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.ClickSwing
import com.yeivikas.olyze.eliner.api.transport.CountInLength
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import com.yeivikas.olyze.eliner.api.transport.TransportPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * ## Grabación y conteo previo (ADR 0035)
 *
 * La grabación armada, la duración del conteo y el modo del click son estado deseado como el resto: viajan en
 * el mismo vaciado y se reaplican al recrear el motor. Van SIEMPRE antes que el arranque del transporte, porque
 * el motor decide si hay conteo en el instante de arrancar. Efecto colateral aceptado: si el motor se recrea
 * (cambio de dispositivo de audio) con el transporte en marcha y la grabación armada, el arranque reaplicado
 * vuelve a empezar por el conteo, igual que ya vuelve al primer tiempo.
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
 *  - Un `stop(); start()` fusionados en un mismo vaciado (el hilo del motor estaba ocupado) NO se reducen a
 *    «en marcha»: como el motor ignora un start repetido, no volvería al primer tiempo y el MIDI externo, que sí
 *    recibe STOP y START, se desfasaría. Se recuerda que hubo una parada ([stopPending]) y el vaciado envía
 *    «parado» y luego «en marcha».
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
    /** Reloj monótono en nanosegundos; inyectable para probar la compensación de latencia sin esperar. */
    private val nanoClock: () -> Long = System::nanoTime,
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

    /** Atenuación temporal del click (0–1). Estado interno: lo manda el tapper, no se muestra. */
    private var duck = 1f

    private val _beatPattern = MutableStateFlow(EliNerTransportApi.DEFAULT_BEAT_PATTERN)
    override val beatPattern: StateFlow<BeatPattern> = _beatPattern.asStateFlow()

    private val _beatUnit = MutableStateFlow(EliNerTransportApi.DEFAULT_BEAT_UNIT)
    override val beatUnit: StateFlow<BeatUnit> = _beatUnit.asStateFlow()

    private val _clickSwing = MutableStateFlow(EliNerTransportApi.DEFAULT_CLICK_SWING)
    override val clickSwing: StateFlow<ClickSwing> = _clickSwing.asStateFlow()

    private val _clickSubdivision = MutableStateFlow(EliNerTransportApi.DEFAULT_CLICK_SUBDIVISION)
    override val clickSubdivision: StateFlow<ClickSubdivision> = _clickSubdivision.asStateFlow()

    private val _delaySync = MutableStateFlow(DelayTempoSync(enabled = true, division = NoteDivision.EIGHTH_DOTTED))
    override val delaySync: StateFlow<DelayTempoSync> = _delaySync.asStateFlow()

    // Posición pendiente de enviar al motor (ADR 0036). Solo vale mientras el bit POSITION esté en `dirty`.
    private var pendingPosition: Long = 0L

    /** Sondeo de la posición para la UI (ADR 0036): flujo frío, solo trabaja mientras alguien lo recolecta. */
    override val positionUpdates: Flow<Long> = flow {
        while (true) {
            emit(native.readPosition())
            delay(POSITION_POLL_MS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private val _outputLatency = MutableStateFlow<Float?>(null)
    override val outputLatencyMillis: StateFlow<Float?> = _outputLatency.asStateFlow()

    private val _recordArmed = MutableStateFlow(false)
    override val recordArmed: StateFlow<Boolean> = _recordArmed.asStateFlow()

    private val _countInLength = MutableStateFlow(EliNerTransportApi.DEFAULT_COUNT_IN_LENGTH)
    override val countInLength: StateFlow<CountInLength> = _countInLength.asStateFlow()

    private val _clickMode = MutableStateFlow(EliNerTransportApi.DEFAULT_CLICK_MODE)
    override val clickMode: StateFlow<ClickMode> = _clickMode.asStateFlow()

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
        var polls = 0
        val line = PulseDelayLine(nanoClock)
        try {
            while (true) {
                // La latencia de salida se muestrea aquí, en el mismo bucle que muestra el pulso (ADR 0037): es
                // lo único que la consume y así no hay un hilo más. El muestreo no espera (try-lock).
                if (polls++ % LATENCY_SAMPLE_EVERY_POLLS == 0) {
                    val ms = native.sampleOutputLatency()
                    _outputLatency.value = if (ms > 0f) ms else null
                }
                val raw = native.readPulse()
                if (raw >= 0L && raw != last) {
                    last = raw
                    line.offer(BeatPulse.fromPacked(raw))
                }
                // El pulso se publica al RENDERIZAR; se muestra cuando suena (retraso = latencia de salida).
                for (p in line.drain(_outputLatency.value)) emit(p)
                delay(PULSE_POLL_MS)
            }
        } finally {
            line.clear()
        }
    }.flowOn(Dispatchers.Default)

    // Campos pendientes de enviar al motor (máscara de bits) y si ya hay un vaciado encolado.
    // Ambos bajo [lock].
    private var dirty = 0
    private var flushPending = false

    /** Hubo un `stop()` desde el último vaciado: si el vaciado termina «en marcha», el motor debe ver la parada. Bajo [lock]. */
    private var stopPending = false

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
        if (!running) stopPending = true
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

    override fun setBeatPattern(pattern: BeatPattern) {
        synchronized(lock) {
            _beatPattern.value = pattern
            markDirty(BEAT_PATTERN)
        }
    }

    override fun setMetronomeDuck(level: Float) {
        if (!level.isFinite()) return              // un valor no finito se ignora, como en el motor
        synchronized(lock) {
            duck = level.coerceIn(0f, 1f)          // es una ganancia: nunca amplifica ni invierte
            markDirty(CLICK_DUCK)
        }
    }

    /**
     * Orden de un solo uso. Va por el MISMO [executor] que los envíos de estado (un solo hilo, FIFO), de modo
     * que llega al motor DESPUÉS de cualquier cambio de tempo anterior: un toque del tapper fija el tempo y
     * luego reinicia el compás, y ese orden se conserva. No es estado, así que no se coalesce (dos órdenes son
     * dos reinicios) ni se reenvía al recrear el motor.
     */
    override fun resyncBar() {
        try {
            executor.execute { native.resyncBar() }
        } catch (e: RejectedExecutionException) {
            // El motor ya se apagó: no hay compás que reiniciar.
        }
    }

    override fun setBeatUnit(unit: BeatUnit) {
        synchronized(lock) {
            _beatUnit.value = unit
            markDirty(BEAT_UNIT)
        }
    }

    override fun setClickSwing(swing: ClickSwing) {
        synchronized(lock) {
            _clickSwing.value = swing
            markDirty(CLICK_SWING)
        }
    }

    override fun setClickSubdivision(subdivision: ClickSubdivision) {
        synchronized(lock) {
            _clickSubdivision.value = subdivision
            markDirty(CLICK_SUBDIVISION)
        }
    }

    override fun locate(ticks: Long) {
        synchronized(lock) {
            pendingPosition = TransportPosition.clamp(ticks)
            markDirty(POSITION)
        }
    }

    override fun positionTicks(): Long = native.readPosition()

    override fun setRecordArmed(armed: Boolean) {
        synchronized(lock) {
            _recordArmed.value = armed
            markDirty(RECORD_ARMED)
        }
    }

    override fun setCountInLength(length: CountInLength) {
        synchronized(lock) {
            _countInLength.value = length
            markDirty(COUNT_IN)
        }
    }

    override fun setClickMode(mode: ClickMode) {
        synchronized(lock) {
            _clickMode.value = mode
            markDirty(CLICK_MODE)
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
                _metronomeSound.value, _beatPattern.value, _clickSubdivision.value, _beatUnit.value, duck,
                _clickSwing.value, _recordArmed.value, _countInLength.value, _clickMode.value, pendingPosition,
                stopFirst = stopPending)
                .also { dirty = 0; flushPending = false; stopPending = false }
        }
        // Orden deliberado: configuración primero y transporte en marcha al final, de modo que
        // el primer pulso ya suena con todo configurado. En particular la grabación armada, la duración del
        // conteo y el modo del click llegan ANTES que el arranque: el motor decide si hay conteo en el
        // instante de arrancar (ADR 0035), con lo que se haya pedido hasta ese momento.
        if (s.bits and TEMPO != 0)      native.setTempo(s.tempo)
        if (s.bits and BAR != 0)        native.setBeatsPerBar(s.beatsPerBar)
        if (s.bits and BEAT_UNIT != 0)  native.setBeatUnit(s.unit.denominator)
        if (s.bits and VOLUME != 0)     native.setMetronomeVolume(s.volume)
        if (s.bits and METRONOME != 0)  native.setMetronomeEnabled(s.metronome)
        if (s.bits and CLICK_SOUND != 0)       native.setMetronomeSound(s.sound.nativeId)
        if (s.bits and BEAT_PATTERN != 0)      native.setMetronomeBeatLevels(s.pattern.packedLow, s.pattern.packedHigh)
        if (s.bits and CLICK_SUBDIVISION != 0) native.setMetronomeSubdivision(s.subdivision.perBeat)
        if (s.bits and CLICK_SWING != 0)       native.setClickSwing(s.swing.percent)
        if (s.bits and CLICK_DUCK != 0)        native.setMetronomeDuck(s.duck)
        if (s.bits and DELAY_SYNC != 0) native.setDelayTempoSync(s.delaySync.enabled, s.delaySync.division.beats)
        if (s.bits and CLICK_MODE != 0)   native.setClickMode(s.clickMode.nativeId)
        if (s.bits and COUNT_IN != 0)     native.setCountInBars(s.countIn.bars)
        if (s.bits and RECORD_ARMED != 0) native.setRecordArmed(s.recordArmed)
        // La posición va después del armado y ANTES del arranque: con grabación armada, el conteo parte de ella.
        if (s.bits and POSITION != 0)     native.setPosition(s.position)
        if (s.bits and RUNNING != 0) {
            // stop() + start() fusionados: el motor ignora un start repetido, así que primero ve la parada.
            if (s.running && s.stopFirst) native.setTransportRunning(false)
            native.setTransportRunning(s.running)
        }
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
        val pattern: BeatPattern,
        val subdivision: ClickSubdivision,
        val unit: BeatUnit,
        val duck: Float,
        val swing: ClickSwing,
        val recordArmed: Boolean,
        val countIn: CountInLength,
        val clickMode: ClickMode,
        val position: Long,
        val stopFirst: Boolean,
    )

    private companion object {
        /** ~60 Hz: por debajo de un fotograma, muy por encima del pulso más rápido (300 BPM = 5 Hz). */
        const val PULSE_POLL_MS = 16L

        /** Sondeo de la posición mostrada: ≈ 30 Hz basta para un contador de compás|pulso|tick. */
        const val POSITION_POLL_MS = 33L

        /** Cada cuántos sondeos del pulso se muestrea la latencia de salida: ≈ 1 s con [PULSE_POLL_MS] = 16 ms. */
        const val LATENCY_SAMPLE_EVERY_POLLS = 64
        const val NO_DATA = -1L

        const val TEMPO = 1 shl 0
        const val BAR = 1 shl 1
        const val VOLUME = 1 shl 2
        const val METRONOME = 1 shl 3
        const val DELAY_SYNC = 1 shl 4
        const val RUNNING = 1 shl 5
        const val CLICK_SOUND = 1 shl 6
        const val BEAT_PATTERN = 1 shl 7
        const val CLICK_SUBDIVISION = 1 shl 8
        const val BEAT_UNIT = 1 shl 9
        const val CLICK_DUCK = 1 shl 10
        const val RECORD_ARMED = 1 shl 11
        const val COUNT_IN = 1 shl 12
        const val CLICK_MODE = 1 shl 13
        // Orden de un solo uso: NO forma parte de ALL (recrear el motor no debe volver a saltar a una posición vieja).
        const val POSITION = 1 shl 14
        const val CLICK_SWING = 1 shl 15
        const val ALL = TEMPO or BAR or VOLUME or METRONOME or DELAY_SYNC or RUNNING or
            CLICK_SOUND or BEAT_PATTERN or CLICK_SUBDIVISION or BEAT_UNIT or CLICK_DUCK or
            RECORD_ARMED or COUNT_IN or CLICK_MODE or CLICK_SWING
    }
}
