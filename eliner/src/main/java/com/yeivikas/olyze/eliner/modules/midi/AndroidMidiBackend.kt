package com.yeivikas.olyze.eliner.modules.midi

import android.app.Application
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo as AndroidMidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import com.yeivikas.olyze.eliner.api.MidiDeviceInfo
import com.yeivikas.olyze.eliner.api.MidiDeviceState
import com.yeivikas.olyze.eliner.api.MidiEvent
import com.yeivikas.olyze.eliner.api.MidiPortDirection
import com.yeivikas.olyze.eliner.api.MidiPortInfo
import com.yeivikas.olyze.eliner.api.MidiTransport
import com.yeivikas.olyze.eliner.services.TimeProvider

/**
 * §26: the one file in this project allowed to import `android.media.
 * midi.*` for the MIDI Foundation (verified — see [MidiPlatformBackend]'s
 * doc). Everything above [MidiPlatformBackend] only ever sees
 * [com.yeivikas.olyze.eliner.api] types.
 *
 * [context] is [Application], not [Context] — same hardening-pass
 * reasoning already applied to `DeviceCapabilityManager`/
 * `AudioDeviceManager`: this class retains it for its own lifetime.
 * [timeProvider] is constructor-injected, matching this project's
 * established DI-by-constructor style everywhere else (`AudioClock`,
 * `RuntimeContext`, etc.) — used to stamp [MidiEvent.timestampNanos] at
 * the moment each raw MIDI callback arrives (see [openInputPorts]).
 *
 * Runs its own dedicated [HandlerThread] for `MidiManager` callbacks
 * (device discovery, device-open results, port receive callbacks) rather
 * than reusing [com.yeivikas.olyze.eliner.services.ThreadManager]'s
 * `ExecutionLane.IO`/`BACKGROUND` — §27 asks to "determinar cuidadosamente
 * en qué contexto se ejecuta" each of these, and the reason NOT to reuse
 * ThreadManager here specifically is that Android's MIDI APIs require a
 * [Handler] (a `Looper`-based callback target), not a `CoroutineScope`/
 * `Executor` — there is no clean way to hand `MidiManager` one of
 * ThreadManager's coroutine dispatchers directly. This is the one place
 * in the MIDI Foundation with its own thread, and it exists because the
 * platform API leaves no other option, not because ThreadManager was
 * insufficient in general.
 */
