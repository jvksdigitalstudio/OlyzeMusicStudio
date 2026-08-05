package com.yeivikas.olyze.eliner.audiofoundation

import android.content.Context
import com.yeivikas.olyze.eliner.api.RuntimeContext

/**
 * Aggregates every Audio Foundation component into one reference, mirroring
 * how [RuntimeContext] aggregates Foundation Services (Fase 2.5). A future
 * Audio Engine module receives one [AudioFoundationContext] instead of
 * importing 9 classes individually.
 */
class AudioFoundationContext(
    val session: AudioSessionManager,
    val devices: AudioDeviceProvider,
    val backend: AudioBackendManager,
    val format: AudioFormatManager,
    val sampleRate: SampleRateManager,
    val buffer: BufferManager,
    val clock: AudioClock,
    val latency: LatencyManager,
    val routing: AudioRoutingGraph,
    val channels: AudioChannelConfiguration,
)

/**
 * Builds an [AudioFoundationContext] from an existing [RuntimeContext] —
 * this *is* "Audio Capability Integration": every manager that needs
 * device/performance information gets it from [runtimeContext]'s already-
 * established interfaces ([RuntimeContext.capabilityProvider],
 * [RuntimeContext.performanceProfileProvider]), never a second,
 * duplicate reference.
 *
 * [applicationContext] is needed for exactly one thing — [AudioDeviceManager]
 * enumerating real audio devices — same restraint as
 * `com.yeivikas.olyze.eliner.api.createDefaultRuntimeContext` in Fase 2.5:
 * pass `applicationContext`, never an `Activity`, and it's used transiently
 * here, not retained beyond this call.
 */
fun createAudioFoundationContext(
    runtimeContext: RuntimeContext,
    applicationContext: Context,
): AudioFoundationContext {
    val sampleRateManager = SampleRateManager(runtimeContext.capabilityProvider)
    return AudioFoundationContext(
        session = AudioSessionManager(runtimeContext.events),
        devices = AudioDeviceManager(applicationContext),
        backend = AudioBackendManager(),
        format = AudioFormatManager(),
        sampleRate = sampleRateManager,
        buffer = BufferManager(runtimeContext.capabilityProvider, runtimeContext.performanceProfileProvider),
        clock = AudioClock(sampleRateManager),
        latency = LatencyManager(runtimeContext.capabilityProvider, runtimeContext.performanceProfileProvider),
        routing = AudioRoutingGraph(),
        channels = AudioChannelConfiguration(),
    )
}
