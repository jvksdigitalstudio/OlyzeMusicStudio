package com.yeivikas.olyze.eliner.bridge

/**
 * Superficie del motor nativo para el dominio de transporte (ADR 0028).
 *
 * Existe para separar dos responsabilidades que de otro modo irían juntas:
 * llamar a JNI ([EliNerTransportBridge]) y conservar/validar el estado deseado
 * ([TransportController]). Gracias a esta interfaz, el controlador se prueba
 * en la JVM con un doble, sin cargar la librería nativa.
 *
 * Cada método es una orden al motor y NO bloquea: la implementación real
 * valida y encola en el motor nativo. Si el motor nativo todavía no existe o
 * ya fue destruido, la orden se descarta (el estado deseado lo conserva
 * [TransportController] y lo reaplica al arrancar).
 */
internal interface TransportNative {
    fun setTempo(bpm: Float)
    fun setTransportRunning(running: Boolean)
    fun setBeatsPerBar(beats: Int)
    fun setMetronomeEnabled(enabled: Boolean)
    fun setMetronomeVolume(volume: Float)

    /** [beats]: duración sincronizada en pulsos (negras), ver `NoteDivision.beats`. */
    fun setDelayTempoSync(enabled: Boolean, beats: Float)

    /** Índice de `MetronomeSound.nativeId` (ClickSound en C++). Fuera de rango: el motor lo ignora. */
    fun setMetronomeSound(sound: Int)

    fun setMetronomeAccent(enabled: Boolean)

    /** Clicks por pulso, 1–4 (`ClickSubdivision.perBeat`). Fuera de rango: el motor lo ignora. */
    fun setMetronomeSubdivision(perBeat: Int)

    /**
     * Instantánea de pulso empaquetada (formato en `BeatPulse.h`), SIN bloquear.
     * Devuelve `-1` si el motor está ocupado arrancando y no puede responder
     * ahora (el llamador conserva su último valor) y `0` si no hay motor.
     */
    fun readPulse(): Long
}
