package com.yeivikas.olyze.eliner.dspfoundation

import com.yeivikas.olyze.eliner.core.ConnectionGraph

/**
 * A node in the DSP processing graph. [processor] is nullable — a node
 * doesn't have to wrap a [DspProcessor] (e.g. a future pass-through or
 * summing point might not process anything itself).
 */
data class DspNode(val id: String, val processor: DspProcessor?)

/**
 * The DSP processing graph — "permitir futuras conexiones entre nodos...
 * no implementar procesamiento real. Solo la arquitectura." Built on
 * `com.yeivikas.olyze.eliner.core.ConnectionGraph<N>` (Fase 5's own
 * extraction, see that class's doc comment for why) instead of
 * hand-rolling graph bookkeeping a second time.
 *
 * This class never calls [DspProcessor.process] on anything — connecting
 * two nodes only records that a future scheduler/executor should treat
 * one as feeding the other; nothing here executes that relationship.
 */
class DspGraph {
    private val graph = ConnectionGraph<DspNode>()

    fun addNode(node: DspNode) = graph.addNode(node.id, node)

    fun removeNode(id: String): Boolean = graph.removeNode(id)

    /** Connects [fromId] to [toId] — see [ConnectionGraph.connect] for validation rules. */
    fun connect(fromId: String, toId: String): Boolean = graph.connect(fromId, toId)

    fun node(id: String): DspNode? = graph.node(id)

    fun nodes(): List<DspNode> = graph.nodes().values.toList()

    fun connections(): List<Pair<String, String>> = graph.connections()
}
