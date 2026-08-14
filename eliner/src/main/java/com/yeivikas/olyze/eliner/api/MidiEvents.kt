package com.yeivikas.olyze.eliner.api

import com.yeivikas.olyze.eliner.events.EliNerEvent

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

/**
 * §22: a point-in-time snapshot, same shape/spirit as [AudioMetricsSnapshot]
 * — every field starts at zero, only ever incremented by real code.
 *
 * [inputQueueUtilizationPercent] is the one metric worth explaining:
 * [MidiEventQueue] is a bounded queue (§12 — see its own doc for why
 * bounded, not unlimited), and this is how a consumer can tell it's
 * approaching capacity before events actually start dropping.
 */
data class MidiMetricsSnapshot(
    val eventsReceived: Long,
    val eventsSent: Long,
    val eventsDropped: Long,
    val sysexBytesReceived: Long,
    val connectedDeviceCount: Int,
    val activeInputPortCount: Int,
    val activeOutputPortCount: Int,
    val inputQueueUtilizationPercent: Float,
)
