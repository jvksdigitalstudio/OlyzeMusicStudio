package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.MidiEvent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * §12: the queue that carries [MidiEvent]s from Android's MIDI delivery
 * threads to [MidiRouter]. Design decision, written out because §12
 * explicitly demands one:
 *
 * **Why not a hand-rolled lock-free ring buffer** (the pattern this
 * project already uses correctly in the native audio engine —
 * `eliner/include/eliner/core/CommandQueue.h`): that queue is SPSC — one
 * audio thread, one control thread, period. This one is genuinely
 * **MPSC**: §37 requires multiple simultaneous input devices, and Android
 * delivers each connected device's/port's MIDI data on its own callback
 * thread — so there can be several concurrent producers feeding one
 * consumer ([MidiRouter], draining on
 * [com.yeivikas.olyze.eliner.services.ExecutionLane.DSP]). A correct
 * lock-free MPSC ring buffer needs CAS-based slot claiming across
 * producers plus correct wraparound handling — meaningfully harder to
 * get right than the SPSC case, and this project has **no way to compile
 * or stress-test it in this environment** (no NDK/network here — see the
 * hardening-phase reports). §12 is explicit: "NO utilizar 'lock-free'
 * simplemente como palabra de marketing" — shipping an unverified
 * lock-free MPSC I can't test is exactly that.
 *
 * **Why [ArrayBlockingQueue] is still the right, honest choice here**:
 * it's a battle-tested JDK primitive (single internal lock + condition
 * variables), genuinely bounded (§12: "evitar... crecimiento ilimitado"),
 * and thread-safe for multiple producers out of the box. Its lock hold
 * time is a handful of array/pointer operations — negligible next to
 * realistic MIDI event rates (even a fast controller or a drum roll
 * rarely exceeds a few kHz of messages) and, critically, this lock is
 * held on Android's MIDI Binder callback thread, **not** the native audio
 * render callback — the actual realtime-critical path this project
 * guards most carefully (see `docs/adr/0011-fase7-dsp-graph-real.md`).
 * A brief JDK lock on a Binder thread is standard, unremarkable Android
 * practice; a lock on the audio render thread would not be.
 *
 * **Overflow policy**: [offer] never blocks — a full queue means the
 * newest event is dropped and [droppedCount] increments (visible via
 * [MidiMetricsSnapshot.eventsDropped]). Dropping newest-on-full, not
 * oldest, matches CommandQueue.h's own documented choice for the same
 * reason: an already-queued NOTE_OFF must never be evicted in favor of a
 * newer message, or a note could get stuck on.
 *
 * [offer] (the producer side, called from Android's Binder callback
 * threads) must never block — that's the realtime-adjacent constraint
 * this whole design serves. [takeBlocking] (the consumer side) is the
 * opposite: [MidiRouter] runs it in a loop on its own dedicated thread
 * ([com.yeivikas.olyze.eliner.services.ExecutionLane.DSP]) with nothing
 * else to do while idle, so blocking there — instead of busy-polling — is
 * simply the correct, efficient producer-consumer pattern; it costs
 * nothing and wastes no CPU.
 */
class MidiEventQueue(capacity: Int = DEFAULT_CAPACITY) {
    private val queue = ArrayBlockingQueue<MidiEvent>(capacity)
    private val capacityInt = capacity

    private val _droppedCount = AtomicLong(0)
    val droppedCount: Long get() = _droppedCount.get()

    /** Producer side — called from Android's MIDI callback thread(s). Never blocks. */
    fun offer(event: MidiEvent): Boolean {
        val accepted = queue.offer(event)
        if (!accepted) _droppedCount.incrementAndGet()
        return accepted
    }

    /**
     * Consumer side — [MidiRouter]'s draining loop only. Blocks up to
     * [timeoutMillis] waiting for the next event; returns `null` on
     * timeout (used purely so the loop can periodically check for
     * shutdown — see [MidiRouter] — not as a polling interval for
     * throughput, which stays event-driven).
     */
    fun takeBlocking(timeoutMillis: Long): MidiEvent? =
        queue.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)

    /**
     * Consumer side — drains everything else currently queued into
     * [destination] without blocking, for batching after [takeBlocking]
     * returns the first event of a burst. Returns how many were moved.
     */
    fun drainTo(destination: MutableCollection<MidiEvent>): Int = queue.drainTo(destination)

    fun utilizationPercent(): Float = (queue.size.toFloat() / capacityInt) * 100f

    companion object {
        /** Generous relative to realistic MIDI burst rates — see class doc. */
        const val DEFAULT_CAPACITY: Int = 1024
    }
}
