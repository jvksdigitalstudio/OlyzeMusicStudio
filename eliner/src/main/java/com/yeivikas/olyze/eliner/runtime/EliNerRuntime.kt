package com.yeivikas.olyze.eliner.runtime

import com.yeivikas.olyze.eliner.configuration.Configuration
import com.yeivikas.olyze.eliner.core.EliNerCore
import com.yeivikas.olyze.eliner.diagnostics.Logger
import com.yeivikas.olyze.eliner.events.EventBus
import com.yeivikas.olyze.eliner.resources.Resources
import com.yeivikas.olyze.eliner.services.CapabilityProvider
import com.yeivikas.olyze.eliner.services.ExecutionLane
import com.yeivikas.olyze.eliner.services.PerformanceProfileProvider
import com.yeivikas.olyze.eliner.services.TaskExecutor
import com.yeivikas.olyze.eliner.services.TimeProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * EliNer's Runtime — the real heart of the engine, per the spec: "el
 * Runtime deberá permanecer completamente independiente de Android UI y
 * de Olyze Music Studio... reutilizable posteriormente en Olyze Music
 * Studio, Olyze Movie Creator, futuros proyectos."
 *
 * This class is named `EliNerRuntime`, not `Runtime` — `java.lang.Runtime`
 * is implicitly in scope in every Kotlin file, and this codebase already
 * calls `Runtime.getRuntime()` in `eliner.services.DeviceCapabilityManager`.
 * Naming this class plain `Runtime` would compile (same-package resolution
 * always wins within `eliner.runtime` itself), but would be a real, needless
 * source of confusion for anyone reading across packages — so it wasn't
 * done, and the more descriptive name also matches the existing
 * `EliNerCore`/`EliNerModule`/`EliNerAudioApi` naming convention.
 *
 * What this class knows about: [EliNerCore] (Fase 1), [RuntimeContext]
 * (Fase 2 services, always behind interfaces), [LifecycleManager],
 * [ServiceRegistry], [ModuleLoader]. What it does **not** know about:
 * Audio, MIDI, DSP, Plugins, or anything UI — none of those are imported
 * here, by design.
 */
