package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Calcula el tempo a partir de pulsaciones del usuario ("tap tempo").
 *
 * ## Responsabilidad única
 * Solo estadística de tiempos: no conoce Android, Compose ni el motor. El reloj se
 * INYECTA (cada [tap] recibe su marca de tiempo), de modo que es determinista y se
 * prueba sin dormir.
 *
 * ## Método (el que se espera de un DAW)
 *  - Con la primera pulsación no hay tempo: hacen falta al menos dos.
 *  - Una pausa larga entre pulsaciones ([resetAfterMs]) empieza una medición nueva: el
 *    usuario ha parado y vuelve a marcar, no es un tempo lentísimo.
 *  - Se promedian los últimos [maxTaps] intervalos, no solo el último: el tempo se
 *    estabiliza a medida que se golpea y un golpe flojo no lo desvía.
 *  - Con 4+ intervalos se DESCARTAN los atípicos (a más de un 25 % de la mediana): un
 *    golpe que se retrasó o se adelantó no arrastra el resultado.
 *  - Dos toques a menos de [minIntervalMs] se tratan como rebote del dedo y se ignoran.
 *  - El resultado se redondea a una décima y se limita al rango del motor.
 *
 * ## Hilos
 * Sin sincronización: se usa desde el hilo principal de la UI.
 */
class TapTempo(
    /** Pausa a partir de la cual la medición se reinicia. Debe superar el intervalo del tempo
     *  mínimo (20 BPM = 3000 ms), o los tempos lentos nunca se podrían medir. */
    private val resetAfterMs: Long = 3_500L,
    /** Cuántas pulsaciones se recuerdan (se promedian las últimas maxTaps − 1 diferencias). */
    private val maxTaps: Int = 8,
    /** Menos que esto entre dos toques es un rebote, no una pulsación (≈ 600 BPM). */
    private val minIntervalMs: Long = 100L,
) {
    private val times = ArrayDeque<Long>()

    /** Último tempo calculado, o `null` si aún no hay suficientes pulsaciones. */
    var lastBpm: Float? = null
        private set

    /** Pulsaciones que cuentan en la medición actual. */
    val tapCount: Int get() = times.size

    /**
     * Registra una pulsación en el instante [nowMs] (milisegundos, reloj monótono).
     * Devuelve el tempo estimado, o `null` mientras haya menos de dos pulsaciones.
     */
    fun tap(nowMs: Long): Float? {
        val last = times.lastOrNull()
        if (last != null) {
            val gap = nowMs - last
            if (gap > resetAfterMs) {
                reset()
            } else if (gap < minIntervalMs) {
                return lastBpm // rebote o reloj que retrocede: se ignora sin tocar la medición
            }
        }
        times.addLast(nowMs)
        while (times.size > maxTaps) times.removeFirst()
        lastBpm = estimate()
        return lastBpm
    }

    /** Descarta la medición en curso. */
    fun reset() {
        times.clear()
        lastBpm = null
    }

    private fun estimate(): Float? {
        if (times.size < 2) return null
        val intervals = times.zipWithNext { a, b -> (b - a).toDouble() }
        val kept = if (intervals.size >= MIN_INTERVALS_FOR_OUTLIER_FILTER) {
            val median = intervals.sorted().let { s ->
                if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            }
            intervals.filter { abs(it - median) <= median * OUTLIER_TOLERANCE }.ifEmpty { intervals }
        } else {
            intervals
        }
        val bpm = 60_000.0 / kept.average()
        val rounded = (bpm * 10.0).roundToInt() / 10f
        return rounded.coerceIn(EliNerTransportApi.MIN_TEMPO_BPM, EliNerTransportApi.MAX_TEMPO_BPM)
    }

    private companion object {
        const val MIN_INTERVALS_FOR_OUTLIER_FILTER = 4
        const val OUTLIER_TOLERANCE = 0.25
    }
}
