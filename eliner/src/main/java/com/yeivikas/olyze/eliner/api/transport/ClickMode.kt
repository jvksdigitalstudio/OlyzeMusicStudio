package com.yeivikas.olyze.eliner.api.transport

/**
 * CUÁNDO puede sonar el click del metrónomo. El botón del metrónomo sigue siendo el interruptor general: este
 * modo elige en qué situaciones suena CUANDO está activado. El conteo previo a grabar es aparte y suena siempre
 * que haya conteo ([CountInLength]), con el botón apagado o no.
 *
 * Es el mismo vocabulario que «During Play and Record / Only During Record / Only During Countoff» de Pro Tools,
 * «Click while recording / playing / Only during count-in» de Logic y las opciones de Metronome Setup de Cubase.
 *
 * [nativeId] es lo que cruza al motor y DEBE coincidir con `eliner::ClickMode`
 * (eliner/include/eliner/transport/ClickPolicy.h); `TransportConstantsMatchNativeTest` lo comprueba.
 * Append-only: no reutilizar ni reordenar valores.
 */
enum class ClickMode(val nativeId: Int) {
    /** Reproduciendo y grabando: el comportamiento histórico del metrónomo. */
    ALWAYS(0),

    /** Solo mientras hay una grabación armada; reproduciendo sin grabar, silencio. */
    RECORD_ONLY(1),

    /** Solo el conteo previo; después de él, silencio. */
    COUNT_IN_ONLY(2),
}
