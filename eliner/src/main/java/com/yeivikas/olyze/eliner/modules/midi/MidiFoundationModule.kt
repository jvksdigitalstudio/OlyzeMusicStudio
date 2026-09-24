package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.EliNerMidiApi
import com.yeivikas.olyze.eliner.api.MidiConsumer
import com.yeivikas.olyze.eliner.api.MidiDeviceEvent
import com.yeivikas.olyze.eliner.api.MidiDeviceInfo
import com.yeivikas.olyze.eliner.api.MidiEvent
import com.yeivikas.olyze.eliner.api.MidiMetricsSnapshot
import com.yeivikas.olyze.eliner.api.MidiParameterBinding
import com.yeivikas.olyze.eliner.core.EliNerModule
import com.yeivikas.olyze.eliner.core.EngineError
import com.yeivikas.olyze.eliner.core.EngineErrorSeverity
import com.yeivikas.olyze.eliner.core.StateMachine
import com.yeivikas.olyze.eliner.diagnostics.Logger
import com.yeivikas.olyze.eliner.events.EventBus
import com.yeivikas.olyze.eliner.services.TaskExecutor
import com.yeivikas.olyze.eliner.services.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** §24: this module's own lifecycle state — deliberately independent of
 *  [com.yeivikas.olyze.eliner.core.EngineState]/[com.yeivikas.olyze.
 *  eliner.api.RuntimeState]/`AudioEngineState`/`AudioSessionState`/
 *  `DSPState`, per the spec's explicit instruction. Four states, not the
 *  two [MidiTransportState] gets — this one needs to distinguish "never
 *  started" from "stopped after running" (a consumer asking "is MIDI
 *  ready?" cares about that distinction; MIDI transport doesn't need to). */
enum class MidiFoundationState { UNINITIALIZED, RUNNING, STOPPED, ERROR }

/**
 * §35 lifecycle + §24 state + §25 API surface, all in one class —
 * deliberately: this is the single concrete implementation of both
 * [EliNerModule] (so it CAN be registered with
 * [com.yeivikas.olyze.eliner.core.ModuleRegistry]/orchestrated by
 * [com.yeivikas.olyze.eliner.core.EliNerCore]/[com.yeivikas.olyze.eliner.
 * runtime.EliNerRuntime] once that stack is connected — see ADR
 * 0010/0012) and [EliNerMidiApi] (so it's directly usable WITHOUT that
 * stack, the same way `DeviceCapabilityManager`/`PerformanceProfileManager`
 * already are — see `MainViewModel.kt`). Splitting these into two classes
 * would mean either duplicating this wiring or one delegating to the
 * other for no real benefit; `EliNerAudioBridge` sets the same one-class
 * precedent for `EliNerAudioApi`.
 *
 * Owns: [MidiDeviceManager] (hot-plug + port I/O), [MidiRouter] (dispatch
 * + bindings), [MidiClockEngine] (transport), [MidiMetrics]. Constructed
 * with everything it needs injected — see companion [create] for the one
 * place that decides which concrete [MidiPlatformBackend] to use.
 */
