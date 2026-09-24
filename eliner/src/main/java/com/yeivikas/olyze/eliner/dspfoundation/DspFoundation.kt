package com.yeivikas.olyze.eliner.dspfoundation

import com.yeivikas.olyze.eliner.api.DspApi
import com.yeivikas.olyze.eliner.api.DspMetricsSnapshot
import com.yeivikas.olyze.eliner.api.DspState
import com.yeivikas.olyze.eliner.api.RuntimeContext
import com.yeivikas.olyze.eliner.core.StateMachine
import com.yeivikas.olyze.eliner.events.EliNerEvent
import com.yeivikas.olyze.eliner.events.EventBus
import kotlinx.coroutines.flow.StateFlow

/** Published on [EventBus] whenever DSP Foundation's state changes. */
data class DspStateChangedEvent(val previous: DspState, val current: DspState) : EliNerEvent

/**
 * The real implementation of [DspApi] — owns [DspState] (via
 * [StateMachine], Fase 3's shared utility) and wraps [context], the
 * aggregator holding every other DSP Foundation piece.
 *
 * Mirrors `com.yeivikas.olyze.eliner.modules.audio.AudioEngine` (Fase 4)
 * closely on purpose: same lifecycle shape, same synchronous
 * event-publishing technique, same restraint (registers nothing, executes
 * nothing).
 */
class DspFoundation(
    val context: DspContext,
    runtimeContext: RuntimeContext,
) : DspApi {

    private val eventBus: EventBus = runtimeContext.events
    private val machine = StateMachine(DspState.UNINITIALIZED, DspState::isValidTransition)

    override val state: StateFlow<DspState> = machine.state

    override fun initialize(): Boolean {
        if (!moveTo(DspState.INITIALIZING)) return false
        return moveTo(DspState.READY)
    }

    override fun shutdown(): Boolean {
        if (state.value != DspState.STOPPED) {
            if (!moveTo(DspState.STOPPING)) return false
        }
        context.bufferPool.clear()
        return moveTo(DspState.STOPPED)
    }

    override fun metricsSnapshot(): DspMetricsSnapshot = context.metrics.snapshot(
        nodeCount = context.graph.nodes().size,
        pooledBufferCount = context.bufferPool.pooledCount(),
    )

    private fun moveTo(target: DspState): Boolean {
        val previous = machine.state.value
        val succeeded = machine.transitionTo(target)
        if (succeeded) {
            eventBus.publish(DspStateChangedEvent(previous, target))
        }
        return succeeded
    }
}
