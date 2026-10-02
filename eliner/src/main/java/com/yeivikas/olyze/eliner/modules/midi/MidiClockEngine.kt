package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.core.StateMachine
import com.yeivikas.olyze.eliner.services.TimeProvider
import kotlinx.coroutines.flow.StateFlow

/** §16: transport state. Two states only — §24: "no inventar 10 estados
 *  si solamente se necesitan unos pocos." CONTINUE doesn't get its own
 *  state: per the MIDI spec it resumes RUNNING from the current tick
 *  position rather than resetting it (unlike START, which does reset —
 *  see [onStart]); the distinction that matters is already captured by
 *  which method was called, not by an extra state value. */
enum class MidiTransportState { STOPPED, RUNNING }

/**
 * §15/§16: MIDI Clock tick counting + Start/Stop/Continue, fed
 * exclusively by [MidiRouter] (never call [onClockTick]/[onStart]/
 * [onStop]/[onContinue] from anywhere else — see [MidiRouter.dispatch]).
 *
 * §24: reuses [StateMachine] rather than a fourth hand-rolled transport
 * state holder — same class [MidiFoundationModule]'s own lifecycle
 * doesn't reuse (see its doc for why that one still needs
 * initialize/start/stop/shutdown, a different shape than this simple
 * two-state transport).
 *
 * Tempo estimation is real (computed from actual inter-tick timing via
 * [timeProvider]), not a fixed assumption — but this is explicitly NOT a
 * sequencer or a sample-accurate scheduler (§15: "no implementar todavía
 * un DAW completo"); [estimatedBpm] is a live readout, nothing here
 * schedules future events against it.
 */
class MidiClockEngine(private val timeProvider: TimeProvider) {
    private val stateMachine = StateMachine(
        initial = MidiTransportState.STOPPED,
        isValidTransition = { _, _ -> true }, // Both directions are always legal per the MIDI spec — Stop can arrive at any time, Start/Continue can too (e.g. a redundant Start while already running is just treated as a fresh reset, not an error).
    )
    val state: StateFlow<MidiTransportState> = stateMachine.state

    @Volatile private var tickCount: Long = 0L
    @Volatile private var lastTickNanos: Long? = null
    @Volatile private var estimatedBpm: Double = 0.0

    fun currentTick(): Long = tickCount

    /** Live tempo estimate in BPM, or `0.0` if fewer than 2 ticks have
     *  been observed yet (not enough data for an estimate). */
    fun currentEstimatedBpm(): Double = estimatedBpm

    fun onClockTick() {
        val now = timeProvider.nowNanos()
        val previous = lastTickNanos
        if (previous != null) {
            val deltaNanos = now - previous
            if (deltaNanos > 0) {
                val quarterNoteNanos = deltaNanos.toDouble() * TICKS_PER_QUARTER_NOTE
                estimatedBpm = 60.0 * 1_000_000_000.0 / quarterNoteNanos
            }
        }
        lastTickNanos = now
        tickCount++
    }

    /** Start resets the tick position to zero — per the MIDI spec, distinct from [onContinue]. */
    fun onStart() {
        tickCount = 0L
        lastTickNanos = null
        stateMachine.transitionTo(MidiTransportState.RUNNING)
    }

    /** Continue resumes from the current tick position — no reset. */
    fun onContinue() {
        stateMachine.transitionTo(MidiTransportState.RUNNING)
    }

    fun onStop() {
        // Reset the tempo-estimation baseline (NOT the tick count — see
        // onContinue) so that whenever ticks resume, the first one after
        // a pause doesn't compute its delta against a timestamp from
        // before the pause, which would produce a nonsensically low BPM
        // estimate for that single tick. Found while writing
        // MidiClockEngineTest — the same class of bug as onStart()'s
        // `lastTickNanos = null` reset, just triggered from the other
        // transport method that can precede a gap in ticks.
        lastTickNanos = null
        stateMachine.transitionTo(MidiTransportState.STOPPED)
    }

    private companion object {
        const val TICKS_PER_QUARTER_NOTE = 24
    }
}
