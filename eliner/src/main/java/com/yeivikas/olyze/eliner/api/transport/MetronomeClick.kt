package com.yeivikas.olyze.eliner.api.transport

/**
 * Sonido del click del metrónomo.
 *
 * El orden y los índices DEBEN coincidir con `eliner::ClickSound`
 * (eliner/include/eliner/transport/ClickSound.h): [nativeId] es lo que cruza al
 * motor. `TransportConstantsMatchNativeTest` lee ese header y falla si divergen.
 * Append-only: no reutilizar ni reordenar valores.
 */
enum class MetronomeSound(val nativeId: Int) {
    /** Seno limpio y corto: el sonido histórico del motor. */
    CLASSIC(0),

    /** Bloque de madera: seco, de caída muy rápida. */
    WOOD(1),

    /** Pitido electrónico, más largo y grave. */
    BEEP(2),

    /** Cencerro: metálico, de caída larga. */
    COWBELL(3),

    /** Hi-hat: ráfaga de ruido muy corta. */
    HAT(4),
}

/**
 * Clicks por pulso del metrónomo: además del pulso, suenan clicks más suaves en
 * las subdivisiones (corcheas, tresillos, semicorcheas).
 *
 * [perBeat] es lo que cruza al motor y DEBE coincidir con `eliner::ClickSubdivision`
 * (mismo header que [MetronomeSound]; lo comprueba el mismo test).
 */
enum class ClickSubdivision(val perBeat: Int) {
    /** Solo el pulso. */
    NONE(1),

    /** Dos por pulso: corcheas. */
    EIGHTHS(2),

    /** Tres por pulso: tresillos de corchea. */
    TRIPLETS(3),

    /** Cuatro por pulso: semicorcheas. */
    SIXTEENTHS(4),
}
