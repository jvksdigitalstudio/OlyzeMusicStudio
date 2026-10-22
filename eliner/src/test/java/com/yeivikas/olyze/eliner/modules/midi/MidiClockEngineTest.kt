package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.services.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §41: "state transitions". A hand-rolled fake, not a mocking library —
 *  this project has none as a dependency yet, and [TimeProvider] is a
 *  3-method interface, cheap enough to fake directly. Deterministic:
 *  advances only when the test tells it to, so tempo estimation is
 *  actually verifiable instead of flaky against wall-clock time. See
 *  [MidiStreamParserTest] for the NOT-EXECUTED disclaimer; applies here
 *  too. */
private class FakeTimeProvider(private var nowNanosValue: Long = 0L) : TimeProvider {
    override fun nowMillis(): Long = nowNanosValue / 1_000_000
    override fun nowNanos(): Long = nowNanosValue
    override fun elapsedNanosSince(startNanos: Long): Long = nowNanosValue - startNanos
    fun advanceNanos(delta: Long) { nowNanosValue += delta }
}

class MidiClockEngineTest {

    @Test
    fun `initial state is STOPPED with zero ticks`() {
        val clock = MidiClockEngine(FakeTimeProvider())
        assertEquals(MidiTransportState.STOPPED, clock.state.value)
        assertEquals(0L, clock.currentTick())
        assertEquals(0.0, clock.currentEstimatedBpm(), 0.0001)
    }

    @Test
    fun `onStart transitions to RUNNING and resets tick count`() {
        val clock = MidiClockEngine(FakeTimeProvider())
        clock.onClockTick()
        clock.onClockTick()
        assertEquals(2L, clock.currentTick())

        clock.onStart()
        assertEquals(MidiTransportState.RUNNING, clock.state.value)
        assertEquals("START resets tick position — see class doc", 0L, clock.currentTick())
    }

    @Test
    fun `onContinue transitions to RUNNING without resetting tick count`() {
        val clock = MidiClockEngine(FakeTimeProvider())
        clock.onStart()
        clock.onClockTick()
        clock.onClockTick()
        clock.onStop()
        assertEquals(2L, clock.currentTick())

        clock.onContinue()
        assertEquals(MidiTransportState.RUNNING, clock.state.value)
        assertEquals("CONTINUE must NOT reset tick position — see class doc", 2L, clock.currentTick())
    }

    @Test
    fun `onStop transitions to STOPPED`() {
        val clock = MidiClockEngine(FakeTimeProvider())
        clock.onStart()
        clock.onStop()
        assertEquals(MidiTransportState.STOPPED, clock.state.value)
    }

    @Test
    fun `onClockTick increments tick count`() {
        val clock = MidiClockEngine(FakeTimeProvider())
        repeat(24) { clock.onClockTick() } // one full quarter note's worth of ticks.
        assertEquals(24L, clock.currentTick())
    }

    @Test
    fun `tempo estimate converges to 120 BPM for evenly-spaced ticks`() {
        // 120 BPM -> one quarter note every 0.5s -> 24 ticks per 0.5s ->
        // ~20833333 ns between ticks.
        val time = FakeTimeProvider()
        val clock = MidiClockEngine(time)
        val nsPerTick = 20_833_333L
        repeat(24) {
            clock.onClockTick()
            time.advanceNanos(nsPerTick)
        }
        assertEquals(120.0, clock.currentEstimatedBpm(), 1.0)
    }

    @Test
    fun `tempo estimate stays zero until at least two ticks observed`() {
        val clock = MidiClockEngine(FakeTimeProvider())
        clock.onClockTick()
        assertEquals("only one tick — not enough data for an estimate yet", 0.0, clock.currentEstimatedBpm(), 0.0001)
    }

    @Test
    fun `redundant Stop while already stopped is a harmless no-op`() {
        val clock = MidiClockEngine(FakeTimeProvider())
        clock.onStop()
        assertEquals(MidiTransportState.STOPPED, clock.state.value)
        assertTrue("must not throw", true)
    }
}
