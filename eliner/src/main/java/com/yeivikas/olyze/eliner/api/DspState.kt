package com.yeivikas.olyze.eliner.api

/**
 * Lifecycle states of the DSP Foundation — a **distinct type** from
 * `com.yeivikas.olyze.eliner.core.EngineState`, [RuntimeState],
 * [AudioEngineState], and `AudioSessionState`
 * (`com.yeivikas.olyze.eliner.audiofoundation`), per the explicit spec:
 * "Independiente de: EngineState, RuntimeState, AudioEngineState,
 * AudioSessionState."
 *
 * Lives in `eliner.api`, not `eliner.dspfoundation`, for the same reason
 * [AudioEngineState] does (see ADR 0006/0008): `DspApi` needs to
 * reference this type, and the DSP Foundation implementation needs to
 * implement `DspApi` — keeping the type here avoids recreating the
 * `api ↔ dspfoundation` cycle before it could ever happen.
 */
enum class DspState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    PROCESSING,
    SUSPENDED,
    STOPPING,
    STOPPED,
    ERROR;

    companion object {
        private val VALID_TRANSITIONS: Map<DspState, Set<DspState>> = mapOf(
            UNINITIALIZED to setOf(INITIALIZING),
            INITIALIZING  to setOf(READY, ERROR),
            READY         to setOf(PROCESSING, STOPPING, ERROR),
            PROCESSING    to setOf(SUSPENDED, STOPPING, ERROR),
            SUSPENDED     to setOf(PROCESSING, STOPPING, ERROR),
            STOPPING      to setOf(STOPPED, ERROR),
            STOPPED       to setOf(INITIALIZING),
            ERROR         to setOf(STOPPING, INITIALIZING),
        )

        fun isValidTransition(from: DspState, to: DspState): Boolean =
            to in (VALID_TRANSITIONS[from] ?: emptySet())
    }
}
