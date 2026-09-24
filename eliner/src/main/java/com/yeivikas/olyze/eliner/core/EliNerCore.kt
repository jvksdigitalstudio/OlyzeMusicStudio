package com.yeivikas.olyze.eliner.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * EliNer's Core Foundation — the administrative center of the engine.
 *
 * This is **not** an audio engine, a DSP engine, or anything that touches
 * real-time media. It owns exactly four things, matching the Fase 1 spec:
 * 1. [state] — the engine's lifecycle ([EngineState]), safely transitioned.
 * 2. A [ModuleRegistry] — where future modules (Audio, DSP, MIDI, ...) will
 *    register themselves; this phase implements the mechanism, not any
 *    module.
 * 3. [version] — this build's [EngineVersion].
 * 4. [errors] — a stream future Diagnostics can subscribe to.
 *
 * ## Dependency direction
 * This file, and every other file in `eliner.core`, imports nothing from
 * `eliner.api`, `eliner.bridge`, `eliner.modules`, or `:app`. That's not a
 * convention to remember — it's simply true today: the only imports above
 * are Kotlin stdlib and `kotlinx.coroutines.flow`. Future modules will
 * depend on `eliner.core`; `eliner.core` must never depend on them.
 *
 * ## What this class deliberately does NOT do
 * - It does not call into `eliner.bridge.EliNerAudioBridge`. Wiring the
 *   existing audio engine in as a registered [EliNerModule] is future work
 *   for the phase that implements the Audio Engine module — touching it
 *   here would risk the one thing this phase must not break.
 * - It does not implement Diagnostics, Configuration, Recovery, or any
 *   other system listed in the architecture docs. It only exposes the
 *   minimal surface those future systems will need ([errors], [snapshot]).
 */
class EliNerCore(
    /**
     * The only piece of "configuration" Core Foundation has right now:
     * what name this engine instance reports as (see [EngineInfo]/
     * [EngineVersion]). Defaults to the real engine name instead of a
     * literal hardcoded elsewhere, per the "no hardcoded values that could
     * be configured" rule — deliberately not a bigger `EngineConfig` class,
     * because there is nothing else genuinely configurable at this layer
     * yet. Expand this if/when a second real option exists.
     */
    private val engineName: String = EngineVersion.CURRENT.name,
) {
    private val _state = MutableStateFlow(EngineState.UNINITIALIZED)

    /** Current lifecycle state. Collect this to react to engine state changes. */
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val moduleRegistry = ModuleRegistry()

    private val _errors = MutableSharedFlow<EngineError>(extraBufferCapacity = 32)

    /**
     * Stream of every [EngineError] reported via [reportError]. No
     * subscriber exists yet in this phase — this is the contract the
     * future Diagnostic System will consume.
     */
    val errors: SharedFlow<EngineError> = _errors.asSharedFlow()

    /** This build's version/identity information. */
    val version: EngineVersion = EngineVersion.CURRENT.copy(name = engineName)

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Moves the engine from [EngineState.UNINITIALIZED] (or [EngineState.STOPPED]
     * / [EngineState.ERROR]) to [EngineState.READY]. Returns `false` without
     * side effects if the current state doesn't allow it.
     *
     * There is nothing for Core Foundation itself to initialize beyond its
     * own state — no modules exist yet in this phase. Once real modules are
     * registered in future phases, this is where their own init hooks would
     * be invoked, following the same pattern [start]/[stop] already use for
     * [EliNerModule.onStart]/[EliNerModule.onStop].
     */
    fun initialize(): Boolean {
        if (!transitionTo(EngineState.INITIALIZING)) return false
        return transitionTo(EngineState.READY)
    }

    /**
     * Moves the engine to [EngineState.RUNNING] and calls [EliNerModule.onStart]
     * on every registered module. A module that throws is reported via
     * [reportError] (severity [EngineErrorSeverity.ERROR]) and does not
     * prevent the remaining modules from starting.
     */
    fun start(): Boolean {
        if (!transitionTo(EngineState.RUNNING)) return false
        for (module in moduleRegistry.getAll()) {
            runCatching { module.onStart() }.onFailure { throwable ->
                reportError(
                    EngineError(
                        code = "MODULE_START_FAILED",
                        message = "Module '${module.id}' failed to start: ${throwable.message}",
                        severity = EngineErrorSeverity.ERROR,
                        moduleId = module.id,
                        cause = throwable,
                    )
                )
            }
        }
        return true
    }

    /** Suspends a running engine. See [EngineState] for valid transitions. */
    fun pause(): Boolean = transitionTo(EngineState.PAUSED)

    /** Resumes a paused engine back to [EngineState.RUNNING]. */
    fun resume(): Boolean = transitionTo(EngineState.RUNNING)

    /**
     * Moves the engine to [EngineState.STOPPING], calls [EliNerModule.onStop]
     * on every registered module (same failure-tolerant behavior as
     * [start]), then to [EngineState.STOPPED].
     */
    fun stop(): Boolean {
        if (!transitionTo(EngineState.STOPPING)) return false
        for (module in moduleRegistry.getAll()) {
            runCatching { module.onStop() }.onFailure { throwable ->
                reportError(
                    EngineError(
                        code = "MODULE_STOP_FAILED",
                        message = "Module '${module.id}' failed to stop: ${throwable.message}",
                        severity = EngineErrorSeverity.ERROR,
                        moduleId = module.id,
                        cause = throwable,
                    )
                )
            }
        }
        return transitionTo(EngineState.STOPPED)
    }

    // ── Module registry (delegated) ───────────────────────────────────

    /** Registers [module]. See [ModuleRegistry.register] for failure behavior. */
    fun registerModule(module: EliNerModule) = moduleRegistry.register(module)

    /** Unregisters the module with [id]. Returns whether it was registered. */
    fun unregisterModule(id: String): Boolean = moduleRegistry.unregister(id)

    /** Returns the module registered under [id], or null. */
    fun getModule(id: String): EliNerModule? = moduleRegistry.get(id)

    /** Whether a module with [id] is currently registered. */
    fun isModuleRegistered(id: String): Boolean = moduleRegistry.isRegistered(id)

    // ── Error reporting ────────────────────────────────────────────────

    /**
     * Publishes [error] on [errors]. A [EngineErrorSeverity.FATAL] error
     * additionally forces a transition to [EngineState.ERROR] — the engine
     * never silently continues after a fatal fault.
     */
    fun reportError(error: EngineError) {
        _errors.tryEmit(error)
        if (error.severity == EngineErrorSeverity.FATAL) {
            transitionTo(EngineState.ERROR)
        }
    }

    // ── Info ───────────────────────────────────────────────────────────

    /** A live [EngineInfo] snapshot — see its documentation for what "live" means. */
    fun snapshot(): EngineInfo = EngineInfo(
        version = version,
        state = _state.value,
        registeredModuleIds = moduleRegistry.getAll().map { it.id },
    )

    // ── Internal: the single place state actually changes ─────────────

    /**
     * Atomically validates and applies a state transition using
     * [EngineState.isValidTransition]. Returns whether it succeeded.
     *
     * Implemented with [MutableStateFlow.update] (a lock-free CAS loop)
     * rather than a `synchronized`/`Mutex` block — this can be called from
     * any thread without ever blocking a caller, which matters once
     * modules start calling [reportError] from their own threads.
     */
    private fun transitionTo(target: EngineState): Boolean {
        var succeeded = false
        _state.update { current ->
            if (EngineState.isValidTransition(current, target)) {
                succeeded = true
                target
            } else {
                succeeded = false
                current
            }
        }
        return succeeded
    }
}
