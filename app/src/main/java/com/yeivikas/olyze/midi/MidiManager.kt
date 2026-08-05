package com.yeivikas.olyze.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MidiOutputDevice(
    val info: MidiDeviceInfo,
    val name: String,
    val id: Int
)

@RequiresApi(Build.VERSION_CODES.M)
class OlyzeMidiManager(private val context: Context) {

    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private var openDevice: MidiDevice? = null
    private var inputPort: MidiInputPort? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _statusText = MutableStateFlow("Sin conexión MIDI")
    val statusText: StateFlow<String> = _statusText

    private val _availableDevices = MutableStateFlow<List<MidiOutputDevice>>(emptyList())
    val availableDevices: StateFlow<List<MidiOutputDevice>> = _availableDevices

    private val handler = Handler(Looper.getMainLooper())

    fun initialize() {
        if (midiManager == null) {
            _statusText.value = "MIDI no soportado"
            return
        }
        refreshDevices()
        midiManager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) { refreshDevices() }
            override fun onDeviceRemoved(device: MidiDeviceInfo) { refreshDevices() }
        }, handler)
    }

    fun refreshDevices() {
        val manager = midiManager ?: return
        val devices = manager.devices.mapIndexedNotNull { idx, info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Device $idx"
            if (info.outputPortCount > 0 || info.inputPortCount > 0) {
                MidiOutputDevice(info, name, idx)
            } else null
        }
        _availableDevices.value = devices
        if (devices.isNotEmpty() && openDevice == null) {
            connectToDevice(devices.first())
        }
    }

    fun connectToDevice(device: MidiOutputDevice) {
        openDevice?.close()
        openDevice = null
        inputPort = null
        _connected.value = false

        midiManager?.openDevice(device.info, { midi ->
            if (midi != null) {
                openDevice = midi
                inputPort = midi.openInputPort(0)
                _connected.value = inputPort != null
                _statusText.value = if (inputPort != null)
                    "✓ ${device.name}" else "Error abriendo puerto"
            } else {
                _statusText.value = "No se pudo conectar"
            }
        }, handler)
    }

    // ── MIDI message senders ──

    fun sendNoteOn(channel: Int, note: Int, velocity: Int) {
        send(byteArrayOf(
            (0x90 or (channel and 0x0F)).toByte(),
            (note and 0x7F).toByte(),
            (velocity and 0x7F).toByte()
        ))
    }

    fun sendNoteOff(channel: Int, note: Int) {
        send(byteArrayOf(
            (0x80 or (channel and 0x0F)).toByte(),
            (note and 0x7F).toByte(),
            0x00
        ))
    }

    fun sendCC(channel: Int, cc: Int, value: Int) {
        send(byteArrayOf(
            (0xB0 or (channel and 0x0F)).toByte(),
            (cc and 0x7F).toByte(),
            (value and 0x7F).toByte()
        ))
    }

    fun sendPitchBend(channel: Int, value: Int) {
        // value: -8192 to 8191
        val v = (value + 8192).coerceIn(0, 16383)
        val lsb = (v and 0x7F).toByte()
        val msb = ((v shr 7) and 0x7F).toByte()
        send(byteArrayOf((0xE0 or (channel and 0x0F)).toByte(), lsb, msb))
    }

    fun sendClock() { send(byteArrayOf(0xF8.toByte())) }
    fun sendStart() {
        send(byteArrayOf(0xF2.toByte(), 0x00, 0x00)) // SPP = 0
        send(byteArrayOf(0xFA.toByte()))
    }
    fun sendStop()     { send(byteArrayOf(0xFC.toByte())) }
    fun sendContinue() { send(byteArrayOf(0xFB.toByte())) }

    private fun send(bytes: ByteArray) {
        try {
            inputPort?.send(bytes, 0, bytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun close() {
        inputPort?.close()
        openDevice?.close()
        inputPort = null
        openDevice = null
        _connected.value = false
    }
}
