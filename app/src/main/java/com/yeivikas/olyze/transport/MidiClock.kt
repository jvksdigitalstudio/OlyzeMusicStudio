package com.yeivikas.olyze.transport

/**
 * Reloj MIDI que se puede arrancar y parar.
 *
 * Existe para que quien orquesta el transporte ([TransportCoordinator]) dependa de
 * esta capacidad mínima y no de la implementación con corrutinas
 * ([MidiClockGenerator]); así su secuencia de arranque/parada se prueba sin
 * temporizadores reales.
 */
interface MidiClock {
    /** Inicia el reloj. Si ya está en marcha no hace nada. */
    fun start()

    /** Detiene el reloj. Si no está en marcha no hace nada. */
    fun stop()
}
