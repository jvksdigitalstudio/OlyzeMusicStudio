package com.yeivikas.olyze.eliner.api.transport

/**
 * Unidad de pulso del compás: la figura que cuenta cada pulso (el DENOMINADOR del compás).
 *
 * Debe coincidir con `eliner::BeatUnit` (eliner/include/eliner/transport/BeatUnit.h): [denominator] es
 * lo que cruza al motor y `TransportConstantsMatchNativeTest` lee ese header para que no divergan.
 *
 * ## Qué cambia y qué NO
 * El TEMPO ([EliNerTransportApi.tempoBpm]) sigue siendo SIEMPRE en negras por minuto. La unidad solo
 * fija cuánto dura un PULSO del metrónomo: [quartersPerPulse] negras. En 6/8 el pulso es una corchea
 * (0,5 negra), así que a 120 BPM suenan 240 pulsos por minuto. El delay sincronizado y el reloj MIDI
 * siguen midiéndose en negras y no se ven afectados.
 */
enum class BeatUnit(val denominator: Int) {
    /** Pulso de blanca (x/2). */
    HALF(2),

    /** Pulso de negra (x/4): el de siempre. */
    QUARTER(4),

    /** Pulso de corchea (x/8): 6/8, 7/8, 12/8… */
    EIGHTH(8),

    /** Pulso de semicorchea (x/16). */
    SIXTEENTH(16);

    /** Duración de un pulso expresada en negras. */
    val quartersPerPulse: Double get() = 4.0 / denominator
}
