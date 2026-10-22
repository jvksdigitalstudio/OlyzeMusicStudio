package com.yeivikas.olyze.eliner.core

/**
 * Generic node-and-connection graph: register nodes by id, connect them
 * directionally, query the result. No processing, no traversal semantics
 * beyond bookkeeping — whatever consumes the graph decides what a
 * connection *means*.
 *
 * Extracted in Fase 5 (DSP Foundation) to avoid writing a *second*
 * hand-rolled copy of this exact mechanic —
 * `com.yeivikas.olyze.eliner.audiofoundation.AudioRoutingGraph` (Fase 3)
 * already implements addNode/removeNode/connect/nodes/connections
 * independently, and DSP Foundation's own `DspGraph` needed the same
 * shape with different node content.
 *
 * **Deliberately not retrofitted onto `AudioRoutingGraph`.** Same
 * reasoning as `StateMachine<S>` (Fase 3, see its own doc comment and ADR
 * 0007): `AudioRoutingGraph` is already-shipped, already-verified code
 * from an earlier phase, and this project's own rule in every phase since
 * has been "no romper arquitectura anterior." Touching working graph
 * bookkeeping for stylistic consolidation, with no compiler available to
 * re-verify the change, is a worse trade than leaving two small,
 * independent, working implementations alone. New graph-shaped code from
 * this phase onward uses this class instead of copying the pattern again.
 *
 * Thread-safe via `synchronized`, same rationale as every other
 * non-real-time registry in this project: graph construction isn't a
 * real-time-audio-thread operation.
 */
class ConnectionGraph<N : Any> {
    private val lock = Any()
    private val nodes = mutableMapOf<String, N>()
    private val connections = mutableListOf<Pair<String, String>>()

    /** Registers [node] under [id]. Throws if [id] is already in the graph. */
    fun addNode(id: String, node: N) {
        synchronized(lock) {
            require(id !in nodes) { "Node '$id' is already in the graph." }
            nodes[id] = node
        }
    }

    /** Removes the node at [id] and every connection touching it. Returns whether it was present. */
    fun removeNode(id: String): Boolean = synchronized(lock) {
        if (id !in nodes) return@synchronized false
        nodes.remove(id)
        connections.removeAll { it.first == id || it.second == id }
        true
    }

    /**
     * Connects [fromId] to [toId]. Rejects self-loops and connections
     * referencing a node not in the graph. Does not detect cycles across
     * the whole graph — whoever consumes the graph (e.g. a scheduler)
     * decides whether a cycle is valid for its purposes.
     */
    fun connect(fromId: String, toId: String): Boolean = synchronized(lock) {
        if (fromId == toId) return@synchronized false
        if (fromId !in nodes || toId !in nodes) return@synchronized false
        connections.add(fromId to toId)
        true
    }

    fun node(id: String): N? = synchronized(lock) { nodes[id] }
    fun nodes(): Map<String, N> = synchronized(lock) { nodes.toMap() }
    fun connections(): List<Pair<String, String>> = synchronized(lock) { connections.toList() }
}
