package com.yeivikas.olyze.eliner.dspfoundation

/**
 * Contract every future DSP processor (EQ, Compressor, Limiter, Gate,
 * Reverb, Delay, Distortion, Filter, Analyzer, ...) will implement.
 *
 * **No implementation of this interface exists in this phase** — that's
 * explicitly future work for the phase that builds each specific
 * processor. This is "solo el contrato profesional," matching the
 * professional shape a real-time DSP processor needs:
 * - [prepare] is called once before any [process] call, with the sample
 *   rate and the largest block size that will ever be passed to
 *   [process] — exactly what a real processor needs to pre-allocate any
 *   internal state (filter coefficients, delay lines) *outside* the
 *   real-time path.
 * - [process] is the real-time call — per Fase 5's rule #1 ("cero
 *   operaciones bloqueantes"), any real implementation must do all its
 *   work without allocating, locking, or touching disk/network inside
 *   this method. This phase defines the signature; nothing calls it yet.
 * - [reset] clears internal state (e.g. delay line contents) without
 *   requiring a full [prepare] call again.
 */
interface DspProcessor {
    /** Stable, unique identifier — used as the key in [DspNode] and [DspGraph]. */
    val id: String

    /** Human-readable name, for logs/diagnostics. */
    val displayName: String

    /** Every parameter this processor exposes, via [DspParameterManager]-compatible [DspParameter]s. */
    fun parameters(): List<DspParameter>

    /**
     * Called once before any [process] call — never from the real-time
     * path. [sampleRateHz] and [maxFrameCount] are guaranteed not to
     * change without a new [prepare] call.
     */
    fun prepare(sampleRateHz: Int, maxFrameCount: Int)

    /**
     * Processes [frame] in place. Real-time path — no blocking operations
     * allowed in any real implementation. Not called by anything in this
     * phase.
     */
    fun process(frame: DspFrame)

    /** Clears internal state (e.g. filter/delay memory) without a full [prepare]. */
    fun reset()
}
