package com.yeivikas.olyze.eliner.audiofoundation

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * The categories this phase distinguishes — matching the spec's explicit
 * list. Android's own `AudioDeviceInfo.TYPE_*` constants don't distinguish
 * "USB Audio" from "professional USB interface" (both report as
 * `TYPE_USB_DEVICE`/`TYPE_USB_ACCESSORY`/`TYPE_USB_HEADSET`) — that
 * distinction, if it ever matters, would need to come from something
 * beyond what the OS reports (e.g. a known-interfaces database), which is
 * explicitly out of scope here. Documented as a real limitation, not
 * hidden.
 */
enum class AudioDeviceType {
    BUILTIN_SPEAKER,
    WIRED_HEADSET,
    USB_AUDIO,
    BLUETOOTH_AUDIO,
    BLUETOOTH_LE_AUDIO,
    OTHER,
}

/** A single audio device as reported by the system, at the moment [AudioDeviceManager.listDevices] was called. */
data class AudioDevice(
    val type: AudioDeviceType,
    val productName: String,
    val isSource: Boolean,
    val isSink: Boolean,
)

/** The contract [AudioFoundationContext] depends on instead of [AudioDeviceManager] directly. */
interface AudioDeviceProvider {
    /** Every currently connected audio device (input and output). */
    fun listDevices(): List<AudioDevice>
}

/**
 * Enumerates audio devices via [AudioManager] — a system service, not UI,
 * same reasoning already established by
 * `com.yeivikas.olyze.eliner.services.DeviceCapabilityManager` in Fase 2.
 *
 * Read-only: never opens a device, never routes audio to one. That's
 * explicitly Audio Engine's job, once it exists ("no reproducir sonido.
 * Solo administrar dispositivos").
 */
class AudioDeviceManager(private val context: Context) : AudioDeviceProvider {

    override fun listDevices(): List<AudioDevice> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()

        return audioManager.getDevices(AudioManager.GET_DEVICES_ALL).map { info ->
            AudioDevice(
                type = mapDeviceType(info.type),
                productName = info.productName?.toString() ?: "Unknown",
                isSource = info.isSource,
                isSink = info.isSink,
            )
        }
    }

    private fun mapDeviceType(type: Int): AudioDeviceType = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        -> AudioDeviceType.BUILTIN_SPEAKER

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> AudioDeviceType.WIRED_HEADSET

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioDeviceType.USB_AUDIO

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> AudioDeviceType.BLUETOOTH_AUDIO

        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> AudioDeviceType.BLUETOOTH_LE_AUDIO

        else -> AudioDeviceType.OTHER
    }
}
