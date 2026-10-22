package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.transport.BeatPulse

/**
 * Línea de retardo de los pulsos que ve la UI (ADR 0037): retiene cada [BeatPulse] el tiempo que tarda el sonido
 * en OÍRSE, para que el indicador se encienda cuando suena el click y no antes.
 *
 * El motor publica un pulso en el instante en que RENDERIZA el bloque; el altavoz lo reproduce
 * `latencia de salida` milisegundos después (10–40 ms en AAudio exclusivo, cientos por Bluetooth). Sin
 * compensar, el indicador se adelanta justo esa cantidad y se nota al tocar con el metrónomo.
 *
 * Lógica pura: la hora llega por [clockNanos] (monótona), de modo que se prueba sin esperar ni hilos. NO es
 * segura entre hilos: la usa un único bucle de sondeo.
 *
 * - Orden: los pulsos salen en el mismo orden en que entraron, nunca se reordenan ni se pierden.
 * - Latencia desconocida (`null`), no finita o ≤ 0: sin retardo (el pulso sale en el mismo ciclo). Es el
 *   comportamiento anterior a este ADR, así que ante la duda no hay regresión.
 * - Latencia por encima de [MAX_LATENCY_MILLIS]: se limita (una medida corrupta no congela el indicador).
 * - Si se acumulan más de [MAX_PENDING] pulsos (el bucle estuvo parado mucho rato), salen todos de golpe: es
 *   preferible a retener una cola arbitraria.
 */
internal class PulseDelayLine(
    private val clockNanos: () -> Long,
) {
    private class Entry(val pulse: BeatPulse, val observedAtNanos: Long)

    private val pending = ArrayDeque<Entry>()

    /** Registra un pulso recién observado en el motor. */
    fun offer(pulse: BeatPulse) {
        pending.addLast(Entry(pulse, clockNanos()))
    }

    /** Devuelve, en orden, los pulsos que ya han tenido tiempo de oírse con la latencia [latencyMillis]. */
    fun drain(latencyMillis: Float?): List<BeatPulse> {
        if (pending.isEmpty()) return emptyList()
        val delayNanos = delayNanosFor(latencyMillis)
        val now = clockNanos()
        val ready = ArrayList<BeatPulse>(pending.size)
        if (pending.size > MAX_PENDING) {
            pending.forEach { ready += it.pulse }
            pending.clear()
            return ready
        }
        while (pending.isNotEmpty() && now - pending.first().observedAtNanos >= delayNanos) {
            ready += pending.removeFirst().pulse
        }
        return ready
    }

    /** Descarta los pulsos retenidos (la colección terminó). */
    fun clear() = pending.clear()

    private fun delayNanosFor(latencyMillis: Float?): Long {
        if (latencyMillis == null || !latencyMillis.isFinite() || latencyMillis <= 0f) return 0L
        return (latencyMillis.coerceAtMost(MAX_LATENCY_MILLIS) * NANOS_PER_MILLI).toLong()
    }

    companion object {
        /** Tope de la latencia que se aplica. Una latencia real mayor (≈ 1 s) ya no es tocable. */
        const val MAX_LATENCY_MILLIS = 1000f
        const val MAX_PENDING = 64
        private const val NANOS_PER_MILLI = 1_000_000f
    }
}
