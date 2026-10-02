package com.yeivikas.olyze.eliner.audiofoundation

/** What kind of thing a routing node represents — matches the spec's explicit list. */
enum class AudioRoutingNodeType { INSTRUMENT, EFFECT, MIXER_BUS, MASTER, PLUGIN }

/** A single node in the future signal-flow graph. Identity only — no audio buffer, no processing callback. */
data class AudioRoutingNode(val id: String, val type: AudioRoutingNodeType)

/** A directed connection between two nodes' [AudioRoutingNode.id]s. */
data class AudioRoutingConnection(val fromNodeId: String, val toNodeId: String)

/**
 * The architecture for future audio routing (instruments → effects →
 * mixer buses → master, plugins attached anywhere) — a graph of
 * identities and connections, with structural validation. **No signal
 * processing happens here**: this class never touches an audio buffer,
 * only node/connection bookkeeping. "No implementar procesamiento. Solo
 * la arquitectura."
 */
class AudioRoutingGraph {
    private val lock = Any()
    private val nodes = mutableMapOf<String, AudioRoutingNode>()
    private val connections = mutableListOf<AudioRoutingConnection>()

    fun addNode(node: AudioRoutingNode) = synchronized(lock) {
        require(node.id !in nodes) { "Node '${node.id}' is already in the graph." }
        nodes[node.id] = node
    }

    fun removeNode(id: String): Boolean = synchronized(lock) {
        if (id !in nodes) return@synchronized false
        nodes.remove(id)
        connections.removeAll { it.fromNodeId == id || it.toNodeId == id }
        true
    }

    /**
     * Connects [fromNodeId] to [toNodeId]. Rejects self-loops and
     * connections referencing a node that isn't in the graph — the only
     * validation this phase performs; cycle detection across the whole
     * graph (e.g. A→B→A) is left for whichever future engine actually
     * processes this graph, since only it will know which cycles are
     * legitimate (e.g. feedback effects) versus not.
     */
    fun connect(fromNodeId: String, toNodeId: String): Boolean = synchronized(lock) {
        if (fromNodeId == toNodeId) return@synchronized false
        if (fromNodeId !in nodes || toNodeId !in nodes) return@synchronized false
        connections.add(AudioRoutingConnection(fromNodeId, toNodeId))
        true
    }

    fun nodes(): List<AudioRoutingNode> = synchronized(lock) { nodes.values.toList() }
    fun connections(): List<AudioRoutingConnection> = synchronized(lock) { connections.toList() }
}
