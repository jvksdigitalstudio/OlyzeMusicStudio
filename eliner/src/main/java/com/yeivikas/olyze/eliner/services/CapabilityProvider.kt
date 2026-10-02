package com.yeivikas.olyze.eliner.services

import android.content.pm.PackageManager

/**
 * A snapshot of what the current device can do. Every field is read at
 * [DeviceCapabilityManager.detect] time — nothing here is a guess or a
 * hardcoded default; a field is only non-null/non-zero if the system
 * actually reported it.
 */
data class DeviceCapabilities(
    val cpuCoreCount: Int,
    val supportedAbis: List<String>,
    val androidSdkInt: Int,
    val androidRelease: String,
    val totalRamBytes: Long,
    val openGlEsVersion: String,
    val vulkanSupported: Boolean,
    val audioLowLatencySupported: Boolean,
    val audioProSupported: Boolean,
    val nativeOutputSampleRateHz: Int?,
    val nativeFramesPerBuffer: Int?,
    val estimatedOutputLatencyMillis: Float?,
    /**
     * Coarse proxy for "this device can host USB audio interfaces", based
     * on [PackageManager.FEATURE_USB_HOST]. Not a guarantee the OS
     * actually exposes a connected interface as an audio device — real USB
     * Audio class negotiation is Audio Engine's job, once it exists.
     */
    val usbHostSupported: Boolean,
    /**
     * Coarse proxy for "this device can talk to Bluetooth MIDI
     * controllers", based on [PackageManager.FEATURE_BLUETOOTH_LE] (BLE is
     * a precondition for BLE-MIDI). Not a guarantee any MIDI-capable
     * device is currently paired — that's MIDI Engine's job, once it
     * exists.
     */
    val bluetoothLeSupported: Boolean,
)

/** The part of [DeviceCapabilityManager] other services are allowed to depend on. */
interface CapabilityProvider {
    fun detect(): DeviceCapabilities
}
