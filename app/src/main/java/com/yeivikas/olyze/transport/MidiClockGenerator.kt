package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Genera el reloj MIDI de tiempo real (24 pulsos por negra, MIDI 1.0) hacia
 * [output] mientras está en marcha.
 *
 * Única responsabilidad: la temporización del reloj. No conoce el estado
 * de reproducción de la UI, el teclado ni los mensajes START/STOP — esos
 * los decide quien lo usa. El tempo se lee de [bpm] en CADA pulso, de modo
 * que un cambio de BPM se aplica en el siguiente pulso sin reiniciar el
 * generador (reiniciarlo emitía un pulso extra inmediato).
 *
 * ## Temporización
 * Los pulsos NO se espacian con un `delay()` de duración fija entre uno y el siguiente: eso acumula el
 * retraso de cada espera y obliga a redondear el intervalo a milisegundos enteros (a 120 BPM, 20,83 ms
 * pasaba a 20 ms: el reloj corría a 125 BPM). En su lugar cada pulso tiene una HORA OBJETIVO absoluta,
 * calculada con el intervalo exacto en nanosegundos y medida con [nanoClock] (monótono) desde el
 * arranque ([PulseSchedule]). Un pulso que llega tarde no desplaza a los siguientes: la media a largo
 * plazo es exacta al tempo, y lo único que queda es el jitter de cada pulso (la resolución de `delay()` es
 * de 1 ms más la latencia del planificador).
 *
 * ## Hilo
 * El bucle corre en el hilo de [scope]; cancelar el ámbito lo detiene. Quien lo construye debe darle un hilo
 * PROPIO (en la app, `olyze-midi-clock` con prioridad de audio): en el hilo principal un fotograma largo
 * retrasaría los pulsos. `start()`/`stop()` se llaman desde un único hilo (el principal) y `stop()` es
 * DETERMINISTA: cuando devuelve, ningún pulso más saldrá hacia [output] (la comprobación y el envío de cada
 * pulso comparten un candado con `stop()`), de modo que «ningún reloj tras STOP» ([TransportCoordinator]) se
 * cumple aunque el bucle esté a mitad de un envío. El envío es seguro entre hilos (`MidiOutputBridge` →
 * `AndroidMidiBackend.send`, bajo su propio candado). Sigue sin ser un reloj con precisión de muestra.
 *
 * No confundir con el reloj de transporte del motor (`TempoClock`, nativo,
 * con precisión de muestra, ADR 0028): ese gobierna el metrónomo y el delay
 * sincronizado DENTRO del motor. Este solo emite el reloj MIDI hacia fuera y
 * toma el mismo tempo ([bpm] viene del transporte del motor). Derivar el
 * reloj MIDI del reloj nativo exige un callback nativo→JVM por pulso y queda
 * como trabajo futuro.
 *
 */
