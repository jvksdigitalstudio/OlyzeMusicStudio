package com.yeivikas.olyze.eliner.services

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build

/**
 * Detects device capabilities via Android system services — never UI
 * (`Context` and framework system-service classes are not `Activity`/
 * `Fragment`/`View`/Compose/Material/XML, so this stays within the "cero
 * dependencia de Android UI" rule).
 *
 * Read-only, by design: this class never writes a setting, requests a
 * permission, or opens an audio stream. It only answers "what can this
 * device do", per the spec ("No modificar configuraciones. Solo detectar
 * capacidades y exponer información").
 *
 * [context] is deliberately typed as [Application], not the more general
 * [Context] — this class holds onto it for the manager's lifetime, so a
 * plain `Context` would let a caller pass an Activity and leak it. Fase
 * — hardening pass: this used to accept `Context` with only a doc-comment
 * warning against Activity contexts; every real call site already passed
 * an application context, so tightening the type to enforce that at
 * compile time was a zero-risk change (see docs/adr for this audit).
 */
class DeviceCapabilityManager(private val context: Application) : CapabilityProvider {

    override fun detect(): DeviceCapabilities {
        val packageManager = context.packageManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val glEsVersionEncoded = activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val openGlEsVersion = "${glEsVersionEncoded shr 16}.${glEsVersionEncoded and 0xFFFF}"

        val nativeSampleRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
        val nativeFramesPerBuffer = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
        val estimatedLatencyMillis =
            if (nativeSampleRate != null && nativeSampleRate > 0 && nativeFramesPerBuffer != null) {
                nativeFramesPerBuffer.toFloat() / nativeSampleRate.toFloat() * 1000f
            } else {
                null
            }

        return DeviceCapabilities(
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            androidSdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            totalRamBytes = memoryInfo.totalMem,
            openGlEsVersion = openGlEsVersion,
            vulkanSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION),
            audioLowLatencySupported = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY),
            audioProSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
            nativeOutputSampleRateHz = nativeSampleRate,
            nativeFramesPerBuffer = nativeFramesPerBuffer,
            estimatedOutputLatencyMillis = estimatedLatencyMillis,
            usbHostSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            bluetoothLeSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
        )
    }
}