class EliNerRuntime(
    private val core: EliNerCore = EliNerCore(),
    override val context: RuntimeContext,
) : EliNerRuntimeApi {

    private val lifecycleManager = LifecycleManager()
    override val state = lifecycleManager.state

    override val services = ServiceRegistry()
    override val modules = ModuleLoader(core, context.logger)

    /** Forwards Core Foundation's error stream into the Logger for the lifetime of a running engine. */
    private var errorForwardingJob: Job? = null

    init {
        // Every Foundation Service becomes discoverable by contract type —
        // this is the "todos los Foundation Services deberán registrarse
        // aquí" requirement. Each service still received its own
        // dependencies via constructor injection when RuntimeContext was
        // built; this registration is purely for *discovery*, not wiring.
        services.register<Logger>(context.logger)
        services.register<Configuration>(context.configuration)
        services.register<Resources>(context.resources)
        services.register<EventBus>(context.events)
        services.register<TimeProvider>(context.timeProvider)
        services.register<CapabilityProvider>(context.capabilityProvider)
        services.register<PerformanceProfileProvider>(context.performanceProfileProvider)
        services.register<TaskExecutor>(context.threadManager)
    }

    override fun initialize(): Boolean {
        if (!moveTo(RuntimeState.INITIALIZING)) return false
        context.logger.info("EliNerRuntime", "Initializing EliNer Runtime...")

        if (!core.initialize() || !core.start()) {
            context.logger.critical("EliNerRuntime", "Core Foundation failed to initialize/start.")
            moveTo(RuntimeState.FAILED)
            return false
        }

        val succeeded = moveTo(RuntimeState.RUNNING)
        if (succeeded) {
            errorForwardingJob = context.threadManager.scopeFor(ExecutionLane.BACKGROUND).launch {
                core.errors.collect { error -> context.logger.log(error) }
            }
            context.logger.info(
                "EliNerRuntime",
                "EliNer Runtime running. Services registered: ${services.size()}. " +
                    "Modules registered: ${core.snapshot().registeredModuleCount}.",
            )
        }
        return succeeded
    }

    override fun pause(): Boolean {
        val succeeded = moveTo(RuntimeState.PAUSED)
        if (succeeded) {
            core.pause()
            context.logger.info("EliNerRuntime", "EliNer Runtime paused.")
        }
        return succeeded
    }

    override fun resume(): Boolean {
        val succeeded = moveTo(RuntimeState.RUNNING)
        if (succeeded) {
            core.resume()
            context.logger.info("EliNerRuntime", "EliNer Runtime resumed.")
        }
        return succeeded
    }

    /**
     * Moves the runtime to [RuntimeState.STOPPING], stops Core Foundation,
     * cancels error forwarding, then to [RuntimeState.STOPPED] — from which
     * [initialize] can legally run again (`STOPPED -> INITIALIZING` is a
     * valid transition, see [RuntimeState]).
     *
     * **Full audit fix (closes the A-1 lifecycle bug referenced in
     * [com.yeivikas.olyze.eliner.modules.midi.MidiFoundationModule]'s own
     * `start()`/`stop()` doc comment):** this used to also call
     * `context.threadManager.shutdown()`. That was wrong for the same
     * reason the MIDI module's own doc explains isn't a problem for
     * *it*: [context] — and the [com.yeivikas.olyze.eliner.services.TaskExecutor]
     * inside it — is handed to [EliNerRuntime] via its constructor, not
     * created and exclusively owned by it (see [createDefaultRuntimeContext],
     * which anyone can call independently of this class). Shutting down a
     * dependency you were only lent, from your own restartable [shutdown],
     * is exactly the asymmetry that made the old behavior break the
     * `STOPPED -> INITIALIZING` transition this class's own state table
     * declares legal: [scopeFor][com.yeivikas.olyze.eliner.services.ThreadManager.scopeFor]
     * throws once shut down, so a second [initialize] would fail the
     * instant anything tried to use a lane.
     *
     * Ownership, stated explicitly so this doesn't regress: whoever calls
     * [createDefaultRuntimeContext] (or otherwise constructs a
     * [RuntimeContext]) owns that context's [RuntimeContext.threadManager]
     * and is responsible for calling
     * [TaskExecutor.shutdown][com.yeivikas.olyze.eliner.services.TaskExecutor.shutdown]
     * on it themselves, once — when the *entire context* (not just one
     * [EliNerRuntime] instance built on top of it) is being discarded for
     * good. [EliNerRuntime.shutdown] only ever tears down what it
     * exclusively owns ([core], [errorForwardingJob]), so
     * `shutdown() -> initialize()` on the same instance is now genuinely
     * safe, not just declared safe by the state table.
     */
    override fun shutdown(): Boolean {
        if (!moveTo(RuntimeState.STOPPING)) return false
        context.logger.info("EliNerRuntime", "Shutting down EliNer Runtime...")

        errorForwardingJob?.cancel()
        errorForwardingJob = null
        core.stop()

        val succeeded = moveTo(RuntimeState.STOPPED)
        if (succeeded) {
            context.logger.info("EliNerRuntime", "EliNer Runtime stopped.")
        }
        return succeeded
    }

    /**
     * Wraps [LifecycleManager.transitionTo] with synchronous event
     * publishing. This is the answer to a gap left open in Fase 2 (ADR
     * 0005): publishing a state-change event was deferred because nothing
     * owned a [kotlinx.coroutines.CoroutineScope] to *subscribe* with. It
     * turns out no subscription is needed for [EliNerRuntime]'s own
     * transitions — [EliNerRuntime] is the one calling [transitionTo], so
     * it can publish [RuntimeStateChangedEvent] synchronously, right here,
     * immediately after a successful transition.
     */
    private fun moveTo(target: RuntimeState): Boolean {
        val previous = lifecycleManager.state.value
        val succeeded = lifecycleManager.transitionTo(target)
        if (succeeded) {
            context.events.publish(RuntimeStateChangedEvent(previous, target))
        }
        return succeeded
    }
}
