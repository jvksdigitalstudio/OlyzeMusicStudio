package com.yeivikas.olyze.eliner.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Internal event bus — this is the mechanism that lets modules communicate
 * (e.g. a future Audio module and a future MIDI module) without importing
 * each other directly, per the architecture rule: no direct calls between
 * peer modules, only interfaces/contracts/events.
 *
 * Deliberately generic: [EventBus] only knows about [EliNerEvent], the
 * marker interface. It has zero imports from `eliner.core`, `eliner.
 * modules`, or any other package — any future module can publish/subscribe
 * without [EventBus] itself changing.
 *
 * `replay = 0`: subscribers only see events published after they start
 * collecting, not a backlog. A future module joining late is not expected
 * to replay history through the bus — if that's ever needed for a specific
 * event type, that's a concern for whoever defines that event, not for the
 * bus itself.
 */
class EventBus {
    private val _events = MutableSharedFlow<EliNerEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /** Every event published, of every type. Most callers want [subscribe] instead. */
    val events: SharedFlow<EliNerEvent> = _events.asSharedFlow()

    /** Publishes [event] to every current subscriber. Never suspends, never throws. */
    fun publish(event: EliNerEvent) {
        _events.tryEmit(event)
    }

    /**
     * A [Flow] filtered to only events of type [T] — a filtered *view* over
     * the same underlying hot stream, not a second independent bus. Typed
     * as [Flow], not [SharedFlow]: `filterIsInstance` can't preserve the
     * "hot, replayable" guarantees a real `SharedFlow` makes, so this is
     * honest about being a cold filter over a hot source, not itself hot.
     */
    inline fun <reified T : EliNerEvent> subscribe(): Flow<T> = events.filterIsInstance<T>()
}
