package com.yeivikas.olyze.eliner.dspfoundation

/**
 * Computes a valid execution order for a [DspGraph]'s nodes — "organizará
 * el orden de ejecución de los nodos DSP... no ejecutar procesamiento
 * todavía. Solo preparar la arquitectura."
 *
 * [computeExecutionOrder] is a real, working topological sort (Kahn's
 * algorithm) — genuine infrastructure, not a stub — but it only
 * *computes an order*. It never calls [DspProcessor.process] on anything;
 * running the graph in the order this returns is future work.
 */
class DspScheduler {

    /**
     * Returns [graph]'s nodes ordered so that every node appears after
     * every node that connects into it, or `null` if [graph] contains a
     * cycle (which makes a single valid linear order impossible).
     */
    fun computeExecutionOrder(graph: DspGraph): List<DspNode>? {
        val nodes = graph.nodes()
        val nodesById = nodes.associateBy { it.id }
        val connections = graph.connections()

        val inDegree = nodes.associate { it.id to 0 }.toMutableMap()
        val adjacency = nodes.associate { it.id to mutableListOf<String>() }
        for ((fromId, toId) in connections) {
            adjacency[fromId]?.add(toId)
            inDegree[toId] = (inDegree[toId] ?: 0) + 1
        }

        val ready = ArrayDeque(inDegree.filterValues { it == 0 }.keys)
        val orderedIds = mutableListOf<String>()

        while (ready.isNotEmpty()) {
            val currentId = ready.removeFirst()
            orderedIds.add(currentId)
            for (neighborId in adjacency[currentId].orEmpty()) {
                val remaining = (inDegree[neighborId] ?: 1) - 1
                inDegree[neighborId] = remaining
                if (remaining == 0) ready.add(neighborId)
            }
        }

        if (orderedIds.size != nodes.size) return null // a cycle left some nodes un-orderable

        return orderedIds.mapNotNull { nodesById[it] }
    }
}
