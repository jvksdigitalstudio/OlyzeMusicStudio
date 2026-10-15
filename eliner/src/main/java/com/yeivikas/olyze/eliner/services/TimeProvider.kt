package com.yeivikas.olyze.eliner.services

/**
 * Where [TimeService] actually reads time from. An interface so a future
 * musical/sample-accurate clock (once Timeline Engine exists) can be
 * substituted without changing anything that already depends on
 * [TimeService] — that future clock is explicitly NOT built here (it
 * belongs to Timeline Engine); this seam just makes room for it.
 */
interface TimeSource {
    /** Wall-clock milliseconds, e.g. for timestamps ([com.yeivikas.olyze.eliner.diagnostics.LogEntry]). */
    fun nowMillis(): Long

    /** Monotonic nanoseconds, for measuring durations — never for timestamps. */
    fun nowNanos(): Long
}

/**
 * The contract [com.yeivikas.olyze.eliner.runtime.RuntimeContext] should
 * depend on instead of [TimeService] directly. Added in Fase 2.5, same
 * reasoning as `eliner.diagnostics.Logger`.
 */
interface TimeProvider {
    fun nowMillis(): Long
    fun nowNanos(): Long
    fun elapsedNanosSince(startNanos: Long): Long
}
