package com.yeivikas.olyze.eliner.runtime

import com.yeivikas.olyze.eliner.api.RuntimeState
import com.yeivikas.olyze.eliner.events.EliNerEvent

/**
 * Published on [RuntimeContext.events] every time [EliNerRuntime]'s
 * [RuntimeState] changes.
 *
 * Per the spec ("no crear taxonomías gigantes, solo eventos que realmente
 * existan hoy"): this is the *only* event type this phase defines. Unlike
 * `eliner.events.EngineStateChangedEvent` (Fase 2, which was never wired
 * up because nothing owned a `CoroutineScope` to subscribe with),
 * [RuntimeStateChangedEvent] genuinely is published automatically — see
 * [EliNerRuntime]'s internal `moveTo` — because [EliNerRuntime] itself is
 * the one calling the transition, so publishing happens synchronously
 * right after a successful transition, with no subscription required.
 */
data class RuntimeStateChangedEvent(
    val previous: RuntimeState,
    val current: RuntimeState,
) : EliNerEvent