class MidiFoundationModule(
    private val backend: MidiPlatformBackend,
    private val eventBus: EventBus,
    private val logger: Logger,
    taskExecutor: TaskExecutor,
    timeProvider: TimeProvider,
) : EliNerModule, EliNerMidiApi {

    override val id: String = "midi"
    override val displayName: String = "MIDI Foundation"

    private val inputQueue = MidiEventQueue()
    private val midiMetrics = MidiMetrics(inputQueue)
    private val clock = MidiClockEngine(timeProvider)
    private val deviceManager = MidiDeviceManager(backend, inputQueue, eventBus)
    private val router = MidiRouter(inputQueue, midiMetrics, clock, taskExecutor)

    private val stateMachine = StateMachine(
        initial = MidiFoundationState.UNINITIALIZED,
        isValidTransition = { from, to ->
            when (to) {
                MidiFoundationState.RUNNING -> from == MidiFoundationState.UNINITIALIZED || from == MidiFoundationState.STOPPED
                MidiFoundationState.STOPPED -> from == MidiFoundationState.RUNNING
                MidiFoundationState.ERROR -> true // an error can be reported from any state.
                MidiFoundationState.UNINITIALIZED -> false // never re-enterable — matches EngineState's own convention.
            }
        },
    )
    val state: StateFlow<MidiFoundationState> = stateMachine.state

    private val _metrics = MutableStateFlow(midiMetrics.snapshot(emptyList()))
    override val metrics: StateFlow<MidiMetricsSnapshot> = _metrics.asStateFlow()

    override val devices: StateFlow<List<MidiDeviceInfo>> = deviceManager.devices
    override val inputEvents: SharedFlow<MidiEvent> = router.allEvents
    override val deviceEvents: Flow<MidiDeviceEvent> = eventBus.subscribe()

    // §35: unlike EliNerRuntime/ThreadManager's known A-1 lifecycle bug
    // (see the hardening-phase report), start()/stop() here do NOT tear
    // down `taskExecutor` themselves — this module was handed it, not
    // given ownership of it (see [create]/constructor), so it has no
    // business shutting it down. That's exactly the asymmetry that broke
    // EliNerRuntime: it called ThreadManager.shutdown() (destroying a
    // shared resource) from ITS OWN stop(), then couldn't restart. This
    // module's stop() only tears down what it exclusively owns
    // (deviceManager, router), so start() -> stop() -> start() again is
    // genuinely safe, not just declared safe.
    override fun start() {
        if (state.value == MidiFoundationState.RUNNING) return
        try {
            router.start()
            deviceManager.start()
            refreshMetrics()
            stateMachine.transitionTo(MidiFoundationState.RUNNING)
        } catch (t: Throwable) {
            reportError("midi.start_failed", "MIDI Foundation failed to start.", t)
            stateMachine.transitionTo(MidiFoundationState.ERROR)
        }
    }

    override fun stop() {
        if (state.value != MidiFoundationState.RUNNING) return
        deviceManager.stop()
        router.stop()
        refreshMetrics()
        stateMachine.transitionTo(MidiFoundationState.STOPPED)
    }

    // §39 checklist item "sin recursos que sobrevivan incorrectamente al
    // shutdown": this was missing in the first version of this file —
    // `backend` (AndroidMidiBackend) owns a dedicated, non-daemon
    // HandlerThread (see its doc for why it needs one) that neither
    // stop() above nor anything else ever released, a genuine leak.
    // Deliberately a SEPARATE method from stop(), not folded into it:
    // stop() is documented (and actually relied on by MidiFoundationState's
    // transition table) as restartable, but a HandlerThread cannot be
    // restarted once quit — folding this into stop() would silently break
    // that restartability, the exact class of bug already found and left
    // deliberately unfixed in EliNerRuntime (A-1, hardening phase) because
    // it would have needed a real design decision. This one doesn't need
    // that: the fix is simply "have a distinct terminal method and call
    // it from the one place that's genuinely terminal" — see
    // MainViewModel.onCleared().
    override fun shutdown() {
        stop()
        backend.shutdown()
    }

    override fun send(portId: String, event: MidiEvent): Boolean {
        val sent = deviceManager.send(portId, event)
        if (sent) midiMetrics.recordSent()
        return sent
    }

    override fun registerConsumer(consumer: MidiConsumer) = router.registerConsumer(consumer)
    override fun unregisterConsumer(consumer: MidiConsumer) = router.unregisterConsumer(consumer)
    override fun registerBinding(binding: MidiParameterBinding) = router.registerBinding(binding)
    override fun unregisterBinding(id: String) {
        router.unregisterBinding(id)
    }
    override fun getBindings(): List<MidiParameterBinding> = router.getBindings()

    // ── EliNerModule ─────────────────────────────────────────────────────
    // §35: hooks for a future ModuleRegistry/EliNerCore-orchestrated
    // lifecycle. Deliberately delegate to the SAME start()/stop() the
    // standalone-usage path calls — one lifecycle implementation, two
    // ways to trigger it, not two.
    override fun onStart() = start()
    override fun onStop() = stop()

    private fun refreshMetrics() {
        _metrics.value = midiMetrics.snapshot(devices.value)
    }

    private fun reportError(code: String, message: String, cause: Throwable?) {
        logger.log(EngineError(code = code, message = message, severity = EngineErrorSeverity.ERROR, moduleId = id, cause = cause))
    }

    companion object {
        /**
         * Constructs a real, Android-backed [MidiFoundationModule]. The
         * one place in the MIDI Foundation that decides
         * [AndroidMidiBackend] is the concrete [MidiPlatformBackend] —
         * everything else depends on the interface (§26).
         */
        fun create(
            context: android.app.Application,
            eventBus: EventBus,
            logger: Logger,
            taskExecutor: TaskExecutor,
            timeProvider: TimeProvider,
        ): MidiFoundationModule = MidiFoundationModule(
            backend = AndroidMidiBackend(context, timeProvider),
            eventBus = eventBus,
            logger = logger,
            taskExecutor = taskExecutor,
            timeProvider = timeProvider,
        )
    }
}
