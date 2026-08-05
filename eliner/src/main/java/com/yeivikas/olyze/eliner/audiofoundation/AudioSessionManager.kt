package com.yeivikas.olyze.eliner.audiofoundation

import com.yeivikas.olyze.eliner.core.StateMachine
import com.yeivikas.olyze.eliner.events.EliNerEvent
import com.yeivikas.olyze.eliner.events.EventBus
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle states of an audio session — administrative only. None of
 * these imply audio is actually flowing; that's the future Audio Engine
 * module's job.
 */
enum class AudioSessionState {
    /** No session has been requested yet. */
    UNINITIALIZED,

    /** [AudioSessionManager.initialize] is in progress. */
    INITIALIZING,

    /** Session is open and valid. */
    ACTIVE,

    /** Session temporarily suspended (e.g. audio focus lost) — not implemented yet, state exists for future use. */
    SUSPENDED,

    /** [AudioSessionManager.close] is in progress. */
    CLOSING,

    /** Session fully closed. Can be reinitialized from here. */
    CLOSED,

    /** Initialization or validation failed. */
    FAILED;

    companion object {
        private val VALID_TRANSITIONS: Map<AudioSessionState, Set<AudioSessionState>> = mapOf(
            UNINITIALIZED to setOf(INITIALIZING),
            INITIALIZING  to setOf(ACTIVE, FAILED),
            ACTIVE        to setOf(SUSPENDED, CLOSING, FAILED),
            SUSPENDED     to setOf(ACTIVE, CLOSING, FAILED),
            CLOSING       to setOf(CLOSED, FAILED),
            CLOSED        to setOf(INITIALIZING),
            FAILED        to setOf(CLOSING, INITIALIZING),
        )

        fun isValidTransition(from: AudioSessionState, to: AudioSessionState): Boolean =
            to in (VALID_TRANSITIONS[from] ?: emptySet())
    }
}

/** Published on [EventBus] whenever an [AudioSessionManager]'s state changes. */
data class AudioSessionStateChangedEvent(
    val previous: AudioSessionState,
    val current: AudioSessionState,
) : EliNerEvent

/**
 * Administers the lifecycle of an audio session: initialize, close,
 * reinitialize, validate state. **Does not open an audio stream or play
 * anything** — that distinction is the whole point of this phase ("no
 * reproducir audio todavía. Solo administrar el ciclo de vida").
 *
 * Publishes [AudioSessionStateChangedEvent] synchronously on every
 * successful transition — same technique as `EliNerRuntime` (Fase 2.5):
 * no [kotlinx.coroutines.CoroutineScope] needed, because this class is
 * the one calling the transition, so it can publish right after.
 */
class AudioSessionManager(private val eventBus: EventBus) {
    private val machine = StateMachine(AudioSessionState.UNINITIALIZED, AudioSessionState::isValidTransition)

    /** Current session state. */
    val state: StateFlow<AudioSessionState> = machine.state

    /** Opens a new session. Returns whether the transition succeeded. */
    fun initialize(): Boolean {
        if (!moveTo(AudioSessionState.INITIALIZING)) return false
        return moveTo(AudioSessionState.ACTIVE)
    }

    /** Closes the current session. Returns whether the transition succeeded. */
    fun close(): Boolean {
        if (!moveTo(AudioSessionState.CLOSING)) return false
        return moveTo(AudioSessionState.CLOSED)
    }

    /** Closes (if needed) and opens a fresh session. */
    fun reinitialize(): Boolean {
        if (state.value != AudioSessionState.CLOSED) {
            if (!close()) return false
        }
        return initialize()
    }

    /** Whether the session is currently usable (i.e. [AudioSessionState.ACTIVE]). */
    fun isValid(): Boolean = state.value == AudioSessionState.ACTIVE

    private fun moveTo(target: AudioSessionState): Boolean {
        val previous = machine.state.value
        val succeeded = machine.transitionTo(target)
        if (succeeded) {
            eventBus.publish(AudioSessionStateChangedEvent(previous, target))
        }
        return succeeded
    }
}
