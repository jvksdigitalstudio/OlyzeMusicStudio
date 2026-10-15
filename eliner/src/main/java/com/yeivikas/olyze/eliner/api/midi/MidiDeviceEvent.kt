package com.yeivikas.olyze.eliner.api.midi

import com.yeivikas.olyze.eliner.api.event.EliNerEvent

/**
 * §8/§36: device connect/disconnect notifications, published on
 * [EliNerMidiApi.deviceEvents] AND, as the same object, on the shared
 * [com.yeivikas.olyze.eliner.events.EventBus] (it implements
 * [EliNerEvent]) — so any future module can observe MIDI connectivity
 * without depending on [EliNerMidiApi] directly, per the architecture
 * rule (§30: MIDI produces events other systems consume via contracts,
 * not direct calls). One type, two streams — not two separate event
 * classes for the same fact.
 *
 * Sealed, not a single class with a nullable "connected: Boolean" flag —
 * the two cases carry genuinely different information ([reason] only
 * makes sense for a disconnect).
 */
sealed interface MidiDeviceEvent : EliNerEvent {
    val device: MidiDeviceInfo

    data class Connected(override val device: MidiDeviceInfo) : MidiDeviceEvent

    data class Disconnected(
        override val device: MidiDeviceInfo,
        val reason: String,
    ) : MidiDeviceEvent
}
