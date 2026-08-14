package com.yeivikas.olyze.eliner.api

/**
 * Lifecycle states of [EliNerRuntime] — deliberately a **separate type**
 * from `com.yeivikas.olyze.eliner.core.EngineState`, per the spec
 * ("Runtime State... debe ser independiente del EngineState existente").
 *
 * They represent different things at different granularity:
 * [com.yeivikas.olyze.eliner.core.EngineState] tracks Core Foundation
 * alone; [RuntimeState] tracks the composed whole (Core + every Foundation
 * Service + every loaded module) as seen from [EliNerRuntime]. A future
 * scenario where Core is `RUNNING` but a Foundation Service failed to
 * initialize is exactly why these can't be the same enum.
 */
enum class RuntimeState {
    /** [EliNerRuntime] has been constructed but [EliNerRuntime.initialize] hasn't run. */
    CREATED,

    /** [EliNerRuntime.initialize] is in progress — Core is starting, services are being wired. */
    INITIALIZING,

    /** Fully initialized and operating. */
    RUNNING,

    /** Temporarily suspended via [EliNerRuntime.pause]. */
    PAUSED,

    /** [EliNerRuntime.shutdown] is in progress. */
    STOPPING,

    /** Fully stopped. Can be re-initialized from here. */
    STOPPED,

    /**
     * Initialization or a critical operation failed. Distinct from Core's
     * own `EngineState.ERROR` — a `RuntimeState.FAILED` can happen even
     * when Core itself is fine (e.g. a Foundation Service failed to wire
     * up), and does not by itself force Core into its own ERROR state.
     */
    FAILED;

    companion object {
        private val VALID_TRANSITIONS: Map<RuntimeState, Set<RuntimeState>> = mapOf(
            CREATED      to setOf(INITIALIZING),
            INITIALIZING to setOf(RUNNING, FAILED),
            RUNNING      to setOf(PAUSED, STOPPING, FAILED),
            PAUSED       to setOf(RUNNING, STOPPING, FAILED),
            STOPPING     to setOf(STOPPED, FAILED),
            STOPPED      to setOf(INITIALIZING),
            FAILED       to setOf(STOPPING, INITIALIZING),
        )

        /** Whether moving from [from] to [to] is a legal transition. */
        fun isValidTransition(from: RuntimeState, to: RuntimeState): Boolean =
            to in (VALID_TRANSITIONS[from] ?: emptySet())
    }
}
