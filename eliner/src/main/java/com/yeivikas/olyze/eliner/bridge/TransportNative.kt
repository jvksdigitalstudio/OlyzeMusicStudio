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

    /**
     * Patrón por tiempo empaquetado ([com.yeivikas.olyze.eliner.api.transport.BeatPattern.packedLow]/
     * [com.yeivikas.olyze.eliner.api.transport.BeatPattern.packedHigh]): UN comando con las dos palabras.
     */
    fun setMetronomeBeatLevels(low: Int, high: Int)

    /** Atenuación temporal del click, 0–1 (ver `EliNerTransportApi.setMetronomeDuck`). */
    fun setMetronomeDuck(level: Float)

    /** Reinicia el compás (orden de un solo uso). */
    fun resyncBar()

    /** Denominador del compás (`BeatUnit.denominator`). Fuera de {2, 4, 8, 16}: el motor lo ignora. */
    fun setBeatUnit(denominator: Int)

    /** Clicks por pulso, 1–4 (`ClickSubdivision.perBeat`). Fuera de rango: el motor lo ignora. */
    fun setMetronomeSubdivision(perBeat: Int)

    /** Swing de la subdivisión en % (`ClickSwing.percent`, 50–75). Fuera de rango: el motor lo ignora. */
    fun setClickSwing(percent: Int)

    /**
     * Muestrea la latencia de salida del stream de audio y devuelve la estimación en milisegundos, o un valor
     * negativo si no hay medida (stream parado o arrancando, o el dispositivo aún sin marcas de tiempo). NUNCA
     * espera (ADR 0037). Hilo de control, jamás el de audio.
     */
    fun sampleOutputLatency(): Float

    /** Coloca el transporte en [ticks] (el motor recorta a [0, 2^24]). Orden, no estado (ADR 0036). */
    fun setPosition(ticks: Long)

    /** Posición de la canción en ticks publicada por el hilo de audio. Lectura atómica, sin bloquear. */
    fun readPosition(): Long

    /** Grabación armada (ADR 0035). Con el transporte parado, arrancar con esto activo inicia el conteo. */
    fun setRecordArmed(armed: Boolean)

    /** Compases de conteo (`CountInLength.bars`: 0, 1, 2 o 4). Cualquier otro valor: el motor lo ignora. */
    fun setCountInBars(bars: Int)

    /** Modo del click (`ClickMode.nativeId`). Fuera de rango: el motor lo ignora. */
    fun setClickMode(mode: Int)

    /**
     * Instantánea de pulso empaquetada (formato en `BeatPulse.h`), SIN bloquear.
     * Devuelve `-1` si el motor está ocupado arrancando y no puede responder
     * ahora (el llamador conserva su último valor) y `0` si no hay motor.
     */
    fun readPulse(): Long
}
