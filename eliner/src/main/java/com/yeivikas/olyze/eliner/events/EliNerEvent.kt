package com.yeivikas.olyze.eliner.events

/**
 * Base contract for anything published on [EventBus].
 *
 * This phase defines the mechanism, not the taxonomy — no `AudioEvent`,
 * `MidiEvent`, or `ProjectEvent` marker types are created here. Those
 * belong to the phase that implements the corresponding module, at which
 * point that module defines its own events implementing [EliNerEvent] and
 * publishes them — [EventBus] doesn't need to know those types exist in
 * advance. (See [EngineStateChangedEvent] for the one concrete example
 * this phase does need.)
 */
interface EliNerEvent