class AndroidMidiBackend(
    private val context: Application,
    private val timeProvider: TimeProvider,
) : MidiPlatformBackend {
    private val midiManager: MidiManager? =
        context.getSystemService(android.content.Context.MIDI_SERVICE) as? MidiManager

    private val callbackThread = HandlerThread("eliner-midi-callback").apply { start() }
    private val handler = Handler(callbackThread.looper)

    private var deviceCallback: MidiManager.DeviceCallback? = null

    // deviceId (String, = AndroidMidiDeviceInfo.id.toString()) -> open handles.
    private val openDevices = mutableMapOf<String, MidiDevice>()
    private val openReceivers = mutableMapOf<String, List<MidiReceiverConnection>>()
    private val openOutputPorts = mutableMapOf<String, MidiInputPort>() // keyed by our portId — see send().

    private class MidiReceiverConnection(val receiver: MidiReceiver, val androidPort: android.media.midi.MidiOutputPort)

    override fun isAvailable(): Boolean = midiManager != null

    override fun listDevices(): List<MidiDeviceInfo> {
        val manager = midiManager ?: return emptyList()
        return manager.devices.map { it.toEliNer() }
    }

    override fun startWatching(
        onDeviceConnected: (MidiDeviceInfo) -> Unit,
        onDeviceDisconnected: (MidiDeviceInfo) -> Unit,
    ) {
        val manager = midiManager ?: return
        val callback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: AndroidMidiDeviceInfo) {
                onDeviceConnected(device.toEliNer())
            }

            override fun onDeviceRemoved(device: AndroidMidiDeviceInfo) {
                onDeviceDisconnected(device.toEliNer())
            }
        }
        manager.registerDeviceCallback(callback, handler)
        deviceCallback = callback
    }

    override fun stopWatching() {
        deviceCallback?.let { midiManager?.unregisterDeviceCallback(it) }
        deviceCallback = null
    }

    override fun openInputPorts(device: MidiDeviceInfo, onEvent: (MidiEvent) -> Unit): Boolean {
        val manager = midiManager ?: return false
        val androidInfo = manager.devices.firstOrNull { it.id.toString() == device.id } ?: return false

        // `open` is asynchronous on Android; the actual port-opening work
        // below runs on `handler`'s thread once the device handle is ready.
        // §10: the eventual per-message callback stays lightweight — parsing
        // + a single lambda invocation, nothing blocking, no I/O, no
        // allocation beyond what MidiStreamParser/MidiEvent already do.
        manager.openDevice(
            androidInfo,
            { opened ->
                if (opened == null) return@openDevice
                openDevices[device.id] = opened
                val connections = device.inputPorts.mapNotNull { portInfo ->
                    val androidOutputPort = opened.openOutputPort(portInfo.portIndex) ?: return@mapNotNull null
                    val parser = MidiStreamParser(portInfo.id)
                    val receiver = object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            // Binder callback thread — must stay lightweight (§10).
                            // `timestamp` here is EliNer's own TimeProvider read
                            // at the moment of delivery, not Android's raw
                            // `timestamp` param — see MidiEvent's doc for why.
                            parser.feed(msg, offset, count, timeProvider.nowNanos(), onEvent)
                        }
                    }
                    androidOutputPort.connect(receiver)
                    MidiReceiverConnection(receiver, androidOutputPort)
                }
                openReceivers[device.id] = connections
            },
            handler,
        )
        return true
    }

    override fun closeDevice(deviceId: String) {
        openReceivers.remove(deviceId)?.forEach { it.androidPort.close() }
        openDevices.remove(deviceId)?.close()
        openOutputPorts.keys.filter { it.startsWith("$deviceId:") }.forEach { portId ->
            openOutputPorts.remove(portId)?.close()
        }
    }

    override fun send(portId: String, event: MidiEvent): Boolean {
        val port = openOutputPorts[portId] ?: run {
            val deviceId = portId.substringBefore(':')
            val device = openDevices[deviceId] ?: return false
            val portIndex = portId.substringAfterLast(':').toIntOrNull() ?: return false
            val opened = device.openInputPort(portIndex) ?: return false
            openOutputPorts[portId] = opened
            opened
        }
        val bytes = event.toRawBytes() ?: return false
        return try {
            port.send(bytes, 0, bytes.size)
            true
        } catch (e: java.io.IOException) {
            // §34: a disconnected/misbehaving device must not crash the
            // engine — report failure, don't propagate the exception.
            false
        }
    }

    override fun shutdown() {
        stopWatching()
        openDevices.keys.toList().forEach { closeDevice(it) }
        callbackThread.quitSafely()
    }

    private fun AndroidMidiDeviceInfo.toEliNer(): MidiDeviceInfo {
        val idStr = id.toString()
        val inputPorts = mutableListOf<MidiPortInfo>()
        val outputPorts = mutableListOf<MidiPortInfo>()
        for (port in ports) {
            val portInfo = MidiPortInfo(
                id = "$idStr:${port.type}:${port.portNumber}",
                deviceId = idStr,
                portIndex = port.portNumber,
                direction = if (port.type == AndroidMidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
                    MidiPortDirection.INPUT // Android's OUTPUT port = data flows to us = our INPUT.
                } else {
                    MidiPortDirection.OUTPUT
                },
                name = port.name ?: "Port ${port.portNumber}",
            )
            if (portInfo.direction == MidiPortDirection.INPUT) inputPorts.add(portInfo) else outputPorts.add(portInfo)
        }
        return MidiDeviceInfo(
            id = idStr,
            name = properties.getString(AndroidMidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device $idStr",
            manufacturer = properties.getString(AndroidMidiDeviceInfo.PROPERTY_MANUFACTURER),
            transport = when (type) {
                AndroidMidiDeviceInfo.TYPE_USB -> MidiTransport.USB
                AndroidMidiDeviceInfo.TYPE_BLUETOOTH -> MidiTransport.BLUETOOTH
                AndroidMidiDeviceInfo.TYPE_VIRTUAL -> MidiTransport.VIRTUAL
                else -> MidiTransport.UNKNOWN
            },
            state = MidiDeviceState.CONNECTED,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
        )
    }

    /**
     * Converts [MidiEvent] back to raw MIDI bytes for [send]. Returns
     * `null` for event types this phase doesn't support sending (SysEx
     * output, MPE-specific messages) — §11 explicitly scopes output to
     * Note On/Off, CC, Pitch Bend, Program Change, Clock, Transport;
     * everything in that list is handled below.
     */
    private fun MidiEvent.toRawBytes(): ByteArray? {
        val ch = channel ?: 0
        return when (type) {
            com.yeivikas.olyze.eliner.api.MidiEventType.NOTE_ON ->
                byteArrayOf((0x90 or ch).toByte(), data1.toByte(), data2.toByte())
            com.yeivikas.olyze.eliner.api.MidiEventType.NOTE_OFF ->
                byteArrayOf((0x80 or ch).toByte(), data1.toByte(), data2.toByte())
            com.yeivikas.olyze.eliner.api.MidiEventType.CONTROL_CHANGE ->
                byteArrayOf((0xB0 or ch).toByte(), data1.toByte(), data2.toByte())
            com.yeivikas.olyze.eliner.api.MidiEventType.PROGRAM_CHANGE ->
                byteArrayOf((0xC0 or ch).toByte(), data1.toByte())
            com.yeivikas.olyze.eliner.api.MidiEventType.PITCH_BEND ->
                byteArrayOf((0xE0 or ch).toByte(), (pitchBendValue and 0x7F).toByte(), ((pitchBendValue shr 7) and 0x7F).toByte())
            com.yeivikas.olyze.eliner.api.MidiEventType.CLOCK -> byteArrayOf(0xF8.toByte())
            com.yeivikas.olyze.eliner.api.MidiEventType.START -> byteArrayOf(0xFA.toByte())
            com.yeivikas.olyze.eliner.api.MidiEventType.STOP -> byteArrayOf(0xFC.toByte())
            com.yeivikas.olyze.eliner.api.MidiEventType.CONTINUE -> byteArrayOf(0xFB.toByte())
            else -> null
        }
    }
}
