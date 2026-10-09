package com.yeivikas.olyze.ui.components.tempo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Matemática pura del dial de tempo (sin Compose): ángulos, giro y mapa BPM ↔ arco.
 *
 * Se separa del composable para poder probarla sin UI: los errores típicos de un dial
 * (saltos al cruzar los 360°, sentido invertido, valores fuera de rango) son aritmética.
 *
 * ## Convenciones
 * Los ángulos están en GRADOS, medidos como en el lienzo de Compose: 0° = las 3 en punto y
 * crecen en sentido HORARIO (el eje Y de pantalla apunta hacia abajo). El arco visible
 * empieza en [START_ANGLE_DEG] (abajo a la izquierda) y barre [SWEEP_DEG] hacia la derecha
 * dejando libre el hueco inferior, como un potenciómetro.
 *
 * ## Gesto
 * El dial es un "jog": girar el dedo alrededor del centro CAMBIA el tempo en relación al giro
 * ([BPM_PER_DEGREE]), sin saltar al punto tocado. Una vuelta completa son 90 BPM: rápido para ir
 * lejos, y los botones ±1/±10 y el nudge para afinar.
 */
internal object TempoDialMath {

    const val START_ANGLE_DEG = 120f
    const val SWEEP_DEG = 300f
    const val BPM_PER_DEGREE = 0.25f

    /** Ángulo (0 ≤ a < 360) del punto (dx, dy) respecto al centro, en la convención de arriba. */
    fun angleDeg(dx: Float, dy: Float): Float {
        val deg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
        return if (deg < 0f) deg + 360f else deg
    }

    /**
     * Giro con signo, por el camino CORTO, de [previous] a [current] (ambos en 0..360).
     * Resultado en (−180, 180]: positivo = sentido horario. Sin esto, pasar de 359° a 1° se
     * leería como −358° y el tempo daría un salto enorme al cruzar el eje.
     */
    fun shortestDeltaDeg(previous: Float, current: Float): Float {
        var d = (current - previous) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Tempo tras un giro de [deltaDeg], limitado a [[min], [max]]. NO redondea (ver [snap]). */
    fun bpmAfterTurn(bpm: Float, deltaDeg: Float, min: Float, max: Float): Float =
        (bpm + deltaDeg * BPM_PER_DEGREE).coerceIn(min, max)

    /** Posición del tempo en el arco, 0..1. */
    fun fraction(bpm: Float, min: Float, max: Float): Float =
        if (max <= min) 0f else ((bpm - min) / (max - min)).coerceIn(0f, 1f)

    /** Ángulo del selector (la bolita) para ese tempo. */
    fun thumbAngleDeg(bpm: Float, min: Float, max: Float): Float =
        START_ANGLE_DEG + SWEEP_DEG * fraction(bpm, min, max)

    /** Redondea a una décima: la resolución con la que se muestra y se envía el tempo. */
    fun snap(bpm: Float): Float = (bpm * 10f).roundToInt() / 10f

    /** Tempo tras un paso de [delta] (±0,1, ±1, ±10…): redondeado a décimas y limitado al rango. */
    fun nudged(bpm: Float, delta: Float, min: Float, max: Float): Float =
        snap((bpm + delta).coerceIn(min, max))
}
