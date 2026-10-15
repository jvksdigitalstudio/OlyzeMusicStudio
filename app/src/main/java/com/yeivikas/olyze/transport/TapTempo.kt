package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Calcula el tempo a partir de pulsaciones del usuario ("tap tempo").
 *
 * ## Responsabilidad única
 * Solo estadística de tiempos: no conoce Android, Compose ni el motor. El reloj se INYECTA (cada [tap]
 * recibe su marca de tiempo), de modo que es determinista y se prueba sin dormir. Conviene pasarle la
 * hora REAL del toque (`PointerInputChange.uptimeMillis`) y no la hora a la que la app lo procesa: Android
 * entrega los toques agrupados por fotograma y eso añade hasta ~16 ms de ruido gratuito.
 *
 * ## Estimador: regresión lineal, no promedio de intervalos
 * Promediar los intervalos entre toques consecutivos equivale a `(último − primero) / (n − 1)`: usa SOLO
 * los dos toques de los extremos y todo el ruido de los demás se tira. Aquí se ajusta una recta
 * `tiempo = a + intervalo · número_de_toque` por mínimos cuadrados con TODOS los toques; con 6–8 toques
 * el error baja entre un 15 % y un 30 % (con 2–3 es idéntico: matemáticamente es lo mismo).
 *
 * ## Robustez ante errores humanos
 *  - **Atípicos:** con 5+ toques, los que se alejan de la recta más de `max(30 ms, 12 % del intervalo)` se
 *    descartan y se reajusta: un dedo flojo no arrastra el tempo.
 *  - **Pausa larga** (> [resetAfterMs]): medición nueva. Debe superar el intervalo del tempo mínimo
 *    (20 BPM = 3000 ms) o los tempos lentos nunca se podrían medir.
 *  - **Hueco o cambio brusco** (un toque a más de 1,8× el intervalo actual, con ≥ 3 toques): se reinicia
 *    con ese toque como primero. Un compás saltado o un cambio de tempo NO corrompen la medición y el motor
 *    conserva su tempo hasta que haya dos toques nuevos.
 *  - **Rebote o flam** (menos de [minIntervalMs], o con ≥ 3 toques, menos de 0,4× el intervalo): se ignora.
 *  - El resultado se redondea a una décima y se limita al rango del motor.
 *
 * ## Fiabilidad
 * [isStable] es `true` con ≥ 4 toques válidos cuyo error respecto a la recta no supera el 3 % del intervalo:
 * la señal de «ya está, puedes dejar de tocar».
 *
 * ## Hilos
 * Sin sincronización: se usa desde el hilo principal de la UI.
 */
class TapTempo(
    /** Pausa a partir de la cual la medición se reinicia. */
    val resetAfterMs: Long = 3_500L,
    /** Cuántos toques se recuerdan: la recta se ajusta a los últimos [maxTaps]. */
    private val maxTaps: Int = 8,
    /** Menos que esto entre dos toques es un rebote, no una pulsación (≈ 600 BPM). */
    private val minIntervalMs: Long = 100L,
) {
    private val times = ArrayDeque<Long>()

    /** Intervalo estimado (ms) de la medición en curso, sin redondear ni limitar. */
    private var intervalMs: Double? = null

    /** Último tempo calculado, o `null` si aún no hay suficientes toques. */
    var lastBpm: Float? = null
        private set

    /** Ver «Fiabilidad» en la documentación de la clase. */
    var isStable: Boolean = false
        private set

    /** Toques que cuentan en la medición actual. */
    val tapCount: Int get() = times.size

    /** Instantánea para la UI. */
    fun status(): TapStatus = TapStatus(count = tapCount, bpm = lastBpm, isStable = isStable)

    /**
     * Registra un toque en el instante [nowMs] (milisegundos, reloj monótono). Devuelve el tempo estimado,
     * o `null` mientras haya menos de dos toques.
     */
    fun tap(nowMs: Long): Float? {
        val last = times.lastOrNull()
        if (last != null) {
            val gap = nowMs - last
            if (gap > resetAfterMs) {
                reset()
            } else if (gap < minIntervalMs) {
                return lastBpm // rebote o reloj que retrocede: se ignora sin tocar la medición
            } else {
                val current = intervalMs
                if (current != null && times.size >= MIN_TAPS_FOR_ADAPTIVE_RULES) {
                    if (gap > current * RESTART_FACTOR) reset()       // hueco o cambio brusco: medición nueva
                    else if (gap < current * FLAM_FACTOR) return lastBpm // doble toque: se ignora
                }
            }
        }
        times.addLast(nowMs)
        while (times.size > maxTaps) times.removeFirst()
        estimate()
        return lastBpm
    }

    /** Descarta la medición en curso. */
    fun reset() {
        times.clear()
        intervalMs = null
        lastBpm = null
        isStable = false
    }

    private fun estimate() {
        if (times.size < 2) {
            intervalMs = null; lastBpm = null; isStable = false
            return
        }
        val points = times.mapIndexed { index, t -> Point(index, t.toDouble()) }
        var line = fit(points)
        var used = points
        if (points.size >= MIN_TAPS_FOR_OUTLIER_FILTER) {
            val tolerance = max(OUTLIER_MIN_MS, OUTLIER_TOLERANCE * line.slope)
            val kept = points.filter { abs(it.t - line.at(it.index)) <= tolerance }
            if (kept.size >= MIN_TAPS_AFTER_FILTER && kept.size < points.size) {
                line = fit(kept)
                used = kept
            }
        }
        intervalMs = line.slope

        val rms = sqrt(used.sumOf { val r = it.t - line.at(it.index); r * r } / used.size)
        isStable = used.size >= STABLE_MIN_TAPS && rms <= STABLE_MAX_RELATIVE_RMS * line.slope

        val bpm = ((60_000.0 / line.slope) * 10.0).roundToInt() / 10f
        lastBpm = bpm.coerceIn(EliNerTransportApi.MIN_TEMPO_BPM, EliNerTransportApi.MAX_TEMPO_BPM)
    }

    /** Un toque: su número de orden dentro de la medición y su instante en ms. */
    private class Point(val index: Int, val t: Double)

    /** Recta `t = intercept + slope · index`; [slope] es el intervalo entre toques en ms. */
    private class Line(val slope: Double, val intercept: Double) {
        fun at(index: Int): Double = intercept + slope * index
    }

    /** Mínimos cuadrados. Precondición: al menos dos puntos con índices distintos. */
    private fun fit(points: List<Point>): Line {
        val n = points.size
        val meanIndex = points.sumOf { it.index.toDouble() } / n
        val meanT = points.sumOf { it.t } / n
        var num = 0.0
        var den = 0.0
        for (p in points) {
            val di = p.index - meanIndex
            num += di * (p.t - meanT)
            den += di * di
        }
        val slope = num / den
        return Line(slope, meanT - slope * meanIndex)
    }

    private companion object {
        const val MIN_TAPS_FOR_ADAPTIVE_RULES = 3   // 2+ intervalos: ya hay un intervalo con el que comparar
        const val RESTART_FACTOR = 1.8
        const val FLAM_FACTOR = 0.4

        const val MIN_TAPS_FOR_OUTLIER_FILTER = 5
        const val MIN_TAPS_AFTER_FILTER = 3
        const val OUTLIER_MIN_MS = 30.0
        const val OUTLIER_TOLERANCE = 0.12

        const val STABLE_MIN_TAPS = 4
        const val STABLE_MAX_RELATIVE_RMS = 0.03
    }
}
