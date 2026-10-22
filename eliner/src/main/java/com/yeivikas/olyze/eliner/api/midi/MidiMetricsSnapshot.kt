package com.yeivikas.olyze.eliner.api.midi

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
