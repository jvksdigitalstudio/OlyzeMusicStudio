package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La compensación de latencia del indicador (ADR 0037): lógica pura con reloj manual, sin esperar. */
class PulseDelayLineTest {

    private var now = 0L
    private val line = PulseDelayLine { now }

    private fun pulse(seq: Long) = BeatPulse(sequence = seq, beatInBar = (seq % 4).toInt(), isRunning = true)
    private fun ms(v: Long) = v * 1_000_000L

    @Test fun `without latency a pulse comes out in the same cycle`() {
        line.offer(pulse(1))
        assertEquals(listOf(pulse(1)), line.drain(null))
    }

    @Test fun `unknown, zero, negative and non-finite latencies do not delay`() {
        for (bad in listOf<Float?>(null, 0f, -5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val l = PulseDelayLine { now }
            l.offer(pulse(1))
            assertEquals("latencia $bad", listOf(pulse(1)), l.drain(bad))
        }
    }

    @Test fun `a pulse is held until the latency has elapsed`() {
        line.offer(pulse(1))
        now = ms(49)
        assertTrue(line.drain(50f).isEmpty())
        now = ms(50)
        assertEquals(listOf(pulse(1)), line.drain(50f))
    }

    @Test fun `a released pulse is not released twice`() {
        line.offer(pulse(1))
        now = ms(100)
        assertEquals(1, line.drain(50f).size)
        assertTrue(line.drain(50f).isEmpty())
    }

    @Test fun `pulses come out in order and each one waits for its own time`() {
        line.offer(pulse(1))
        now = ms(30); line.offer(pulse(2))
        now = ms(60); line.offer(pulse(3))
        now = ms(55)
        assertEquals(listOf(pulse(1)), line.drain(50f))          // el 2 observado a 30 ms sale a los 80
        now = ms(85)
        assertEquals(listOf(pulse(2)), line.drain(50f))
        now = ms(120)
        assertEquals(listOf(pulse(3)), line.drain(50f))
    }

    @Test fun `several pulses that are ready at once come out together and in order`() {
        line.offer(pulse(1)); line.offer(pulse(2)); line.offer(pulse(3))
        now = ms(500)
        assertEquals(listOf(pulse(1), pulse(2), pulse(3)), line.drain(50f))
    }

    @Test fun `a latency change applies to the pulses still waiting`() {
        line.offer(pulse(1))
        now = ms(30)
        assertTrue(line.drain(50f).isEmpty())
        assertEquals(listOf(pulse(1)), line.drain(20f))          // la medida bajó: ya cumplió
    }

    @Test fun `an absurd latency is capped so the indicator cannot freeze`() {
        line.offer(pulse(1))
        now = ms(1_000)
        assertEquals(listOf(pulse(1)), line.drain(1_000_000f))
    }

    @Test fun `an overgrown backlog is flushed at once instead of waiting`() {
        repeat(PulseDelayLine.MAX_PENDING + 1) { line.offer(pulse(it.toLong())) }
        assertEquals(PulseDelayLine.MAX_PENDING + 1, line.drain(500f).size)
        assertTrue(line.drain(500f).isEmpty())
    }

    @Test fun `clear drops what is held`() {
        line.offer(pulse(1))
        line.clear()
        now = ms(1_000)
        assertTrue(line.drain(50f).isEmpty())
    }

    @Test fun `draining an empty line is a no-op`() {
        assertTrue(line.drain(50f).isEmpty())
    }
}
