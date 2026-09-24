package com.yeivikas.olyze.eliner.api

/**
 * Lifecycle states of the Audio Engine — a **distinct type** from
 * `com.yeivikas.olyze.eliner.core.EngineState` (Core Foundation) and
 * `RuntimeState` (this file, Runtime Foundation), per the explicit spec:
 * "No reutilizar RuntimeState. No reutilizar EngineState. Debe representar
 * únicamente el estado interno del Audio Engine."
 *
 * Lives in `eliner.api`, not `eliner.modules.audio`, for the same reason
 * [RuntimeState] does (see ADR 0006): `AudioEngineApi` needs to reference
 * this type, and `com.yeivikas.olyze.eliner.modules.audio.AudioEngine`
 * needs to implement `AudioEngineApi` — if this enum lived in
 * `eliner.modules.audio` instead, that would recreate the exact
 * `api ↔ modules.audio` cycle already found and fixed once. Applying that
 * lesson up front this time instead of discovering it again in an audit.
 */
enum class AudioEngineState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    RUNNING,
    PAUSED,
    STOPPING,
    STOPPED,
    ERROR;

    companion object {
        private val VALID_TRANSITIONS: Map<AudioEngineState, Set<AudioEngineState>> = mapOf(
            UNINITIALIZED to setOf(INITIALIZING),
            INITIALIZING  to setOf(READY, ERROR),
            READY         to setOf(RUNNING, STOPPING, ERROR),
            RUNNING       to setOf(PAUSED, STOPPING, ERROR),
            PAUSED        to setOf(RUNNING, STOPPING, ERROR),
            STOPPING      to setOf(STOPPED, ERROR),
            STOPPED       to setOf(INITIALIZING),
            ERROR         to setOf(STOPPING, INITIALIZING),
        )

        fun isValidTransition(from: AudioEngineState, to: AudioEngineState): Boolean =
            to in (VALID_TRANSITIONS[from] ?: emptySet())
    }
}
