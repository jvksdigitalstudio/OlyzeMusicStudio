package com.yeivikas.olyze.eliner.modules.audio

import com.yeivikas.olyze.eliner.api.AudioEngineState
import com.yeivikas.olyze.eliner.core.StateMachine
import com.yeivikas.olyze.eliner.events.EliNerEvent
import com.yeivikas.olyze.eliner.events.EventBus
import kotlinx.coroutines.flow.StateFlow

/** Published on [EventBus] whenever the Audio Engine's state changes. */
data class AudioEngineStateChangedEvent(
    val previous: AudioEngineState,
    val current: AudioEngineState,
) : EliNerEvent

/**
 * Owns [AudioEngineState] and validates every transition — the "Audio
 * State Machine" the spec asks for, built on
 * `com.yeivikas.olyze.eliner.core.StateMachine<S>` (Fase 3) instead of a
 * fourth hand-rolled copy of the transition-table pattern.
 *
 * Administers `start`/`pause`/`resume`/`stop`/`flush`/`restart` exactly as
 * named in the spec. [flush] and [restart] don't transition state on their
 * own the way the others do — [flush] is a precondition check only (the
 * actual buffer-clearing side effect belongs to
 * [com.yeivikas.olyze.eliner.modules.audio.AudioEngine], which owns the
 * buffer pool this controller doesn't know about); [restart] is a
 * `stop`-then-`start` sequence.
 */
class AudioStreamController(private val eventBus: EventBus) {
    private val machine = StateMachine(AudioEngineState.UNINITIALIZED, AudioEngineState::isValidTransition)

    /** Current engine lifecycle state. */
    val state: StateFlow<AudioEngineState> = machine.state

    fun initialize(): Boolean {
        if (!moveTo(AudioEngineState.INITIALIZING)) return false
        return moveTo(AudioEngineState.READY)
    }

    fun start(): Boolean = moveTo(AudioEngineState.RUNNING)

    fun pause(): Boolean = moveTo(AudioEngineState.PAUSED)

    fun resume(): Boolean = moveTo(AudioEngineState.RUNNING)

    fun stop(): Boolean {
        if (!moveTo(AudioEngineState.STOPPING)) return false
        return moveTo(AudioEngineState.STOPPED)
    }

    /** Whether a flush is currently legal — [AudioEngineState.RUNNING] or [AudioEngineState.PAUSED] only. */
    fun canFlush(): Boolean = state.value == AudioEngineState.RUNNING || state.value == AudioEngineState.PAUSED

    /** Stops (if not already stopped) and starts a fresh cycle. */
    fun restart(): Boolean {
        if (state.value != AudioEngineState.STOPPED) {
            if (!stop()) return false
        }
        if (!initialize()) return false
        return start()
    }

    private fun moveTo(target: AudioEngineState): Boolean {
        val previous = machine.state.value
        val succeeded = machine.transitionTo(target)
        if (succeeded) {
            eventBus.publish(AudioEngineStateChangedEvent(previous, target))
        }
        return succeeded
    }
}
