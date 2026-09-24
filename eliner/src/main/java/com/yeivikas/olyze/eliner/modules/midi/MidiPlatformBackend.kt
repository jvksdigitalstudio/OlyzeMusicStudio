package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.MidiDeviceInfo
import com.yeivikas.olyze.eliner.api.MidiEvent

/**
 * §26: the seam between EliNer's MIDI vocabulary and whatever platform
 * API actually talks to hardware. [AndroidMidiBackend] is the only
 * implementation this phase builds, but nothing above this interface
 * (device manager, router, API) ever imports `android.media.midi.*` —
 * that import list is confined to [AndroidMidiBackend] alone (verify with
 * `grep -rl android.media.midi eliner/src/main/java` — it should return
 * exactly one file).
 */
interface MidiPlatformBackend {
    /** `null` if the platform's MIDI service isn't available (§4: some
     *  devices report [android.content.pm.PackageManager.
     *  FEATURE_MIDI] absent; must degrade, not crash). */
    fun isAvailable(): Boolean

    /** Snapshot of every currently known device. */
    fun listDevices(): List<MidiDeviceInfo>

    /**
     * Starts watching for hot-plug (§36). [onDeviceConnected]/
     * [onDeviceDisconnected] are called from whatever thread the platform
     * delivers the notification on — callers must not assume a specific
     * thread.
     */
    fun startWatching(
        onDeviceConnected: (MidiDeviceInfo) -> Unit,
        onDeviceDisconnected: (MidiDeviceInfo) -> Unit,
    )

    fun stopWatching()

    /**
     * Opens every input port of [device] and begins delivering events to
     * [onEvent] — called from the platform's own callback thread (a
     * Binder thread on Android), never blocking, never doing anything
     * beyond handing the event off (§10: "El callback debe ser ligero").
     * Returns `false` if the device couldn't be opened.
     */
    fun openInputPorts(device: MidiDeviceInfo, onEvent: (MidiEvent) -> Unit): Boolean

    /** Closes every open port belonging to [deviceId], if any are open. */
    fun closeDevice(deviceId: String)

    /**
     * Sends [event] out [portId]. Returns `false` on any failure
     * (unknown port, closed device, platform send error) — never throws
     * (§34).
     */
    fun send(portId: String, event: MidiEvent): Boolean

    /** Closes every open device/port and releases platform resources. */
    fun shutdown()
}
