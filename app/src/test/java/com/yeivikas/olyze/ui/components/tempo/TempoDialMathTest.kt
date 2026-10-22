package com.yeivikas.olyze.ui.components.tempo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aritmética del dial: lo que más se rompe en un control rotatorio (cruce de 360°, sentido, límites). */
class TempoDialMathTest {

    private val min = 20f
    private val max = 300f
    private val eps = 1e-3f

    @Test fun `angles follow the canvas convention - 0 is 3 o'clock and grows clockwise`() {
        assertEquals(0f, TempoDialMath.angleDeg(1f, 0f), eps)      // derecha
        assertEquals(90f, TempoDialMath.angleDeg(0f, 1f), eps)     // ABAJO (el eje Y de pantalla crece hacia abajo)
        assertEquals(180f, TempoDialMath.angleDeg(-1f, 0f), eps)   // izquierda
        assertEquals(270f, TempoDialMath.angleDeg(0f, -1f), eps)   // arriba
    }

    @Test fun `shortest delta crosses the 360 boundary without jumping`() {
        assertEquals(2f, TempoDialMath.shortestDeltaDeg(359f, 1f), eps)
        assertEquals(-2f, TempoDialMath.shortestDeltaDeg(1f, 359f), eps)
        assertEquals(30f, TempoDialMath.shortestDeltaDeg(100f, 130f), eps)
        assertEquals(-30f, TempoDialMath.shortestDeltaDeg(130f, 100f), eps)
    }

    @Test fun `shortest delta always stays within minus 180 exclusive and 180 inclusive`() {
        var a = 0f
        while (a < 360f) {
            var b = 0f
            while (b < 360f) {
                val d = TempoDialMath.shortestDeltaDeg(a, b)
                assertTrue("a=$a b=$b d=$d", d > -180f - eps && d <= 180f + eps)
                b += 17f
            }
            a += 13f
        }
    }

    @Test fun `a full clockwise turn raises the tempo by 90 BPM`() {
        assertEquals(190f, TempoDialMath.bpmAfterTurn(100f, 360f, min, max), eps)
        assertEquals(100f, TempoDialMath.bpmAfterTurn(100f, 0f, min, max), eps)
    }

    @Test fun `turning counter-clockwise lowers the tempo`() {
        assertEquals(95f, TempoDialMath.bpmAfterTurn(100f, -20f, min, max), eps)
    }

    @Test fun `tempo never leaves the engine range`() {
        assertEquals(max, TempoDialMath.bpmAfterTurn(290f, 3600f, min, max), eps)
        assertEquals(min, TempoDialMath.bpmAfterTurn(30f, -3600f, min, max), eps)
    }

    @Test fun `fraction maps the range onto 0 to 1`() {
        assertEquals(0f, TempoDialMath.fraction(20f, min, max), eps)
        assertEquals(1f, TempoDialMath.fraction(300f, min, max), eps)
        assertEquals(0.5f, TempoDialMath.fraction(160f, min, max), eps)
        assertEquals(0f, TempoDialMath.fraction(-5f, min, max), eps)
        assertEquals(1f, TempoDialMath.fraction(999f, min, max), eps)
    }

    @Test fun `thumb sweeps 300 degrees from the start angle`() {
        assertEquals(120f, TempoDialMath.thumbAngleDeg(20f, min, max), eps)
        assertEquals(420f, TempoDialMath.thumbAngleDeg(300f, min, max), eps)   // 120 + 300
        assertEquals(270f, TempoDialMath.thumbAngleDeg(160f, min, max), eps)   // arriba del todo
    }

    @Test fun `snap rounds to one decimal`() {
        assertEquals(120.1f, TempoDialMath.snap(120.06f), eps)
        assertEquals(120.0f, TempoDialMath.snap(119.96f), eps)
    }

    @Test fun `nudged steps in tenths and clamps`() {
        assertEquals(120.1f, TempoDialMath.nudged(120f, 0.1f, min, max), eps)
        assertEquals(119.9f, TempoDialMath.nudged(120f, -0.1f, min, max), eps)
        assertEquals(130f, TempoDialMath.nudged(120f, 10f, min, max), eps)
        assertEquals(max, TempoDialMath.nudged(295f, 10f, min, max), eps)
        assertEquals(min, TempoDialMath.nudged(25f, -10f, min, max), eps)
    }

    @Test fun `repeated tenth nudges do not accumulate float error`() {
        var bpm = 120f
        repeat(100) { bpm = TempoDialMath.nudged(bpm, 0.1f, min, max) }
        assertEquals(130f, bpm, eps)
    }
}
