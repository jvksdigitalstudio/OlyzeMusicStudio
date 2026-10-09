package com.yeivikas.olyze.eliner.api.transport

/**
 * Duración musical expresada en PULSOS (negras).
 *
 * Es el vocabulario con el que una app pide un efecto sincronizado al tempo
 * ("delay a corchea con puntillo") sin conocer segundos ni BPM: el motor
 * resuelve `segundos = 60 / BPM × beats`. [beats] es el único dato que cruza
 * al motor nativo, así que añadir una división nueva es añadir una línea aquí.
 *
 * Los puntillos multiplican por 1,5 y los tresillos por 2/3, tal como en
 * notación musical.
 */
enum class NoteDivision(val beats: Float) {
    WHOLE(4f),
    HALF(2f),

    QUARTER_DOTTED(1.5f),
    QUARTER(1f),
    QUARTER_TRIPLET(2f / 3f),

    EIGHTH_DOTTED(0.75f),
    EIGHTH(0.5f),
    EIGHTH_TRIPLET(1f / 3f),

    SIXTEENTH_DOTTED(0.375f),
    SIXTEENTH(0.25f),
    SIXTEENTH_TRIPLET(1f / 6f),
}
