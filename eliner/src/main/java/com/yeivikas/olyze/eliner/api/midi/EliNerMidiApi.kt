package com.yeivikas.olyze.eliner.api.midi

/**
 * §25: the public contract the UI/app layer talks to — never Android MIDI
 * types, never the implementation class directly.
 */
interface EliNerMidiApi {
    /** Every currently known device (connected or recently disconnected — see [MidiDeviceState]). */
    val devices: kotlinx.coroutines.flow.StateFlow<List<MidiDeviceInfo>>

    /** Every input event, from every connected device/port, in arrival order. */
    val inputEvents: kotlinx.coroutines.flow.SharedFlow<MidiEvent>

    /** Device connect/disconnect notifications — see [MidiDeviceConnectedEvent]/[MidiDeviceDisconnectedEvent].
     *  A [kotlinx.coroutines.flow.Flow], not a [kotlinx.coroutines.flow.SharedFlow] — this is a filtered
     *  view over the shared [com.yeivikas.olyze.eliner.events.EventBus], same honest distinction
     *  [com.yeivikas.olyze.eliner.events.EventBus.subscribe] itself documents. */
    val deviceEvents: kotlinx.coroutines.flow.Flow<MidiDeviceEvent>

    val metrics: kotlinx.coroutines.flow.StateFlow<MidiMetricsSnapshot>

    /** Starts device discovery/hot-plug watching. Idempotent — see implementation for the exact lifecycle contract. */
    fun start()

    /** Stops discovery, closes every open port/device, releases resources. Idempotent. */
    fun stop()

    /**
     * Sends [event] out [portId]. Returns `false` if [portId] doesn't
     * exist, isn't an OUTPUT port, or the underlying send failed — never
     * throws for an ordinary send failure (§34: a badly-behaved or
     * disconnected device must not be able to take the engine down).
     */
    fun send(portId: String, event: MidiEvent): Boolean

    fun registerConsumer(consumer: MidiConsumer)
    fun unregisterConsumer(consumer: MidiConsumer)

    fun registerBinding(binding: MidiParameterBinding)
    fun unregisterBinding(id: String)
    fun getBindings(): List<MidiParameterBinding>

    /**
     * Terminal teardown — distinct from [stop]. [stop] is restartable
     * (`stop()` then `start()` again is safe, by design — see
     * [MidiFoundationModule]'s doc for why, in contrast with the known
     * `EliNerRuntime`/`ThreadManager` A-1 lifecycle bug from the
     * hardening phase). [shutdown] is NOT restartable: it releases the
     * platform backend's own dedicated callback thread, which cannot be
     * restarted once stopped. Call this only when the module itself will
     * never be used again (e.g. from `ViewModel.onCleared()`), after
     * [stop] — never call [start] again after [shutdown].
     */
    fun shutdown()
}

/**
 * §13: what [MidiRouter] dispatches to. Deliberately synchronous and
 * minimal — a real consumer (future Synth/Sampler/Automation) decides for
 * itself whether handling an event needs to hop to another thread;
 * [MidiRouter] just guarantees delivery in arrival order on
 * [com.yeivikas.olyze.eliner.services.ExecutionLane.DSP].
 */
fun interface MidiConsumer {
    fun onMidiEvent(event: MidiEvent)
}
