package com.yeivikas.olyze.eliner.dspfoundation

/**
 * A reusable, ordered list of [DspProcessor]s meant to run in series —
 * "permitirá posteriormente organizar procesadores en serie."
 *
 * [process] genuinely iterates and calls each processor's
 * [DspProcessor.process] in order — that's the entire point of a chain —
 * but nothing in this phase ever calls [DspChain.process] itself (no
 * `DspScheduler` or anywhere else invokes it), and zero real
 * [DspProcessor] implementations exist to populate a chain with. The
 * infrastructure is real; nothing exercises it yet.
 */
class DspChain {
    private val lock = Any()
    private val processors = mutableListOf<DspProcessor>()

    fun append(processor: DspProcessor) = synchronized(lock) { processors.add(processor) }

    fun remove(id: String): Boolean = synchronized(lock) { processors.removeAll { it.id == id } }

    fun processors(): List<DspProcessor> = synchronized(lock) { processors.toList() }

    /** Calls [DspProcessor.prepare] on every processor in the chain, in order. */
    fun prepare(sampleRateHz: Int, maxFrameCount: Int) {
        processors().forEach { it.prepare(sampleRateHz, maxFrameCount) }
    }

    /** Calls [DspProcessor.process] on every processor in the chain, in order, on the same [frame]. */
    fun process(frame: DspFrame) {
        processors().forEach { it.process(frame) }
    }

    /** Calls [DspProcessor.reset] on every processor in the chain. */
    fun reset() {
        processors().forEach { it.reset() }
    }
}

/** The bus categories named explicitly in the spec — no more, no fewer. */
enum class DspBusType { INSERT, SEND, RETURN, MASTER }

/**
 * A DSP bus — "preparar la infraestructura para futuros buses DSP... no
 * implementar mezcla. Solo la arquitectura." A bus is identity + type +
 * the [DspChain] of processors it routes through; it does **not** combine
 * or sum any signal from multiple sources — that's mixing, explicitly out
 * of scope.
 */
data class DspBus(
    val id: String,
    val type: DspBusType,
    val chain: DspChain = DspChain(),
)
