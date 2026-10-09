package com.yeivikas.olyze.eliner.core

/**
 * Contract every future EliNer module (Audio Engine, DSP Engine, MIDI
 * Engine, Mixer Engine, Timeline Engine, Render Engine, Project System,
 * Resource Manager, Configuration System, Diagnostics System, Recovery
 * System, Adaptive Plugin System...) must implement to be registered with
 * [ModuleRegistry] and orchestrated by [EliNerCore].
 *
 * Intentionally minimal. This phase does not implement any of the modules
 * listed above, so this interface only defines what [EliNerCore] itself
 * needs to be able to do with *any* module, generically:
 * - identify it ([id], [displayName])
 * - start it when the engine starts ([onStart])
 * - stop it when the engine stops ([onStop])
 *
 * That's also why [ModuleRegistry] doesn't need a stub class per future
 * module to "support" them — any class implementing this one interface can
 * be registered, whatever it ends up doing internally.
 *
 * Not included on purpose: pause/resume hooks, async/suspend lifecycle
 * methods, or a per-module state machine. Nothing in this phase needs
 * them, and adding them now would be guessing at requirements no real
 * module has stated yet. Add them in the phase that implements the first
 * module that actually needs them.
 */
interface EliNerModule {
    /**
     * Stable, unique identifier used as the [ModuleRegistry] key — e.g.
     * `"audio"`, `"dsp"`, `"midi"`. Must not change between versions of the
     * same module once other code may depend on it.
     */
    val id: String

    /** Human-readable name, for logs/diagnostics — never used as a key. */
    val displayName: String

    /**
     * Called by [EliNerCore] when the engine transitions to
     * [EngineState.RUNNING]. Implementations should throw on
     * unrecoverable failure — [EliNerCore] catches it, reports an
     * [EngineError], and continues starting the remaining modules rather
     * than letting one bad module abort engine startup entirely.
     */
    fun onStart()

    /**
     * Called by [EliNerCore] when the engine transitions to
     * [EngineState.STOPPING]. Same failure-handling contract as
     * [onStart]: throwing is reported, not propagated.
     */
    fun onStop()
}
