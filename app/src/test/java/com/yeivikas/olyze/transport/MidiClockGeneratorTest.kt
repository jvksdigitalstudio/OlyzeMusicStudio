package com.yeivikas.olyze.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lógica pura del reloj MIDI (24 PPQN). Los valores esperados son
 * `floor(60000 / bpm / 24)` calculados a mano, no derivados del código
 * bajo prueba. El bucle con `delay()` no se prueba aquí: requeriría
 * kotlinx-coroutines-test, que este proyecto aún no incluye.
 */
class MidiClockGeneratorTest {

    @Test
    fun `120 bpm gives 20 ms pulses`() {
        // 60000 / 120 = 500 ms por negra; 500 / 24 = 20.83 -> 20
        assertEquals(20L, MidiClockGenerator.pulseIntervalMs(120))
    }

    @Test
    fun `300 bpm gives 8 ms pulses`() {
        // 60000 / 300 = 200; 200 / 24 = 8.33 -> 8
        assertEquals(8L, MidiClockGenerator.pulseIntervalMs(300))
    }

    @Test
    fun `20 bpm gives 125 ms pulses`() {
        // 60000 / 20 = 3000; 3000 / 24 = 125
        assertEquals(125L, MidiClockGenerator.pulseIntervalMs(20))
    }

    @Test
    fun `interval never drops below 1 ms`() {
        // 60000 / 100000 / 24 = 0.025 -> 0 -> piso de 1
        assertEquals(1L, MidiClockGenerator.pulseIntervalMs(100_000))
    }

    @Test
    fun `pulses per quarter note is the MIDI standard 24`() {
        assertEquals(24, MidiClockGenerator.PULSES_PER_QUARTER_NOTE)
    }
}
