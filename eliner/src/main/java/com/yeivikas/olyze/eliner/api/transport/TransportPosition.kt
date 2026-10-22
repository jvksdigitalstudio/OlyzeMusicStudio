package com.yeivikas.olyze.eliner.api.transport

/**
 * Posición del transporte: unidad y conversiones puras (ADR 0036). Réplica EXACTA de
 * `eliner/include/eliner/transport/Position.h`; `TransportConstantsMatchNativeTest` lee ese header y falla si
 * las constantes divergen, y los valores de [songPositionFor] están fijados contra los del test nativo.
 *
 * La posición se mide en TICKS a [TICKS_PER_QUARTER] por negra (PPQ). 960 divide exactamente la negra en 2, 3,
 * 4, 5, 6, 8, 10, 12, 15 y 16 partes, así que corcheas, tresillos, semicorcheas y quintillos caen en ticks
 * enteros. La posición 0 es el primer tiempo del compás 1.
 */
object TransportPosition {
    /** Ticks por negra (PPQ). */
    const val TICKS_PER_QUARTER: Int = 960

    /**
     * Posición máxima: 2^24 ticks (≈ 2,4 h a 120 BPM). Viaja al motor como `float`, que representa EXACTAMENTE los
     * enteros hasta ahí; por encima se recorta, nunca se desborda.
     */
    const val MAX_TICKS: Long = 1L shl 24

    /** Ticks que dura un «MIDI beat» del Song Position Pointer: una semicorchea (6 pulsos de reloj MIDI). */
    const val TICKS_PER_MIDI_BEAT: Int = TICKS_PER_QUARTER / 4

    /** Valor máximo del Song Position Pointer: 14 bits. */
    const val MAX_SONG_POSITION: Int = 16383

    /** Posición descompuesta para mostrarla: todo en base 0 (la UI suma 1 a compás y pulso). */
    data class BarBeatTick(val bar: Long, val beat: Int, val tick: Int)

    /**
     * Posición → compás | pulso | tick, con un compás CONSTANTE desde el origen (el mapa de compases llega con el
     * mapa de tempo). Réplica de `eliner::barBeatTickFor` (Position.h) con división de SUELO, de modo que las
     * posiciones negativas (conteo previo antes del compás 1) caen en el compás anterior y no en el 0.
     *
     * @param beatsPerBar pulsos por compás; menos de 1 se trata como 1.
     * @param quartersPerPulse duración del pulso en negras ([BeatUnit.quartersPerPulse]).
     */
    fun barBeatTickFor(ticks: Long, beatsPerBar: Int, quartersPerPulse: Double): BarBeatTick {
        val pulseTicks = ticksPerPulse(quartersPerPulse)
        val bpb = beatsPerBar.coerceAtLeast(1).toLong()
        val index = Math.floorDiv(ticks, pulseTicks)
        return BarBeatTick(
            bar = Math.floorDiv(index, bpb),
            beat = Math.floorMod(index, bpb).toInt(),
            tick = Math.floorMod(ticks, pulseTicks).toInt(),
        )
    }

    /** Duración de UN pulso en ticks; como en el motor, se redondea al tick más cercano y nunca es menor que 1. */
    fun ticksPerPulse(quartersPerPulse: Double): Long =
        Math.round(quartersPerPulse * TICKS_PER_QUARTER).coerceAtLeast(1L)

    /** Recorta [ticks] al rango que acepta el motor ([0, MAX_TICKS]). */
    fun clamp(ticks: Long): Long = ticks.coerceIn(0L, MAX_TICKS)

    /**
     * Posición → Song Position Pointer (en semicorcheas). Redondea hacia abajo, porque un receptor no puede
     * colocarse entre dos semicorcheas, y recorta al rango del mensaje (0..16383).
     */
    fun songPositionFor(ticks: Long): Int {
        if (ticks <= 0L) return 0
        return (ticks / TICKS_PER_MIDI_BEAT).coerceAtMost(MAX_SONG_POSITION.toLong()).toInt()
    }
}
