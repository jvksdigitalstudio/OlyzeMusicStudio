package com.yeivikas.olyze.eliner.core

/**
 * How serious an [EngineError] is.
 *
 * This directly drives one real behavior in [EliNerCore]: a [FATAL] error
 * forces a transition to [EngineState.ERROR]; [WARNING] and [ERROR] do not.
 * Everything else about *displaying or logging* an error belongs to the
 * future Diagnostic System (`eliner.diagnostics`), not to Core.
 */
enum class EngineErrorSeverity {
    /** Something unexpected happened but the engine can keep running. */
    WARNING,

    /** A module or operation failed; the engine keeps running in a degraded state. */
    ERROR,

    /** The engine can no longer guarantee a consistent state. */
    FATAL,
}

/**
 * A single fault reported to the Core, from the Core itself or from a
 * registered [EliNerModule].
 *
 * This is deliberately just a data holder. Core's only job with it is to
 * (a) publish it on [EliNerCore.errors] and (b) force an [EngineState.ERROR]
 * transition if it's [EngineErrorSeverity.FATAL]. Deciding what to do with
 * an error beyond that — logging, showing it to the user, sending a report —
 * is exactly what the future Diagnostic System exists for; Core only needs
 * to guarantee that whoever implements it has a single, well-typed stream
 * to subscribe to.
 */
data class EngineError(
    val code: String,
    val message: String,
    val severity: EngineErrorSeverity,
    val moduleId: String? = null,
    val cause: Throwable? = null,
)
