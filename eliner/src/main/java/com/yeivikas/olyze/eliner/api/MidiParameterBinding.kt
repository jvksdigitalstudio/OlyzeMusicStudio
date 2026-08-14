package com.yeivikas.olyze.eliner.api

/**
 * §21: the binding contract. Deliberately does NOT reference
 * `eliner.dspfoundation.DspParameter` directly — [targetParameterId] is a
 * plain `String` matching [com.yeivikas.olyze.eliner.dspfoundation.
 * DspParameter.id] BY CONVENTION, not by compile-time dependency. `eliner.
 * api` sits below `eliner.dspfoundation` in the dependency direction
 * (§29); a binding contract in the API layer must not import a Foundation
 * layer type. This is the same pattern `DspParameterManager`'s own doc
 * comment anticipated: "MIDI Learn would map a CC number to a parameter
 * id" — this class is that mapping, nothing more. Applying it to a real
 * target is [MidiParameterBindingRegistry]'s job, and even that stays
 * generic (see its doc) rather than reaching into `DspParameterManager`
 * directly, because `DspParameterManager` is part of the Kotlin DSP stack
 * that ADR 0010 documents as disconnected from the real (native) audio
 * path — hardwiring a binding target there would silently do nothing
 * audible, which is worse than not wiring one at all.
 *
 * [curve] is intentionally just an enum of shapes, not an arbitrary
 * function — a real curve editor is UI work (§21 says "no implementar UI
 * todavía"), and an arbitrary `(Float) -> Float` isn't representable in a
 * saved binding (no serializable closures) without more infrastructure
 * than this phase needs.
 */
enum class MidiBindingCurve { LINEAR, EXPONENTIAL, LOGARITHMIC }

data class MidiParameterBinding(
    val id: String,
    val sourceDeviceId: String,
    val channel: Int,
    /** CC number (0-127) — MIDI Learn today only targets Control Change,
     *  the overwhelmingly common case for knobs/faders. Extending this to
     *  other message types (e.g. aftertouch-as-modulation) is real future
     *  work, not built here. */
    val controller: Int,
    val targetParameterId: String,
    val outputMin: Float,
    val outputMax: Float,
    val invert: Boolean = false,
    val curve: MidiBindingCurve = MidiBindingCurve.LINEAR,
) {
    init {
        require(channel in 0..15) { "channel must be in 0..15, got $channel." }
        require(controller in 0..127) { "controller must be in 0..127, got $controller." }
        require(outputMin <= outputMax) {
            "outputMin ($outputMin) must be <= outputMax ($outputMax) for binding '$id'."
        }
    }

    /**
     * Maps a raw 0-127 CC [value] through [curve]/[invert] into
     * [outputMin]..[outputMax]. Pure function, no side effects — applying
     * the result to an actual target is the caller's job (see class doc).
     */
    fun evaluate(value: Int): Float {
        require(value in 0..127) { "value must be in 0..127, got $value." }
        var normalized = value / 127f
        if (invert) normalized = 1f - normalized
        val shaped = when (curve) {
            MidiBindingCurve.LINEAR -> normalized
            // Both shaped so f(0)=0, f(1)=1, matching a plain audio-style
            // taper — real curve tuning (exponent choice) is a UI/preset
            // concern this phase doesn't own.
            MidiBindingCurve.EXPONENTIAL -> normalized * normalized
            MidiBindingCurve.LOGARITHMIC -> kotlin.math.sqrt(normalized)
        }
        return outputMin + shaped * (outputMax - outputMin)
    }
}

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
