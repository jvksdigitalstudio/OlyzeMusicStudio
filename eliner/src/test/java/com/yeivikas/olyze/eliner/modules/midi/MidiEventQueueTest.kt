package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.MidiEvent
import com.yeivikas.olyze.eliner.api.MidiEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §41: "queue". Verifies the bounded, overflow-drops-newest behavior
 *  [MidiEventQueue]'s own doc comment commits to — the actual contract
 *  that matters for correctness (a stuck note must never happen because
 *  its NOTE_OFF got evicted). See [MidiStreamParserTest] for the
 *  NOT-EXECUTED disclaimer; applies here too. */
class MidiEventQueueTest {

    private fun clockEvent(tag: Int) = MidiEvent(
        type = MidiEventType.CLOCK,
        channel = null,
        timestampNanos = tag.toLong(), // used purely as an identity tag for these tests.
        sourcePortId = "p1",
    )

    @Test
    fun `offer within capacity always succeeds`() {
        val queue = MidiEventQueue(capacity = 4)
        repeat(4) { assertTrue(queue.offer(clockEvent(it))) }
        assertEquals(0L, queue.droppedCount)
    }

    @Test
    fun `offer beyond capacity drops the newest event, not the oldest`() {
        val queue = MidiEventQueue(capacity = 2)
        assertTrue(queue.offer(clockEvent(1)))
        assertTrue(queue.offer(clockEvent(2)))
        assertFalse("queue is full — this offer must be rejected", queue.offer(clockEvent(3)))
        assertEquals(1L, queue.droppedCount)

        val drained = mutableListOf<MidiEvent>()
        queue.drainTo(drained)
        // The already-queued events (1, 2) must still be there, in order —
        // event 3 was the one dropped, not event 1.
        assertEquals(listOf(1L, 2L), drained.map { it.timestampNanos })
    }

    @Test
    fun `drainTo empties the queue and returns the count moved`() {
        val queue = MidiEventQueue(capacity = 8)
        repeat(3) { queue.offer(clockEvent(it)) }
        val drained = mutableListOf<MidiEvent>()
        val moved = queue.drainTo(drained)
        assertEquals(3, moved)
        assertEquals(3, drained.size)

        // Queue is now empty — a second drain moves nothing.
        val secondDrain = mutableListOf<MidiEvent>()
        assertEquals(0, queue.drainTo(secondDrain))
    }

    @Test
    fun `utilizationPercent reflects how full the queue is`() {
        val queue = MidiEventQueue(capacity = 4)
        assertEquals(0f, queue.utilizationPercent(), 0.01f)
        queue.offer(clockEvent(1))
        queue.offer(clockEvent(2))
        assertEquals(50f, queue.utilizationPercent(), 0.01f)
    }

    @Test
    fun `takeBlocking returns null on timeout when nothing is queued`() {
        val queue = MidiEventQueue(capacity = 4)
        assertEquals(null, queue.takeBlocking(timeoutMillis = 10L))
    }

    @Test
    fun `takeBlocking returns the event once offered`() {
        val queue = MidiEventQueue(capacity = 4)
        queue.offer(clockEvent(42))
        val result = queue.takeBlocking(timeoutMillis = 10L)
        assertEquals(42L, result?.timestampNanos)
    }
}
