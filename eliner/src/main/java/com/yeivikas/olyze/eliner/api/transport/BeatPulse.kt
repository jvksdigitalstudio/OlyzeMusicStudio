package com.yeivikas.olyze.eliner.api.transport

/**
 * Posición de pulso del transporte, para indicadores visuales (ADR 0029).
 *
 * Es una instantánea del reloj del motor nativo: [sequence] sube en CADA pulso
 * (también con compás de 1 tiempo, donde [beatInBar] no cambia), de modo que la
 * UI detecta "ha llegado un pulso nuevo" comparando [sequence], no [beatInBar].
 *
 * @property sequence    contador monótono de pulsos desde que existe el motor nativo. Se
 *                       reinicia a 0 solo si el motor se recrea. No se reinicia al parar/arrancar.
 * @property beatInBar   tiempo dentro del compás, 0 = primer tiempo (el acentuado).
 * @property isRunning   `true` mientras el transporte está en marcha.
 *
 * ## Sincronía con el oído
 * La instantánea corresponde al momento en que el hilo de audio RENDERIZÓ el
 * bloque con el pulso. El sonido llega al altavoz con la latencia de salida del
 * dispositivo (típicamente decenas de ms con Oboe de baja latencia), así que un
 * destello visual puede adelantarse ese tiempo al click. Es una limitación de
 * medir en el hilo de audio, no un error de reloj: el reloj sigue siendo exacto
 * a la muestra.
 */
data class BeatPulse(
    val sequence: Long,
    val beatInBar: Int,
    val isRunning: Boolean,
) {
    companion object {
        /** Estado de reposo: sin pulsos, transporte parado. */
        val IDLE = BeatPulse(sequence = 0L, beatInBar = 0, isRunning = false)

        // Formato empaquetado: DEBE coincidir con eliner/transport/BeatPulse.h
        // (lo comprueba TransportConstantsMatchNativeTest leyendo la cabecera).
        internal const val BEAT_MASK: Long = 0xFFL
        internal const val RUNNING_BIT: Long = 1L shl 8
        internal const val SEQUENCE_SHIFT: Int = 16

        /** Decodifica el valor empaquetado que publica el motor nativo. Debe ser >= 0. */
        internal fun fromPacked(raw: Long): BeatPulse = BeatPulse(
            sequence = raw ushr SEQUENCE_SHIFT,
            beatInBar = (raw and BEAT_MASK).toInt(),
            isRunning = (raw and RUNNING_BIT) != 0L,
        )
    }
}
