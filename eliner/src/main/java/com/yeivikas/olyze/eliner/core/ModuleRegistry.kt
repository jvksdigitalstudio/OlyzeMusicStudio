package com.yeivikas.olyze.eliner.core

/**
 * Registers and looks up [EliNerModule] instances by [EliNerModule.id].
 *
 * Because [EliNerModule] is a generic contract, this class doesn't need to
 * know anything about Audio, DSP, MIDI, or any other future module — it
 * works identically for all of them. That's the actual point of the
 * "module registry" the Core Foundation is supposed to provide: the
 * mechanism to register modules, not per-module wiring for modules that
 * don't exist yet.
 *
 * Thread safety: registration/lookup use a plain `synchronized` block, not
 * a suspend-based lock. This is deliberate — module registration happens
 * during engine setup, not on any real-time-sensitive path (Core Foundation
 * doesn't touch the audio thread at all). A blocking lock here is simpler
 * and correct; if a future module needs lock-free reads on a hot path,
 * that's a concern for that module to solve, not something Core should
 * pre-solve speculatively.
 */
class ModuleRegistry {
    private val lock = Any()
    private val modules = mutableMapOf<String, EliNerModule>()

    /**
     * Registers [module]. Throws [IllegalArgumentException] if a module
     * with the same [EliNerModule.id] is already registered — silently
     * overwriting a registered module would hide a real bug (two modules
     * accidentally claiming the same id) rather than surfacing it.
     */
    fun register(module: EliNerModule) {
        synchronized(lock) {
            require(module.id !in modules) {
                "A module with id '${module.id}' is already registered " +
                    "(existing: ${modules[module.id]?.displayName})."
            }
            modules[module.id] = module
        }
    }

    /** Unregisters the module with [id]. Returns whether it was registered. */
    fun unregister(id: String): Boolean = synchronized(lock) {
        modules.remove(id) != null
    }

    /** Returns the module registered under [id], or null. */
    fun get(id: String): EliNerModule? = synchronized(lock) { modules[id] }

    /** Whether a module with [id] is currently registered. */
    fun isRegistered(id: String): Boolean = synchronized(lock) { id in modules }

    /** Snapshot of every currently registered module. */
    fun getAll(): List<EliNerModule> = synchronized(lock) { modules.values.toList() }

    /** Number of currently registered modules. */
    fun size(): Int = synchronized(lock) { modules.size }
}
