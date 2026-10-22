package com.yeivikas.olyze.eliner.api.transport

/**
 * Swing de la subdivisión del click (ADR 0039): cuánto se RETRASA el click intermedio de cada par de
 * subdivisiones. Solo se oye con subdivisión de 2 o 4 por pulso ([ClickSubdivision]); con tresillos no hay par que
 * desplazar. El pulso (el tiempo fuerte) nunca se mueve.
 *
 * [percent] es el porcentaje del PAR que ocupa la primera subdivisión: 50 = recto, 67 ≈ tresillo (el «shuffle»
 * clásico) y 75 = punto y semi. Es el mismo eje que el «Swing» de Logic y Cubase. DEBE estar dentro de
 * `kMinClickSwingPercent..kMaxClickSwingPercent` (eliner/include/eliner/transport/ClickSound.h) y [STRAIGHT] ser el
 * valor por defecto del motor; `TransportConstantsMatchNativeTest` lo comprueba.
 */
enum class ClickSwing(val percent: Int) {
    /** Recto: sin swing (el comportamiento de siempre). */
    STRAIGHT(50),

    /** Ligero (≈ groove suave). */
    LIGHT(58),

    /** Medio. */
    MEDIUM(62),

    /** Tresillo (≈ 2:1): el shuffle clásico. */
    TRIPLET(67),

    /** Fuerte: punto y semi. */
    HARD(75),
}
