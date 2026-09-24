package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.EliNerMidiApi
import com.yeivikas.olyze.eliner.api.MidiDeviceInfo
import com.yeivikas.olyze.eliner.api.MidiEvent
import com.yeivikas.olyze.eliner.api.MidiEventType
import com.yeivikas.olyze.eliner.services.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MIDI OUTPUT hacia hardware externo / DAW (app -> dispositivo).
 *
 * Reemplaza a la antigua `com.yeivikas.olyze.midi.OlyzeMidiManager`, que
 * reimplementaba desde cero conexión a dispositivo, `MidiManager.
 * DeviceCallback` y envío de bytes `android.media.midi.*` — exactamente lo
 * mismo que ya resuelven [AndroidMidiBackend]/[MidiDeviceManager], pero de
 * forma paralela y sin pasar por [EliNerMidiApi] (deuda documentada en
 * `eliner.modules.midi/README.md`, sección "Sobre OlyzeMidiManager", y en
 * `docs/adr/0012-midi-foundation.md`, sección "Qué se dejó fuera").
 *
 * Responsabilidad única: traducir llamadas de alto nivel (nota, CC, pitch
 * bend, transporte) a [MidiEvent] y entregarlas por el primer puerto de
 * salida disponible, vía [midi]. No descubre dispositivos por su cuenta,
 * no abre puertos, no importa `android.media.midi.*` — todo eso sigue
 * confinado a [com.yeivikas.olyze.eliner.modules.midi.AndroidMidiBackend]
 * (§26: un único archivo en todo el proyecto puede tocar esa API). Esta
 * clase solo observa [EliNerMidiApi.devices], que [MidiDeviceManager] ya
 * mantiene actualizado con hot-plug real.
 *
 * No es dueña de [midi]: la recibe ya construida e iniciada por quien la
 * inyecta (hoy, `MainViewModel`, que también la usa para MIDI de entrada).
 * En consecuencia, [close] nunca llama a `midi.stop()`/`midi.shutdown()`
 * — solo cancela la corrutina de observación que esta clase sí posee.
 */
class MidiOutputBridge(
    private val midi: EliNerMidiApi,
    private val timeProvider: TimeProvider,
    scope: CoroutineScope,
) {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _statusText = MutableStateFlow(STATUS_DISCONNECTED)
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    @Volatile
    private var activePortId: String? = null

    private val watchJob: Job = scope.launch {
        midi.devices.collect { devices -> onDevicesChanged(devices) }
    }

    private fun onDevicesChanged(devices: List<MidiDeviceInfo>) {
        val current = activePortId
        val currentStillPresent = current != null &&
            devices.any { device -> device.outputPorts.any { it.id == current } }
        if (currentStillPresent) return

        val target = devices.firstOrNull { it.outputPorts.isNotEmpty() }
        val port = target?.outputPorts?.firstOrNull()
        activePortId = port?.id
        _connected.value = port != null
        _statusText.value = when {
            port != null && target != null -> "\u2713 ${target.name}"
            devices.isEmpty() -> STATUS_DISCONNECTED
            else -> STATUS_NO_OUTPUT_PORT
        }
    }

    // ── Note / CC / Pitch Bend ──────────────────────────────────────────

    fun sendNoteOn(channel: Int, note: Int, velocity: Int) = emit(
        MidiEventType.NOTE_ON,
        channel = channel,
        data1 = note,
        data2 = velocity,
    )

    fun sendNoteOff(channel: Int, note: Int) = emit(
        MidiEventType.NOTE_OFF,
        channel = channel,
        data1 = note,
        data2 = 0,
    )

    fun sendCC(channel: Int, cc: Int, value: Int) = emit(
        MidiEventType.CONTROL_CHANGE,
        channel = channel,
        data1 = cc,
        data2 = value,
    )

    /** [value]: -8192..8191, 0 = centro — mismo rango que la antigua `OlyzeMidiManager.sendPitchBend`. */
    fun sendPitchBend(channel: Int, value: Int) = emit(
        MidiEventType.PITCH_BEND,
        channel = channel,
        pitchBendValue = (value + PITCH_BEND_CENTER).coerceIn(0, PITCH_BEND_MAX),
    )

    // ── Transporte / Clock ───────────────────────────────────────────────

    fun sendClock() = emit(MidiEventType.CLOCK, channel = null)
    fun sendStart() = emit(MidiEventType.START, channel = null)
    fun sendStop() = emit(MidiEventType.STOP, channel = null)
    fun sendContinue() = emit(MidiEventType.CONTINUE, channel = null)

    private fun emit(
        type: MidiEventType,
        channel: Int?,
        data1: Int = 0,
        data2: Int = 0,
        pitchBendValue: Int = PITCH_BEND_CENTER,
    ) {
        val portId = activePortId ?: return
        midi.send(
            portId,
            MidiEvent(
                type = type,
                channel = channel,
                data1 = data1,
                data2 = data2,
                pitchBendValue = pitchBendValue,
                timestampNanos = timeProvider.nowNanos(),
                sourcePortId = portId,
            ),
        )
    }

    /** Detiene solo la observación de dispositivos de este bridge. Nunca
     *  toca el ciclo de vida de [midi] — no le pertenece (ver doc de clase). */
    fun close() {
        watchJob.cancel()
    }

    private companion object {
        const val STATUS_DISCONNECTED = "Sin conexi\u00f3n MIDI"
        const val STATUS_NO_OUTPUT_PORT = "Ning\u00fan dispositivo con puerto de salida"
        const val PITCH_BEND_CENTER = 8192
        const val PITCH_BEND_MAX = 16383
    }
}
