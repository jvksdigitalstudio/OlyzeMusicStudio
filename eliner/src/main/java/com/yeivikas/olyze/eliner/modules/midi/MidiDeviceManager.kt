package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.MidiDeviceEvent
import com.yeivikas.olyze.eliner.api.MidiDeviceInfo
import com.yeivikas.olyze.eliner.api.MidiDeviceState
import com.yeivikas.olyze.eliner.events.EventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * §8: detects and administers MIDI devices, behind [MidiPlatformBackend]
 * — nothing here or above ever touches `android.media.midi.*` directly.
 *
 * Every input port of every connected device is opened automatically
 * (§10: input should just work, not require an explicit per-port "start
 * listening" call from a consumer) and its parsed events go straight into
 * [inputQueue] — this class's only job re: events is getting them off the
 * platform thread and into the queue; [MidiRouter] owns everything after
 * that.
 */
class MidiDeviceManager(
    private val backend: MidiPlatformBackend,
    private val inputQueue: MidiEventQueue,
    private val eventBus: EventBus,
) {
    private val _devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    val devices: StateFlow<List<MidiDeviceInfo>> = _devices.asStateFlow()

    private var watching = false

    /** §35: idempotent — calling twice while already watching is a no-op. */
    fun start() {
        if (watching) return
        watching = true
        _devices.value = backend.listDevices()
        _devices.value.forEach(::connectDevice)
        backend.startWatching(
            onDeviceConnected = { device ->
                _devices.value = _devices.value.filterNot { it.id == device.id } + device
                connectDevice(device)
                publish(MidiDeviceEvent.Connected(device))
            },
            onDeviceDisconnected = { device ->
                backend.closeDevice(device.id)
                val disconnected = device.copy(state = MidiDeviceState.DISCONNECTED)
                _devices.value = _devices.value.map { if (it.id == device.id) disconnected else it }
                publish(MidiDeviceEvent.Disconnected(disconnected, reason = "Device removed"))
            },
        )
    }

    /** §35/§36: idempotent. Closes every open device — §34: a device that
     *  disappears mid-processing must not leave a dangling handle. */
    fun stop() {
        if (!watching) return
        watching = false
        backend.stopWatching()
        _devices.value.forEach { backend.closeDevice(it.id) }
        _devices.value = emptyList()
    }

    fun send(portId: String, event: com.yeivikas.olyze.eliner.api.MidiEvent): Boolean = backend.send(portId, event)

    private fun connectDevice(device: MidiDeviceInfo) {
        if (device.inputPorts.isEmpty()) return
        backend.openInputPorts(device) { event -> inputQueue.offer(event) }
    }

    private fun publish(event: MidiDeviceEvent) {
        eventBus.publish(event) // Same object also returned via `devices`/consumed by MidiFoundationModule for `deviceEvents` — see MidiDeviceEvent's doc.
    }
}
