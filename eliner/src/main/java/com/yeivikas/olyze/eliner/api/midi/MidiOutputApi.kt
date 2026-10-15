package com.yeivikas.olyze.eliner.api.midi

import kotlinx.coroutines.flow.StateFlow

/**
 * Superficie pública de SALIDA MIDI hacia el primer dispositivo con puerto
 * de salida disponible — lo que la UI/app necesita para enviar notas,
 * controladores y mensajes de transporte sin conocer puertos, dispositivos
 * ni la implementación concreta (`MidiOutputBridge`).
 *
 * Complementa a [EliNerMidiApi]: ésta es la API genérica (cualquier puerto,
 * eventos de entrada, bindings); [MidiOutputApi] es la API de conveniencia
 * "enviar al dispositivo activo". Una app que solo necesita tocar notas y
 * emitir reloj depende únicamente de esta interfaz.
 *
 * Si no hay un puerto de salida activo ([connected] es `false`), los
 * métodos `send*` descartan el mensaje sin efecto. El puerto activo es el
 * primero disponible entre los dispositivos conocidos y se re-evalúa
 * cuando cambia la lista de dispositivos.
 */
interface MidiOutputApi {
    /** `true` mientras exista un puerto de salida activo. */
    val connected: StateFlow<Boolean>

    /** Texto de estado listo para mostrar (dispositivo activo, sin conexión…). */
    val statusText: StateFlow<String>

    fun sendNoteOn(channel: Int, note: Int, velocity: Int)
    fun sendNoteOff(channel: Int, note: Int)
    fun sendCC(channel: Int, cc: Int, value: Int)

    /** [value] centrado en 0, rango −8192..8191. */
    fun sendPitchBend(channel: Int, value: Int)

    /** Mensajes de reloj/transporte del sistema (sin canal). */
    fun sendClock()
    fun sendStart()
    fun sendStop()
    fun sendContinue()
}
