package com.yeivikas.olyze.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Estadística del tap tempo con un reloj inyectado: sin dormir y totalmente determinista. */
class TapTempoTest {

    /** Marca [count] pulsaciones separadas [intervalMs] a partir de [start]; devuelve la última estimación. */
    private fun TapTempo.tapEvery(intervalMs: Long, count: Int, start: Long = 0L): Float? {
        var r: Float? = null
        for (i in 0 until count) r = tap(start + i * intervalMs)
        return r
    }

    @Test fun `first tap gives no tempo`() {
        val t = TapTempo()
        assertNull(t.tap(0L))
        assertEquals(1, t.tapCount)
    }

    @Test fun `two taps 500 ms apart are 120 BPM`() {
        val t = TapTempo()
        t.tap(0L)
        assertEquals(120.0f, t.tap(500L)!!, 0.0f)
    }

    @Test fun `steady taps converge on the exact tempo`() {
        assertEquals(100.0f, TapTempo().tapEvery(600L, 8)!!, 0.0f)
        assertEquals(75.0f, TapTempo().tapEvery(800L, 6)!!, 0.0f)
    }

    @Test fun `result is rounded to one decimal`() {
        // 60000 / 463 = 129.589…
        assertEquals(129.6f, TapTempo().tapEvery(463L, 4)!!, 0.0f)
    }

    @Test fun `a long pause starts a new measurement`() {
        val t = TapTempo()
        t.tapEvery(500L, 4)                       // 120 BPM
        assertNull("tras una pausa la primera pulsación no tiene tempo", t.tap(20_000L))
        assertEquals(1, t.tapCount)
        assertEquals(150.0f, t.tap(20_400L)!!, 0.0f)
    }

    @Test fun `slow tempos are measurable - 20 BPM is 3000 ms between taps`() {
        val t = TapTempo()
        t.tap(0L)
        assertEquals(20.0f, t.tap(3_000L)!!, 0.0f)
    }

    @Test fun `one sloppy tap does not drag the tempo away`() {
        val t = TapTempo()
        // Intervalos de 500 ms con UN golpe retrasado 150 ms (los dos intervalos que lo rodean: 650 y 350).
        val times = listOf(0L, 500L, 1000L, 1650L, 2000L, 2500L, 3000L, 3500L)
        var bpm: Float? = null
        for (x in times) bpm = t.tap(x)
        assertEquals("el golpe flojo se descarta como atípico: sigue en 120", 120.0f, bpm!!, 1.5f)
    }

    @Test fun `finger bounce under 100 ms is ignored`() {
        val t = TapTempo()
        t.tap(0L); t.tap(500L)
        val before = t.lastBpm
        assertEquals(before, t.tap(540L))
        assertEquals(2, t.tapCount)
    }

    @Test fun `a clock that goes backwards is ignored, not trusted`() {
        val t = TapTempo()
        t.tap(1_000L); t.tap(1_500L)
        t.tap(900L)
        assertEquals(2, t.tapCount)
        assertEquals(120.0f, t.lastBpm!!, 0.0f)
    }

    @Test fun `result is clamped to the engine range`() {
        val fast = TapTempo(minIntervalMs = 1L)
        assertEquals(300.0f, fast.tapEvery(50L, 4)!!, 0.0f)            // 1200 BPM → 300
        val slow = TapTempo(resetAfterMs = 60_000L)
        assertEquals(20.0f, slow.tapEvery(10_000L, 3)!!, 0.0f)        // 6 BPM → 20
    }

    @Test fun `only the latest taps count, so the tempo can change mid-measurement`() {
        val t = TapTempo(maxTaps = 5)
        t.tapEvery(600L, 5)                       // 100 BPM
        var last: Float? = null
        for (i in 1..5) last = t.tap(2_400L + i * 400L)   // luego 150 BPM
        assertEquals(150.0f, last!!, 0.0f)
    }

    @Test fun `reset clears the measurement`() {
        val t = TapTempo()
        t.tapEvery(500L, 3)
        t.reset()
        assertNull(t.lastBpm)
        assertEquals(0, t.tapCount)
        assertTrue(t.tap(0L) == null)
    }
}
