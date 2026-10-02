package com.yeivikas.olyze.eliner.runtime

import com.yeivikas.olyze.eliner.core.EliNerModule
import com.yeivikas.olyze.eliner.core.ModuleRegistryContract
import com.yeivikas.olyze.eliner.diagnostics.Logger

/**
 * Infrastructure for registering [EliNerModule]s and letting
 * [com.yeivikas.olyze.eliner.core.EliNerCore] manage their lifecycle — explicitly infrastructure only:
 * no Audio/DSP/MIDI/Plugin/Render module is loaded through this in this
 * phase, per the spec.
 *
 * Deliberately thin: The (single, concrete) [com.yeivikas.olyze.eliner.core.EliNerCore] (Fase 1) already owns module
 * registration ([com.yeivikas.olyze.eliner.core.EliNerCore.registerModule], exposed here only through
 * [ModuleRegistryContract] — ver ADR 0022) and already calls
 * [EliNerModule.onStart]/[EliNerModule.onStop] during its own
 * `start()`/`stop()`. [ModuleLoader] doesn't duplicate that — it adds the
 * one thing Core Foundation intentionally does *not* do (it has no
 * `Logger` dependency, by design — see `eliner.core`'s architecture
 * notes): logging module registration for Runtime Diagnostics.
 *
 * Does NOT implement hot-load-while-running semantics (loading a module
 * after [EliNerRuntime] is already `RUNNING` and immediately starting it).
 * With zero real modules to validate that behavior against yet, adding it
 * now would be exactly the "código provisional" this phase forbids — a
 * module loaded before [EliNerRuntime.initialize] will be started
 * correctly by [com.yeivikas.olyze.eliner.core.EliNerCore.start]; anything more is future work.
 */
class ModuleLoader(
    // Fase 1.1 §20 (ADR 0022): [ModuleRegistryContract], no la clase concreta
    // EliNerCore — ModuleLoader vive en la frontera pública (eliner.api) y
    // solo necesita registrar/dar de baja/consultar módulos, no el resto de
    // la superficie de EliNerCore (lifecycle, errores, snapshot).
    private val core: ModuleRegistryContract,
    private val logger: Logger,
) {
    fun load(module: EliNerModule) {
        core.registerModule(module)
        logger.info("ModuleLoader", "Module registered: ${module.id} (${module.displayName})")
    }

    fun unload(id: String): Boolean {
        val removed = core.unregisterModule(id)
        if (removed) {
            logger.info("ModuleLoader", "Module unregistered: $id")
        }
        return removed
    }

    fun isLoaded(id: String): Boolean = core.isModuleRegistered(id)

    fun get(id: String): EliNerModule? = core.getModule(id)
}
