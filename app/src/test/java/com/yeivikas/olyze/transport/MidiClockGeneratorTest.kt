package com.yeivikas.olyze.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Lógica pura del reloj MIDI (24 PPQN): el intervalo exacto y la planificación de las horas objetivo. Los
 * valores esperados son `60e9 / bpm / 24` calculados a mano, no derivados del código bajo prueba. El bucle con
 * `delay()` no se prueba aquí (requeriría kotlinx-coroutines-test, que este proyecto aún no incluye): por eso
 * la planificación vive en [PulseSchedule], que recibe la hora como argumento.
 */
class MidiClockGeneratorTest {

    private val ms = 1_000_000L

    private fun assertNear(expected: Long, actual: Long, toleranceNanos: Long = 1L) =
        assertTrue("esperado $expected ± $toleranceNanos, fue $actual", abs(expected - actual) <= toleranceNanos)

    // ── Intervalo exacto ─────────────────────────────────────────────────────

    @Test fun `120 bpm gives an exact pulse of 20 and a third milliseconds`() {
        // 60e9 / 120 / 24 = 20 833 333,33 ns: antes se truncaba a 20 ms y el reloj corría a 125 BPM.
        assertEquals(20_833_333.333, MidiClockGenerator.pulseIntervalNanos(120f), 0.001)
    }

    @Test fun `300 bpm gives 8333333 ns pulses`() {
        assertEquals(8_333_333.333, MidiClockGenerator.pulseIntervalNanos(300f), 0.001)
    }

    @Test fun `20 bpm gives 125 ms pulses`() {
        assertEquals(125_000_000.0, MidiClockGenerator.pulseIntervalNanos(20f), 0.0)
    }

    @Test fun `fractional tempo is supported`() {
        // 60e9 / 133,5 / 24 = 18 726 591,76 ns
        assertEquals(18_726_591.760, MidiClockGenerator.pulseIntervalNanos(133.5f), 0.001)
    }

    @Test fun `an invalid tempo is treated as 1 bpm and never gives an absurd interval`() {
        // El transporte nunca lo emite (limita a 20); si llegara, 1 BPM = 60e9 / 1 / 24 = 2,5 s.
        assertEquals(2_500_000_000.0, MidiClockGenerator.pulseIntervalNanos(0f), 0.0)
        assertEquals(2_500_000_000.0, MidiClockGenerator.pulseIntervalNanos(-5f), 0.0)
        assertEquals(2_500_000_000.0, MidiClockGenerator.pulseIntervalNanos(Float.NaN), 0.0)
    }

    @Test fun `pulses per quarter note is the MIDI standard 24`() {
        assertEquals(24, MidiClockGenerator.PULSES_PER_QUARTER_NOTE)
    }

    @Test fun `waits are rounded up to whole milliseconds so the pulse never fires early`() {
        assertEquals(0L, MidiClockGenerator.ceilToMillis(0L))
        assertEquals(1L, MidiClockGenerator.ceilToMillis(1L))
        assertEquals(1L, MidiClockGenerator.ceilToMillis(ms))
        assertEquals(2L, MidiClockGenerator.ceilToMillis(ms + 1L))
        assertEquals(21L, MidiClockGenerator.ceilToMillis(20_833_333L))
    }

    // ── Planificación ────────────────────────────────────────────────────────

    @Test fun `waiting exactly as asked lands every pulse on the ideal grid`() {
        // 8 negras a 120 BPM = 192 pulsos = 4 s exactos, sin acumular redondeo.
        val schedule = PulseSchedule(startNanos = 0L)
        var now = 0L
        repeat(8 * MidiClockGenerator.PULSES_PER_QUARTER_NOTE) { now += schedule.nanosUntilNextPulse(now, 120f) }
        assertNear(4_000_000_000L, now)
    }

    @Test fun `rounding each wait up to a millisecond does not accumulate drift`() {
        // 60 negras a 120 BPM = 30 s. Despertando siempre con el redondeo por exceso de delay(), la hora real
        // se queda a menos de 1 ms de la ideal. Con el esquema anterior (20 ms fijos por pulso) eran 28,8 s.
        val schedule = PulseSchedule(startNanos = 0L)
        var now = 0L
        repeat(60 * MidiClockGenerator.PULSES_PER_QUARTER_NOTE) {
            val wait = schedule.nanosUntilNextPulse(now, 120f)
            now += MidiClockGenerator.ceilToMillis(wait) * ms
        }
        assertNear(30_000_000_000L, now, toleranceNanos = ms)
    }

    @Test fun `a start time that is not zero does not matter`() {
        val start = 7_000_000_000_000L
        val schedule = PulseSchedule(startNanos = start)
        var now = start
        repeat(24) { now += schedule.nanosUntilNextPulse(now, 120f) }
        assertNear(start + 500_000_000L, now)
    }

    @Test fun `a tempo change applies from the next pulse`() {
        val schedule = PulseSchedule(startNanos = 0L)
        var now = 0L
        val first = schedule.nanosUntilNextPulse(now, 120f)       // 20 833 333 ns
        assertNear(20_833_333L, first)
        now += first
        val second = schedule.nanosUntilNextPulse(now, 240f)      // este intervalo ya es el de 240 BPM
        assertNear(10_416_667L, second)
        now += second
        val third = schedule.nanosUntilNextPulse(now, 240f)
        assertNear(10_416_667L, third)
    }

    @Test fun `a slightly late pulse is caught up and not skipped`() {
        val schedule = PulseSchedule(startNanos = 0L)
        // El primer pulso se emite a tiempo; el segundo se pide a las 41,67 ms pero solo se atiende a las 50 ms.
        schedule.nanosUntilNextPulse(0L, 120f)
        assertEquals("ya toca: sale sin esperar", 0L, schedule.nanosUntilNextPulse(50 * ms, 120f))
        // El tercero, a las 62,5 ms, sigue en su sitio de la rejilla: no se desplazó por el retraso.
        assertNear(12_500_000L, schedule.nanosUntilNextPulse(50 * ms, 120f))
    }

    @Test fun `a long stall re-anchors instead of bursting a flood of pulses`() {
        val schedule = PulseSchedule(startNanos = 0L)
        schedule.nanosUntilNextPulse(0L, 120f)
        val stalled = 5_000_000_000L                               // app suspendida 5 s
        val wait = schedule.nanosUntilNextPulse(stalled, 120f)
        assertNear(20_833_333L, wait)                              // un intervalo entero, no 0
        // Y a partir de ahí vuelve a una cadencia normal.
        assertNear(20_833_333L, schedule.nanosUntilNextPulse(stalled + wait, 120f))
    }

    @Test fun `the wait is never negative`() {
        val schedule = PulseSchedule(startNanos = 0L)
        repeat(50) { i -> assertTrue(schedule.nanosUntilNextPulse(i * 90 * ms, 300f) >= 0L) }
    }
}
