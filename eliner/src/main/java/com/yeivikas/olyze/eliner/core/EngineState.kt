package com.yeivikas.olyze.eliner.core

/**
 * The lifecycle states EliNer's Core Foundation can be in.
 *
 * This enum on its own does nothing — [EliNerCore] is the only place that
 * actually changes state, always through [isValidTransition]. That keeps
 * "what states exist" and "which transitions are legal" defined in exactly
 * one place, instead of scattered across whatever code happens to touch
 * state later.
 */
enum class EngineState {
    /** The engine object exists but [EliNerCore.initialize] hasn't run yet. */
    UNINITIALIZED,

    /** [EliNerCore.initialize] is in progress. */
    INITIALIZING,

    /** Initialized, idle. Modules are registered but not yet running. */
    READY,

    /** Modules have been started via [EliNerCore.start]. */
    RUNNING,

    /** Temporarily suspended via [EliNerCore.pause]; can resume to RUNNING. */
    PAUSED,

    /** [EliNerCore.stop] is in progress — modules are being shut down. */
    STOPPING,

    /** Fully stopped. Can be re-initialized from here. */
    STOPPED,

    /**
     * A fatal [EngineError] was reported (see [EliNerCore.reportError]).
     * Recovery requires an explicit re-initialize — the engine never
     * silently leaves ERROR on its own.
     */
    ERROR;

    companion object {
        // Every legal transition, listed explicitly. If a transition isn't
        // in this table, EliNerCore refuses it and the caller gets `false`
        // back — no partial/inconsistent state changes.
        private val VALID_TRANSITIONS: Map<EngineState, Set<EngineState>> = mapOf(
            UNINITIALIZED to setOf(INITIALIZING),
            INITIALIZING  to setOf(READY, ERROR),
            READY         to setOf(RUNNING, STOPPING, ERROR),
            RUNNING       to setOf(PAUSED, STOPPING, ERROR),
            PAUSED        to setOf(RUNNING, STOPPING, ERROR),
            STOPPING      to setOf(STOPPED, ERROR),
            STOPPED       to setOf(INITIALIZING),
            // ERROR can only be escalated to a clean shutdown or a fresh
            // re-init attempt — never straight back to RUNNING/READY, so a
            // faulty engine can't silently resume as if nothing happened.
            ERROR         to setOf(STOPPING, INITIALIZING),
        )

        /** Whether moving from [from] to [to] is a legal transition. */
        fun isValidTransition(from: EngineState, to: EngineState): Boolean =
            to in (VALID_TRANSITIONS[from] ?: emptySet())
    }
}
