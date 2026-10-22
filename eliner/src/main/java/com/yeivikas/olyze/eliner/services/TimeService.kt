package com.yeivikas.olyze.eliner.services

/** Default [TimeSource], backed by the JVM's own clock. No Android dependency. */
class SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun nowNanos(): Long = System.nanoTime()
}

/**
 * The single shared time reference for all of EliNer, per the spec:
 * "Debe existir una única referencia de tiempo para todo EliNer." Anything
 * that will eventually need timing — automation, playback, sync, MIDI,
 * recording — reads through this instead of calling `System.
 * currentTimeMillis()`/`System.nanoTime()` directly, so there's exactly
 * one place to swap in a different clock later.
 *
 * This phase does not add musical/sample-accurate time (bars, beats,
 * samples-since-start) — that's Timeline Engine's job. This is
 * deliberately just the low-level shared clock everything else will be
 * built on top of.
 */
class TimeService(private val source: TimeSource = SystemTimeSource()) : TimeProvider {
    override fun nowMillis(): Long = source.nowMillis()
    override fun nowNanos(): Long = source.nowNanos()

    /** Nanoseconds elapsed since [startNanos] (a value previously read from [nowNanos]). */
    override fun elapsedNanosSince(startNanos: Long): Long = source.nowNanos() - startNanos
}
