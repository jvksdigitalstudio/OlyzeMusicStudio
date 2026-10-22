package com.yeivikas.olyze.eliner.audiofoundation

/**
 * The single time reference for everything audio-related — "nunca
 * utilizar múltiples relojes independientes." Distinct on purpose from
 * `com.yeivikas.olyze.eliner.services.TimeProvider` (Fase 2): that one is
 * wall-clock/monotonic nanoseconds, general-purpose (logging timestamps,
 * measuring arbitrary durations). [AudioClock] is frame-based — its unit
 * is "samples since the clock started," which is what recording,
 * playback, automation, MIDI sync, and DSP will all eventually need to
 * agree on, and wall-clock time cannot give them (wall-clock drifts
 * relative to the audio hardware's own sample clock; frame counting does
 * not).
 *
 * Depends on [SampleRateProvider] (interface), not [SampleRateManager]
 * directly — the sample rate can change at runtime (see
 * [SampleRateManager.setSampleRate]), and every conversion below reads the
 * *current* rate at call time rather than capturing it once at
 * construction.
 *
 * This phase only builds the conversion math — no actual clock is started
 * against real audio callbacks yet, because there's no Audio Engine
 * driving real callbacks. [advanceFrames] is called by future code (once
 * it exists) each time a real audio buffer is processed.
 */
class AudioClock(private val sampleRateProvider: SampleRateProvider) {
    @Volatile
    private var framesElapsed: Long = 0L

    /** Total frames elapsed since this clock was created or last [reset]. */
    fun currentFrame(): Long = framesElapsed

    /** [currentFrame] converted to seconds at the current sample rate. */
    fun currentTimeSeconds(): Double =
        framesElapsed.toDouble() / sampleRateProvider.currentSampleRateHz.value.toDouble()

    /**
     * Advances the clock by [frameCount] — called once per processed
     * audio buffer, by whatever eventually drives real audio callbacks
     * (Audio Engine, not built in this phase).
     */
    fun advanceFrames(frameCount: Int) {
        require(frameCount >= 0) { "frameCount must not be negative, got $frameCount." }
        framesElapsed += frameCount
    }

    /** Converts a frame count to seconds at the current sample rate. */
    fun framesToSeconds(frames: Long): Double =
        frames.toDouble() / sampleRateProvider.currentSampleRateHz.value.toDouble()

    /** Converts a duration in seconds to a frame count at the current sample rate. */
    fun secondsToFrames(seconds: Double): Long =
        (seconds * sampleRateProvider.currentSampleRateHz.value).toLong()

    /** Resets the clock back to frame zero. */
    fun reset() {
        framesElapsed = 0L
    }
}
