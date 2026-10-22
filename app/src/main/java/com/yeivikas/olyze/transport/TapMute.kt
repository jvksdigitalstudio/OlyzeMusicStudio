package com.yeivikas.olyze.transport

/**
 * Curva del «Mute» del tap tempo: el metrónomo se silencia al empezar a tocar y VUELVE A ENTRAR GRADUALMENTE
 * a medida que la medición se afianza. Es lo que describe el manual de FL Studio para la opción Mute del tapper:
 * silencio al empezar a tocar y retorno automático durante los siguientes toques, cuando el tempo ya es más estable.
 *
 * Función pura: da el nivel de atenuación (0 mudo – 1 sin atenuar) para un número de toques. Quien la aplica
 * es el ViewModel; el motor suaviza el paso entre niveles (sin chasquidos).
 *
 * Mientras hay 1–2 toques la estimación es casi una adivinanza y un click sonando al tempo ANTIGUO desorienta
 * a quien está marcando otro: silencio. Desde el 3.er toque sube linealmente hasta [TapStatus.IDEAL_TAPS], donde
 * el click vuelve a su nivel normal.
 */
object TapMute {

    /** Toques con el click totalmente mudo. */
    private const val SILENT_TAPS = 2

    /** Nivel de atenuación para [tapCount] toques en la medición actual. */
    fun levelFor(tapCount: Int): Float {
        if (tapCount <= SILENT_TAPS) return 0f
        return ((tapCount - SILENT_TAPS) / (TapStatus.IDEAL_TAPS - SILENT_TAPS).toFloat()).coerceIn(0f, 1f)
    }
}
