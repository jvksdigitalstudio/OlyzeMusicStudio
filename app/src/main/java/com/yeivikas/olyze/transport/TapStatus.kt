package com.yeivikas.olyze.transport

/**
 * Lo que la UI muestra de la medición de "tap tempo" en curso: cuántos toques cuentan, qué tempo se
 * estima con ellos y si la estimación ya es fiable. Valor inmutable.
 *
 * @property count  toques que cuentan en la medición actual (0 = ninguna en curso).
 * @property bpm    tempo estimado, o `null` mientras haya menos de dos toques.
 * @property isStable `true` cuando los toques son regulares y suficientes (≥ 4): el usuario puede dejar de
 *                    tocar. Con menos toques la estimación es PROVISIONAL por naturaleza (un solo
 *                    intervalo humano varía ±5 % o más).
 */
data class TapStatus(val count: Int, val bpm: Float?, val isStable: Boolean) {
    companion object {
        /** Sin medición en curso. */
        val IDLE = TapStatus(count = 0, bpm = null, isStable = false)
    }
}
