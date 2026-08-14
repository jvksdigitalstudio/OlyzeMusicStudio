package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.MidiDeviceState
import com.yeivikas.olyze.eliner.api.MidiMetricsSnapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * §22: real counters, incremented only by real code — no fabricated
 * values. Atomics because [recordReceived]/[recordSysexBytes] are called
 * from [MidiRouter]'s dedicated DSP-lane thread while [snapshot] can be
 * read from any thread (e.g. a future diagnostics UI collecting a
 * `StateFlow` on the main thread) — a plain `var` would be a data race.
 */
class MidiMetrics(private val inputQueue: MidiEventQueue) {
    private val eventsReceived = AtomicLong(0)
    private val eventsSent = AtomicLong(0)
    private val sysexBytesReceived = AtomicLong(0)

    fun recordReceived(sysexBytes: Int = 0) {
        eventsReceived.incrementAndGet()
        if (sysexBytes > 0) sysexBytesReceived.addAndGet(sysexBytes.toLong())
    }

    fun recordSent() {
        eventsSent.incrementAndGet()
    }

    fun snapshot(devices: List<com.yeivikas.olyze.eliner.api.MidiDeviceInfo>): MidiMetricsSnapshot {
        val connected = devices.filter { it.state == MidiDeviceState.CONNECTED }
        return MidiMetricsSnapshot(
            eventsReceived = eventsReceived.get(),
            eventsSent = eventsSent.get(),
            eventsDropped = inputQueue.droppedCount,
            sysexBytesReceived = sysexBytesReceived.get(),
            connectedDeviceCount = connected.size,
            activeInputPortCount = connected.sumOf { it.inputPorts.size },
            activeOutputPortCount = connected.sumOf { it.outputPorts.size },
            inputQueueUtilizationPercent = inputQueue.utilizationPercent(),
        )
    }
}
