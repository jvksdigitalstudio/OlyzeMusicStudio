package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi

/**
 * Orquesta lo que significa "reproducir" y "parar" para la app: coordina el
 * transporte del motor, los mensajes MIDI de transporte hacia fuera y el reloj MIDI.
 *
 * ## Responsabilidad única
 * La SECUENCIA. No guarda estado (el estado de reproducción es del motor:
 * [EliNerTransportApi.isRunning]), no conoce la UI ni Compose y no genera
 * temporización (eso es de [MidiClock]). Antes esta secuencia vivía mezclada
 * dentro de `MainViewModel`.
 *
 * ## Orden, y por qué importa
 *  - **Arrancar:** primero el motor (el primer pulso suena en el primer frame),
 *    luego START hacia fuera y por último el reloj, de modo que el dispositivo externo
 *    recibe START antes que el primer pulso de reloj.
 *  - **Parar:** primero se detiene el reloj (ningún pulso de reloj tras STOP), luego el
 *    motor, luego STOP hacia fuera y, al final, se silencian las notas que quedaran
 *    sonando (un STOP no debe dejar notas colgadas).
 *
 * @param silenceAllNotes silencia todas las voces; se inyecta como función para no
 *   depender de la API de audio completa.
 */
class TransportCoordinator(
    private val transport: EliNerTransportApi,
    private val midiOutput: MidiOutputApi,
    private val midiClock: MidiClock,
    private val silenceAllNotes: () -> Unit,
) {
    /** Reproduce si estaba parado; detiene si estaba en marcha. */
    fun togglePlay() {
        if (transport.isRunning.value) stopAll() else startAll()
    }

    /**
     * Detiene y deja el transporte listo para empezar desde el primer tiempo (el
     * siguiente arranque vuelve al compás 1). Equivale a parar.
     */
    fun rewind() = stopAll()

    private fun startAll() {
        transport.start()
        midiOutput.sendStart()
        midiClock.start()
    }

    private fun stopAll() {
        midiClock.stop()
        transport.stop()
        midiOutput.sendStop()
        silenceAllNotes()
    }
}
