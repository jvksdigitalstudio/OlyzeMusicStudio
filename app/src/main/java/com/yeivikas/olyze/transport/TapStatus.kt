package com.yeivikas.olyze.transport

/**
 * Lo que la UI muestra de la medición de "tap tempo" en curso: cuántos toques cuentan, qué tempo se
 * estima con ellos y si la estimación ya es fiable. Valor inmutable.
 *
 * @property count  toques que cuentan en la medición actual (0 = ninguna en curso).
 * @property bpm    pulsos por minuto estimados (con pulso de negra, el tempo del motor; ver [quarterNoteBpmFor]),
 *                  o `null` mientras haya menos de dos toques.
 * @property progress avance hacia el número IDEAL de toques ([IDEAL_TAPS]), 0.0–1.0: es la barra indicadora que
 *                    describe el manual de FL Studio (un indicador que muestra el número ideal de toques).
 * @property isStable `true` cuando los toques son regulares y suficientes (≥ 4): el usuario puede dejar de
 *                    tocar. Con menos toques la estimación es PROVISIONAL por naturaleza (un solo
 *                    intervalo humano varía ±5 % o más).
 */
data class TapStatus(val count: Int, val bpm: Float?, val isStable: Boolean) {

    val progress: Float get() = (count / IDEAL_TAPS.toFloat()).coerceIn(0f, 1f)

    companion object {
        /**
         * Toques tras los cuales la estimación ya no mejora de forma apreciable: los que recuerda [TapTempo]
         * (8). El manual de FL no da la cifra; es la de este diseño, medida en la simulación del ADR 0033.
         */
        const val IDEAL_TAPS = 8

        /** Sin medición en curso. */
        val IDLE = TapStatus(count = 0, bpm = null, isStable = false)
    }
}
