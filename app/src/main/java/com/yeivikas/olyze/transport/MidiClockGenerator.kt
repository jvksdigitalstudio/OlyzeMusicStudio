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
 * El hilo de [scope] gobierna la vida del generador: cancelar el ámbito lo
 * detiene. Los pulsos se espacian con `delay()` — suficiente para reloj de
 * control, pero sin garantía de precisión de tiempo real (deriva de
 * planificación); un reloj de grado profesional requeriría un temporizador
 * de alta resolución en el lado nativo.
 *
 * No es seguro para hilos: usar desde un único hilo (el principal).
 */
class MidiClockGenerator(
    private val output: MidiOutputApi,
    private val bpm: StateFlow<Int>,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    val isRunning: Boolean get() = job != null

    /** Inicia el reloj. Si ya está en marcha no hace nada. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                output.sendClock()
                delay(pulseIntervalMs(bpm.value))
            }
        }
    }

    /** Detiene el reloj. Si no está en marcha no hace nada. */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Pulsos de reloj por negra, fijados por el estándar MIDI. */
        const val PULSES_PER_QUARTER_NOTE = 24

        /** Intervalo entre pulsos en ms para [bpm]; nunca menor que 1 ms. */
        fun pulseIntervalMs(bpm: Int): Long =
            (60_000.0 / bpm / PULSES_PER_QUARTER_NOTE).toLong().coerceAtLeast(1L)
    }
}
