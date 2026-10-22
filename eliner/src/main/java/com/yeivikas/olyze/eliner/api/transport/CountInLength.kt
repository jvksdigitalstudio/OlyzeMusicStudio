package com.yeivikas.olyze.eliner.api.transport

/**
 * Cuántos compases suenan ANTES de que empiece una grabación («count-in» en Ableton y Logic, «precount» en
 * Cubase, «countoff» en Pro Tools).
 *
 * [bars] es lo que cruza al motor y DEBE coincidir con `eliner::isValidCountInBars` y `kCountInBarsMax`
 * (eliner/include/eliner/transport/CountIn.h): `TransportConstantsMatchNativeTest` lee ese header y falla si
 * divergen. Solo existen estas duraciones; un valor que el motor no reconoce se ignora, no se recorta.
 *
 * ## Cuándo hay conteo
 * Solo cuando se ARRANCA el transporte desde parado con la grabación armada
 * ([EliNerTransportApi.setRecordArmed]). Armar a mitad de reproducción no lo inicia (como en los DAW: no hay
 * nada que contar si ya estás sonando). El conteo suena aunque el botón del metrónomo esté apagado y termina
 * siempre en una barra de compás. Ver ADR 0035.
 */
enum class CountInLength(val bars: Int) {
    /** Sin conteo: la grabación empieza al instante. */
    OFF(0),

    /** Un compás. */
    ONE_BAR(1),

    /** Dos compases. */
    TWO_BARS(2),

    /** Cuatro compases. */
    FOUR_BARS(4),
}
