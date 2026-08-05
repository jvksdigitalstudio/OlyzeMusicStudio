package com.yeivikas.olyze.eliner.core

/**
 * A point-in-time, read-only snapshot of the engine's overall status —
 * what you'd show in a future "About EliNer" or diagnostics screen.
 *
 * Produced by [EliNerCore.snapshot]. Every field is read from a live
 * source at the moment of the call ([EngineVersion.CURRENT], the current
 * [EngineState], the actual [ModuleRegistry] contents) — nothing here is
 * hardcoded or guessed. If no modules are registered yet,
 * [registeredModuleIds] is simply empty; this class doesn't invent
 * capabilities the engine doesn't have.
 */
data class EngineInfo(
    val version: EngineVersion,
    val state: EngineState,
    val registeredModuleIds: List<String>,
) {
    /** Convenience for callers that only care about module count, not identity. */
    val registeredModuleCount: Int get() = registeredModuleIds.size
}
