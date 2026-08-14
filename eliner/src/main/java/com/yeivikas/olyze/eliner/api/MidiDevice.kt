package com.yeivikas.olyze.eliner.api

/** Which direction a [MidiPortInfo] carries data. */
enum class MidiPortDirection { INPUT, OUTPUT }

/**
 * A single MIDI port on a [MidiDeviceInfo] — a device can expose several
 * (§9). [id] is a stable identifier scoped to its parent device, used by
 * [MidiEvent.sourcePortId] and by output calls that need to target a
 * specific port.
 */
data class MidiPortInfo(
    val id: String,
    val deviceId: String,
    val portIndex: Int,
    val direction: MidiPortDirection,
    val name: String,
)

/**
 * How a [MidiDeviceInfo] is physically connected. Only the transports
 * `android.media.midi.MidiDeviceInfo.getType()` actually reports are
 * listed here (`TYPE_USB`, `TYPE_VIRTUAL`, `TYPE_BLUETOOTH`, all present
 * since API 23 — well below this project's minSdk 24) — §4 explicitly
 * forbids inventing support the platform doesn't really have, but this
 * one platform DOES reliably distinguish all three, so [UNKNOWN] is only
 * ever the true fallback for a type value outside that documented set,
 * not a hedge against Bluetooth being unreliable.
 */
enum class MidiTransport { USB, BLUETOOTH, VIRTUAL, UNKNOWN }

/** Connection state of a [MidiDeviceInfo], for UI/consumers that care (§8). */
enum class MidiDeviceState { CONNECTED, DISCONNECTED }

/**
 * EliNer's own representation of a MIDI device — never
 * `android.media.midi.MidiDeviceInfo` itself (§25/§26). [id] is stable
 * for the lifetime of a connection but is NOT guaranteed stable across
 * reconnects of the same physical hardware (Android doesn't guarantee
 * that either — a re-plugged USB device can get a new internal id). Any
 * future persistence (e.g. "remember this controller's bindings") needs
 * to key on [name]/[manufacturer] or a more durable identity than [id],
 * which is out of scope for this phase.
 */
data class MidiDeviceInfo(
    val id: String,
    val name: String,
    val manufacturer: String?,
    val transport: MidiTransport,
    val state: MidiDeviceState,
    val inputPorts: List<MidiPortInfo>,
    val outputPorts: List<MidiPortInfo>,
)
