package com.yeivikas.olyze.eliner.events

import com.yeivikas.olyze.eliner.core.EngineState

/**
 * Published when [com.yeivikas.olyze.eliner.core.EliNerCore]'s state
 * changes, for anything that wants to react to lifecycle transitions via
 * the event bus instead of collecting `EliNerCore.state` directly.
 *
 * This is the only concrete event type defined in this phase — every other
 * future event (audio, MIDI, project, hardware...) belongs to the phase
 * that implements that domain. It exists now because it's genuinely
 * useful today: Logger, or any other Foundation Service, can react to
 * engine lifecycle without depending on `EliNerCore` directly, only on
 * [EventBus] + this event type.
 *
 * Note: publishing this event is NOT wired up automatically in this phase
 * — see `eliner.core`'s architecture notes for why. Whoever composes the
 * full engine is responsible for forwarding `EliNerCore.state` changes
 * onto an [EventBus] via this event, once that composition root exists.
 */
data class EngineStateChangedEvent(
    val previous: EngineState,
    val current: EngineState,
) : EliNerEvent
