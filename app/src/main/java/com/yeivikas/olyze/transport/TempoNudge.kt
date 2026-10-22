package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi

/**
 * Nudge del tempo: mientras se mantiene pulsado, el tempo se acelera o frena TEMPORALMENTE; al soltar vuelve
 * al tempo fijado. Sirve para alinear la fase con una fuente en vivo sin cambiar el tempo del proyecto. Es el
 * comportamiento que describe el manual de FL Studio para el tap tempo: pulsado, acelera o frena el tempo del
 * proyecto para sincronizarlo con otra fuente en vivo, y al soltarlo se vuelve al tempo fijado.
 *
 * Es DISTINTO de los pasos ±0,1/±1/±10 de la interfaz, que son cambios permanentes.
 *
 * Función pura. La cantidad ([FRACTION]) es una decisión de diseño de esta app: el manual de FL no la
 * especifica. 4 % es un empuje que corrige unos 20 ms de fase por segundo a 120 BPM y no es disonante.
 */
object TempoNudge {

    /** Fracción del tempo que se suma o resta mientras dura el nudge. */
    const val FRACTION = 0.04f

    /**
     * Tempo durante el nudge: [base] más (dirección 1) o menos (dirección −1) un [FRACTION], limitado al rango
     * del motor. Dirección 0 u otro valor no cambia nada.
     */
    fun target(base: Float, direction: Int): Float {
        val sign = when {
            direction > 0 -> 1f
            direction < 0 -> -1f
            else -> 0f
        }
        return (base * (1f + sign * FRACTION)).coerceIn(EliNerTransportApi.MIN_TEMPO_BPM, EliNerTransportApi.MAX_TEMPO_BPM)
    }
}
