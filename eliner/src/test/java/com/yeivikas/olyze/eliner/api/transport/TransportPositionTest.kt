package com.yeivikas.olyze.eliner.api.transport

import com.yeivikas.olyze.eliner.api.transport.TransportPosition.BarBeatTick
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Conversión posición → compás | pulso | tick (ADR 0036). Los casos son los de `test_position.cpp`, de modo que
 * Kotlin y C++ dan lo mismo; si uno cambia, falla el otro.
 */
class TransportPositionTest {

    @Test fun `ticks per pulse for each beat unit`() {
        assertEquals(1920L, TransportPosition.ticksPerPulse(BeatUnit.HALF.quartersPerPulse))
        assertEquals(960L, TransportPosition.ticksPerPulse(BeatUnit.QUARTER.quartersPerPulse))
        assertEquals(480L, TransportPosition.ticksPerPulse(BeatUnit.EIGHTH.quartersPerPulse))
        assertEquals(240L, TransportPosition.ticksPerPulse(BeatUnit.SIXTEENTH.quartersPerPulse))
        assertEquals(1L, TransportPosition.ticksPerPulse(0.0))
    }

    @Test fun `the origin is bar 0, beat 0, tick 0`() {
        assertEquals(BarBeatTick(0, 0, 0), TransportPosition.barBeatTickFor(0, 4, 1.0))
    }

    @Test fun `five pulses and 7 ticks in 4-4 is bar 1, beat 1, tick 7`() {
        assertEquals(BarBeatTick(1, 1, 7), TransportPosition.barBeatTickFor(4 * 960L + 960 + 7, 4, 1.0))
    }

    @Test fun `minus one tick is the last tick of the last beat of the previous bar`() {
        assertEquals(BarBeatTick(-1, 3, 959), TransportPosition.barBeatTickFor(-1, 4, 1.0))
    }

    @Test fun `minus four pulses is the start of bar minus one`() {
        assertEquals(BarBeatTick(-1, 0, 0), TransportPosition.barBeatTickFor(-4 * 960L, 4, 1.0))
    }

    @Test fun `3-8 with eighth-note pulses has three pulses per bar`() {
        assertEquals(BarBeatTick(1, 0, 0), TransportPosition.barBeatTickFor(3 * 480L, 3, 0.5))
    }

    @Test fun `a bar of zero beats is treated as one beat instead of dividing by zero`() {
        assertEquals(0, TransportPosition.barBeatTickFor(100, 0, 1.0).beat)
    }

    @Test fun `the readout counts from one for bar and beat`() {
        // Lo que enseña la UI: el primer tiempo es 1.1.000.
        val p = TransportPosition.barBeatTickFor(0, 4, 1.0)
        assertEquals("1.1.000", "%d.%d.%03d".format(p.bar + 1, p.beat + 1, p.tick))
        val q = TransportPosition.barBeatTickFor(4 * 960L + 960 + 7, 4, 1.0)
        assertEquals("2.2.007", "%d.%d.%03d".format(q.bar + 1, q.beat + 1, q.tick))
    }
}