class MidiClockGenerator(
    private val output: MidiOutputApi,
    private val bpm: StateFlow<Float>,
    private val scope: CoroutineScope,
    /** Reloj monótono en nanosegundos; inyectable para probar la planificación sin esperar. */
    private val nanoClock: () -> Long = System::nanoTime,
) : MidiClock {
    /** Protege [job], [generation] y [emitting], y serializa cada envío con [stop]. */
    private val gate = Any()
    private var job: Job? = null

    /** Número de la puesta en marcha vigente: un bucle viejo que aún no vio su cancelación no emite. */
    private var generation = 0L
    private var emitting = false

    val isRunning: Boolean get() = synchronized(gate) { job != null }

    /** Inicia el reloj. Si ya está en marcha no hace nada. */
    override fun start() {
        synchronized(gate) {
            if (job != null) return
            val run = ++generation
            emitting = true
            job = scope.launch {
                val schedule = PulseSchedule(startNanos = nanoClock())
                while (isActive) {
                    if (!emitPulse(run)) break
                    val waitNanos = schedule.nanosUntilNextPulse(nanoClock(), bpm.value)
                    if (waitNanos > 0L) delay(ceilToMillis(waitNanos))
                }
            }
        }
    }

    /** Detiene el reloj. Si no está en marcha no hace nada. Al volver, no saldrá ningún pulso más. */
    override fun stop() {
        val running = synchronized(gate) {
            emitting = false
            generation++
            job.also { job = null }
        }
        running?.cancel()
    }

    /** Emite un pulso si la puesta en marcha [run] sigue vigente. `false` = el bucle debe terminar. */
    private fun emitPulse(run: Long): Boolean {
        synchronized(gate) {
            if (!emitting || generation != run) return false
            output.sendClock()
        }
        return true
    }

    companion object {
        /** Pulsos de reloj por negra, fijados por el estándar MIDI. */
        const val PULSES_PER_QUARTER_NOTE = 24

        private const val NANOS_PER_MINUTE = 60_000_000_000.0
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Tempo mínimo con el que se calcula un intervalo: un valor no válido (cero, negativo, NaN) cuenta como este. */
        private const val FALLBACK_BPM = 1f

        /**
         * Intervalo EXACTO entre pulsos, en nanosegundos y con su parte fraccionaria, para [bpm]. No se
         * redondea: la fracción la arrastra [PulseSchedule] de un pulso al siguiente.
         */
        fun pulseIntervalNanos(bpm: Float): Double {
            val safeBpm = if (bpm >= FALLBACK_BPM) bpm else FALLBACK_BPM // también atrapa NaN
            return NANOS_PER_MINUTE / safeBpm / PULSES_PER_QUARTER_NOTE
        }

        /** Milisegundos enteros que cubren [nanos] (por exceso: despertar nunca antes de la hora objetivo). */
        internal fun ceilToMillis(nanos: Long): Long = (nanos + NANOS_PER_MILLI - 1L) / NANOS_PER_MILLI
    }
}

/**
 * Horas objetivo de los pulsos del reloj MIDI. Lógica pura (sin corrutinas ni reloj propio: la hora llega
 * como argumento), de modo que se prueba sin esperar.
 *
 * Lleva el tiempo teórico transcurrido desde el arranque como `Double` en nanosegundos (con fracción) y
 * pide el pulso `k` en `arranque + tiempo teórico`: la espera de cada pulso corrige la de los anteriores, así
 * que ni el redondeo de `delay()` ni un pulso tardío se acumulan. Cada intervalo se calcula con el tempo
 * del momento, de modo que un cambio de BPM rige desde el pulso siguiente.
 *
 * ## Atraso grande
 * Si la app estuvo suspendida o el hilo bloqueado más de [maxLagNanos], recuperar emitiría una ráfaga de pulsos
 * seguidos, que un receptor leería como un tempo disparado. En ese caso se REANCLA: el siguiente pulso cae un
 * intervalo después de ahora. Un atraso menor se recupera (los pulsos retrasados salen seguidos, sin perderse).
 */
internal class PulseSchedule(
    startNanos: Long,
    private val maxLagNanos: Long = MAX_LAG_NANOS,
) {
    private var anchorNanos = startNanos
    private var elapsedNanos = 0.0

    /**
     * Se llama justo después de emitir un pulso, con la hora actual [nowNanos] y el tempo [bpm]: devuelve
     * cuántos nanosegundos hay que esperar hasta el siguiente (0 si ya toca emitirlo).
     */
    fun nanosUntilNextPulse(nowNanos: Long, bpm: Float): Long {
        val interval = MidiClockGenerator.pulseIntervalNanos(bpm)
        elapsedNanos += interval
        val wait = anchorNanos + elapsedNanos.toLong() - nowNanos
        if (wait >= -maxLagNanos) return if (wait > 0L) wait else 0L
        anchorNanos = nowNanos
        elapsedNanos = interval
        return interval.toLong()
    }

    companion object {
        /** Atraso a partir del cual se reancla en vez de recuperar: 100 ms. */
        const val MAX_LAG_NANOS = 100_000_000L
    }
}
