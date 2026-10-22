package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun TapTempo.tapAt(vararg times: Long): Float? {
        var r: Float? = null
        for (t in times) r = tap(t)
        return r
    }

    // ── Básico ───────────────────────────────────────────────────────────────

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

    @Test fun `slow tempos are measurable - 20 BPM is 3000 ms between taps`() {
        val t = TapTempo()
        t.tap(0L)
        assertEquals(20.0f, t.tap(3_000L)!!, 0.0f)
    }

    @Test fun `result is clamped to the engine range`() {
        assertEquals(300.0f, TapTempo(minIntervalMs = 1L).tapEvery(50L, 4)!!, 0.0f)       // 1200 BPM → 300
        assertEquals(20.0f, TapTempo(resetAfterMs = 60_000L).tapEvery(10_000L, 3)!!, 0.0f) // 6 BPM → 20
    }

    // ── Estimador: usa TODOS los toques ──────────────────────────────────────

    @Test fun `the estimate uses the middle taps and not only the first and last`() {
        // Con extremos 0 y 2000 un promedio de intervalos daría exactamente 120,0; la regresión sobre los
        // cinco toques da 120,5 (60000 / 498).
        assertEquals(120.5f, TapTempo().tapAt(0L, 510L, 1000L, 1490L, 2000L)!!, 0.0f)
    }

    @Test fun `one sloppy tap does not drag the tempo away`() {
        // Intervalos de 500 ms con UN golpe retrasado 150 ms: se descarta como atípico y sigue en 120.
        assertEquals(120.0f, TapTempo().tapAt(0L, 500L, 1000L, 1650L, 2000L, 2500L, 3000L, 3500L)!!, 0.05f)
    }

    @Test fun `only the latest taps count, so the tempo can change mid-measurement`() {
        val t = TapTempo(maxTaps = 5)
        t.tapEvery(600L, 5)                                   // 100 BPM
        var last: Float? = null
        for (i in 1..5) last = t.tap(2_400L + i * 400L)       // luego 150 BPM
        assertEquals(150.0f, last!!, 0.0f)
    }

    // ── Pausas, rebotes y errores humanos ────────────────────────────────────

    @Test fun `a long pause starts a new measurement`() {
        val t = TapTempo()
        t.tapEvery(500L, 4)                                   // 120 BPM
        assertNull("tras una pausa la primera pulsación no tiene tempo", t.tap(20_000L))
        assertEquals(1, t.tapCount)
        assertEquals(150.0f, t.tap(20_400L)!!, 0.0f)
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

    @Test fun `a gap beyond 1_8 times the interval restarts the measurement and keeps the old tempo untouched`() {
        val t = TapTempo()
        t.tapEvery(500L, 4)                                   // 120 BPM, último toque en 1500
        // Hueco de 1200 ms (2,4× el intervalo): un compás saltado o un cambio de tempo.
        assertNull("el toque que rompe el ritmo no produce un tempo disparatado", t.tap(1_500L + 1_200L))
        assertEquals("empieza una medición nueva con ese toque", 1, t.tapCount)
        // Dos toques más a 400 ms: el tempo nuevo se mide limpio.
        assertEquals(150.0f, t.tap(2_700L + 400L)!!, 0.0f)
    }

    @Test fun `a moderately longer gap is accepted as a normal tap`() {
        val t = TapTempo()
        t.tapEvery(500L, 4)
        val before = t.tapCount
        assertTrue(t.tap(1_500L + 750L) != null)              // 1,5× el intervalo
        assertEquals(before + 1, t.tapCount)
    }

    @Test fun `a double tap under 0_4 times the interval is ignored as a flam`() {
        val t = TapTempo()
        t.tapEvery(500L, 4)
        val before = t.lastBpm
        val count = t.tapCount
        assertEquals(before, t.tap(1_500L + 150L))            // 0,3× el intervalo
        assertEquals(count, t.tapCount)
    }

    @Test fun `with only two taps there is no interval to compare against yet`() {
        val t = TapTempo()
        t.tapEvery(500L, 2)
        assertTrue(t.tap(500L + 1_300L) != null)              // 2,6× pero aún sin reglas adaptativas
        assertEquals(3, t.tapCount)
    }

    // ── Fiabilidad y estado ──────────────────────────────────────────────────

    @Test fun `steady taps become stable after four`() {
        val t = TapTempo()
        t.tapEvery(500L, 3)
        assertFalse("con 3 toques la estimación es provisional", t.isStable)
        t.tap(1_500L)
        assertTrue(t.isStable)
    }

    @Test fun `erratic taps are never stable`() {
        val t = TapTempo()
        t.tapAt(0L, 300L, 1_000L, 1_450L, 2_100L, 2_450L, 3_250L, 3_650L)
        assertFalse(t.isStable)
    }

    @Test fun `status mirrors the measurement`() {
        val t = TapTempo()
        assertEquals(TapStatus.IDLE, t.status())
        t.tap(0L)
        assertEquals(TapStatus(count = 1, bpm = null, isStable = false), t.status())
        t.tap(500L)
        assertEquals(TapStatus(count = 2, bpm = 120.0f, isStable = false), t.status())
    }

    @Test fun `reset clears the measurement`() {
        val t = TapTempo()
        t.tapEvery(500L, 5)
        t.reset()
        assertNull(t.lastBpm)
        assertEquals(0, t.tapCount)
        assertFalse(t.isStable)
        assertEquals(TapStatus.IDLE, t.status())
        assertTrue(t.tap(0L) == null)
    }

    // ── Precisión frente al promedio de intervalos ───────────────────────────

    @Test fun `regression is more accurate than averaging the intervals on jittery human taps`() {
        // 300 mediciones de 8 toques a 120 BPM con temblor uniforme de ±35 ms (σ ≈ 20 ms, el de una persona),
        // con un generador congruencial fijo: el resultado es siempre el mismo.
        var state = 20_241_014L
        fun jitter(): Double {
            state = (state * 1_103_515_245L + 12_345L) and 0x7fffffffL
            return ((state % 2001L) - 1000L) / 1000.0 * 35.0
        }
        var errorRegression = 0.0
        var errorIntervals = 0.0
        repeat(300) {
            val times = LongArray(8) { i -> Math.floor(i * 500.0 + jitter() + 0.5).toLong() }
            val tapper = TapTempo()
            var bpm: Float? = null
            for (t in times) bpm = tapper.tap(t)
            errorRegression += Math.abs(bpm!! - 120.0)
            // Promedio de intervalos consecutivos = (último − primero) / (n − 1): usa solo los dos extremos.
            errorIntervals += Math.abs(60_000.0 / ((times.last() - times.first()) / 7.0) - 120.0)
        }
        assertTrue("regresión $errorRegression frente a promedio $errorIntervals", errorRegression < errorIntervals)
    }

    // ── Toques aceptados y progreso (ADR 0033) ───────────────────────────────

    @Test fun `accepted taps counts only the taps that were not ignored`() {
        val t = TapTempo()
        assertEquals(0L, t.acceptedTaps)
        t.tap(0L); t.tap(500L)
        assertEquals(2L, t.acceptedTaps)
        t.tap(540L)                                           // rebote: ignorado
        assertEquals(2L, t.acceptedTaps)
        t.tap(1_000L)
        assertEquals(3L, t.acceptedTaps)
    }

    @Test fun `a tap that restarts the measurement is still an accepted tap`() {
        val t = TapTempo()
        t.tapEvery(500L, 4)
        val before = t.acceptedTaps
        t.tap(1_500L + 1_200L)                                // hueco: reinicia con este toque
        assertEquals(before + 1, t.acceptedTaps)
        assertEquals(1, t.tapCount)
    }

    @Test fun `reset does not rewind the accepted taps counter`() {
        val t = TapTempo()
        t.tapEvery(500L, 3)
        t.reset()
        assertEquals(3L, t.acceptedTaps)
    }

    @Test fun `progress goes from zero to the ideal number of taps`() {
        assertEquals(0f, TapStatus.IDLE.progress, 0f)
        assertEquals(0.5f, TapStatus(4, 120f, true).progress, 0f)
        assertEquals(1f, TapStatus(TapStatus.IDEAL_TAPS, 120f, true).progress, 0f)
        assertEquals(1f, TapStatus(40, 120f, true).progress, 0f)
    }

    // ── Conversión de pulsos por minuto a negras por minuto ─────────────────

    @Test fun `with a quarter note pulse the tapped rate is the engine tempo`() {
        assertEquals(120f, quarterNoteBpmFor(120f, BeatUnit.QUARTER), 0f)
        assertEquals(129.6f, quarterNoteBpmFor(129.6f, BeatUnit.QUARTER), 1e-4f)
    }

    @Test fun `eighth note pulses count half a quarter each`() {
        assertEquals(120f, quarterNoteBpmFor(240f, BeatUnit.EIGHTH), 1e-4f)      // 6/8: 240 corcheas/min = 120 negras/min
        assertEquals(64.8f, quarterNoteBpmFor(129.6f, BeatUnit.EIGHTH), 1e-4f)
    }

    @Test fun `sixteenth and half note pulses convert in the same way`() {
        assertEquals(75f, quarterNoteBpmFor(300f, BeatUnit.SIXTEENTH), 1e-4f)
        assertEquals(120f, quarterNoteBpmFor(60f, BeatUnit.HALF), 1e-4f)
    }

    @Test fun `the converted tempo is limited to the engine range`() {
        assertEquals(20f, quarterNoteBpmFor(30f, BeatUnit.EIGHTH), 0f)            // 15 negras/min → 20
        assertEquals(300f, quarterNoteBpmFor(300f, BeatUnit.HALF), 0f)            // 600 negras/min → 300
    }

    @Test fun `the converted tempo is rounded to one decimal`() {
        // 123,5 corcheas/min = 61,75 negras/min (exacto en binario) → 61,8: el empate se redondea hacia arriba
        assertEquals(61.8f, quarterNoteBpmFor(123.5f, BeatUnit.EIGHTH), 1e-4f)
        assertEquals(61.7f, quarterNoteBpmFor(123.4f, BeatUnit.EIGHTH), 1e-4f)   // 61,70 → 61,7
    }

    @Test fun `tapping a six eight pulse reproduces the pulse rate the user heard`() {
        // 8 toques de corchea a 240 por minuto (250 ms) → tempo del motor 120 negras/min → pulsos a 240/min.
        val tapped = TapTempo().tapEvery(250L, 8)!!
        val engineBpm = quarterNoteBpmFor(tapped, BeatUnit.EIGHTH)
        assertEquals(120f, engineBpm, 1e-4f)
        assertEquals(240f, engineBpm / BeatUnit.EIGHTH.quartersPerPulse.toFloat(), 1e-3f)
    }
}
