package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.MidiConsumer
import com.yeivikas.olyze.eliner.api.MidiEvent
import com.yeivikas.olyze.eliner.api.MidiEventType
import com.yeivikas.olyze.eliner.api.MidiParameterBinding
import com.yeivikas.olyze.eliner.services.ExecutionLane
import com.yeivikas.olyze.eliner.services.TaskExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * §13: drains [inputQueue] and dispatches every [MidiEvent], in arrival
 * order, to every registered [MidiConsumer] — the router "no debe conocer
 * implementaciones concretas" (no Synth/Sampler import here, ever; they
 * register themselves via [registerConsumer] once they exist).
 *
 * Runs its draining loop on [ExecutionLane.DSP] via the shared
 * [TaskExecutor] (reusing [com.yeivikas.olyze.eliner.services.
 * ThreadManager] — §27, no dedicated `MidiThreadManager`). CLOCK/START/
 * STOP/CONTINUE events are additionally forwarded to [clock] before
 * consumers see them, so [clock]'s tick/transport state is always
 * up to date by the time a consumer's callback runs (§15/§16).
 *
 * CONTROL_CHANGE events are matched against every registered
 * [MidiParameterBinding] (§20/§21); a match publishes the evaluated
 * `(parameterId, value)` pair on [bindingUpdates] — see that property's
 * doc for why the router stops there instead of applying the value to a
 * concrete target itself.
 */
class MidiRouter(
    private val inputQueue: MidiEventQueue,
    private val metrics: MidiMetrics,
    private val clock: MidiClockEngine,
    private val taskExecutor: TaskExecutor,
) {
    private val consumers = mutableListOf<MidiConsumer>()
    private val consumersLock = Any()

    private val bindings = mutableMapOf<String, MidiParameterBinding>()
    private val bindingsLock = Any()

    private val _bindingUpdates = MutableSharedFlow<Pair<String, Float>>(extraBufferCapacity = 64)

    private val _allEvents = MutableSharedFlow<MidiEvent>(extraBufferCapacity = 256)
    /** Every event, of every type, after clock/binding processing — the
     *  real [SharedFlow] backing [com.yeivikas.olyze.eliner.api.
     *  EliNerMidiApi.inputEvents]. */
    val allEvents: SharedFlow<MidiEvent> = _allEvents.asSharedFlow()

    /**
     * `(parameterId, evaluatedValue)` for every CC that matched a
     * registered [MidiParameterBinding]. Deliberately NOT applied to any
     * concrete DSP/Synth parameter here — see [MidiParameterBinding]'s
     * doc for why hardwiring a target (e.g. `DspParameterManager`, which
     * ADR 0010 documents as disconnected from the real native audio path)
     * would be worse than not wiring one. Whatever eventually owns real
     * parameters collects this and applies it.
     */
    val bindingUpdates: SharedFlow<Pair<String, Float>> = _bindingUpdates.asSharedFlow()

    private var loopJob: Job? = null

    /** §35: idempotent. */
    fun start() {
        if (loopJob != null) return
        loopJob = taskExecutor.scopeFor(ExecutionLane.DSP).launch {
            val batch = mutableListOf<MidiEvent>()
            while (isActive) {
                val first = inputQueue.takeBlocking(POLL_TIMEOUT_MILLIS) ?: continue
                batch.clear()
                batch.add(first)
                inputQueue.drainTo(batch)
                for (event in batch) dispatch(event)
            }
        }
    }

    /** §35: idempotent. Does not drain remaining queued events — see [start] for why re-`start`ing after `stop` and back is safe regardless (nothing is lost, just deferred). */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun registerConsumer(consumer: MidiConsumer) {
        synchronized(consumersLock) { consumers.add(consumer) }
    }

    fun unregisterConsumer(consumer: MidiConsumer) {
        synchronized(consumersLock) { consumers.remove(consumer) }
    }

    fun registerBinding(binding: MidiParameterBinding) = synchronized(bindingsLock) { bindings[binding.id] = binding }
    fun unregisterBinding(id: String) {
        synchronized(bindingsLock) { bindings.remove(id) }
    }
    fun getBindings(): List<MidiParameterBinding> = synchronized(bindingsLock) { bindings.values.toList() }

    private fun dispatch(event: MidiEvent) {
        metrics.recordReceived(sysexBytes = event.sysex?.size ?: 0)

        when (event.type) {
            MidiEventType.CLOCK -> clock.onClockTick()
            MidiEventType.START -> clock.onStart()
            MidiEventType.STOP -> clock.onStop()
            MidiEventType.CONTINUE -> clock.onContinue()
            MidiEventType.CONTROL_CHANGE -> matchBindings(event)
            else -> Unit
        }

        val snapshot = synchronized(consumersLock) { consumers.toList() }
        for (consumer in snapshot) consumer.onMidiEvent(event)
        _allEvents.tryEmit(event)
    }

    private fun matchBindings(event: MidiEvent) {
        val channel = event.channel ?: return
        val matches = synchronized(bindingsLock) {
            bindings.values.filter { it.channel == channel && it.controller == event.data1 }
        }
        for (binding in matches) {
            _bindingUpdates.tryEmit(binding.targetParameterId to binding.evaluate(event.data2))
        }
    }

    companion object {
        private const val POLL_TIMEOUT_MILLIS = 250L
    }
}
