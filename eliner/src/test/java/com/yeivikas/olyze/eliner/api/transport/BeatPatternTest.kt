package com.yeivikas.olyze.eliner.api.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Contrato del patrón por tiempo: valores por defecto, empaquetado hacia el motor y patrones por compás. */
class BeatPatternTest {

    @Test fun `default is an accented first beat and normal beats after it`() {
        val p = BeatPattern.DEFAULT
        assertEquals(BeatLevel.ACCENT, p[0])
        for (i in 1 until BeatPattern.MAX_BEATS) assertEquals(BeatLevel.MEDIUM, p[i])
    }

    @Test fun `default packs to the same words the engine uses as its own default`() {
        // Accent (3) en el tiempo 0 y Medium (2) en el resto: 0xAAAB y 0xAAAA (ver Metronome.h).
        assertEquals(0xAAAB, BeatPattern.DEFAULT.packedLow)
        assertEquals(0xAAAA, BeatPattern.DEFAULT.packedHigh)
    }

    @Test fun `packing is a lossless round trip for any pattern`() {
        val all = BeatLevel.values()
        var seed = 12345
        repeat(200) {
            val levels = List(BeatPattern.MAX_BEATS) { seed = (seed * 1103515245 + 12345) and 0x7fffffff; all[seed % all.size] }
            val p = BeatPattern.of(levels)
            assertEquals(p, BeatPattern.fromPacked(p.packedLow, p.packedHigh))
        }
    }

    @Test fun `packed words always stay within 16 bits so the engine accepts them`() {
        val loud = BeatPattern.of(List(BeatPattern.MAX_BEATS) { BeatLevel.ACCENT })
        assertEquals(0xFFFF, loud.packedLow)
        assertEquals(0xFFFF, loud.packedHigh)
        val mute = BeatPattern.of(List(BeatPattern.MAX_BEATS) { BeatLevel.MUTE })
        assertEquals(0, mute.packedLow)
        assertEquals(0, mute.packedHigh)
    }

    @Test fun `beats 0 to 7 live in the low word and 8 to 15 in the high word`() {
        val p = BeatPattern.of(List(BeatPattern.MAX_BEATS) { BeatLevel.MUTE }).with(0, BeatLevel.ACCENT).with(8, BeatLevel.SOFT)
        assertEquals(0b11, p.packedLow)
        assertEquals(0b01, p.packedHigh)
        val q = BeatPattern.of(List(BeatPattern.MAX_BEATS) { BeatLevel.MUTE }).with(7, BeatLevel.ACCENT).with(15, BeatLevel.ACCENT)
        assertEquals(0b11 shl 14, q.packedLow)
        assertEquals(0b11 shl 14, q.packedHigh)
    }

    @Test fun `with returns a changed copy and leaves the original untouched`() {
        val p = BeatPattern.DEFAULT
        val q = p.with(2, BeatLevel.MUTE)
        assertEquals(BeatLevel.MEDIUM, p[2])
        assertEquals(BeatLevel.MUTE, q[2])
        assertNotEquals(p, q)
    }

    @Test fun `with is a no-op for an unchanged level or an out-of-range beat`() {
        val p = BeatPattern.DEFAULT
        assertSame(p, p.with(1, BeatLevel.MEDIUM))
        assertSame(p, p.with(-1, BeatLevel.MUTE))
        assertSame(p, p.with(16, BeatLevel.MUTE))
    }

    @Test fun `reading outside 0 to 15 is medium and never throws`() {
        assertEquals(BeatLevel.MEDIUM, BeatPattern.DEFAULT[-1])
        assertEquals(BeatLevel.MEDIUM, BeatPattern.DEFAULT[99])
    }

    @Test fun `of pads missing beats as medium and drops the extra ones`() {
        val short = BeatPattern.of(listOf(BeatLevel.MUTE))
        assertEquals(BeatLevel.MUTE, short[0])
        assertEquals(BeatLevel.MEDIUM, short[15])
        val long = BeatPattern.of(List(40) { BeatLevel.SOFT })
        assertEquals(BeatLevel.SOFT, long[15])
        assertEquals(BeatPattern.of(List(16) { BeatLevel.SOFT }), long)
    }

    @Test fun `simple meters get the default accent pattern`() {
        assertEquals(BeatPattern.DEFAULT, BeatPattern.defaultFor(4, BeatUnit.QUARTER))
        assertEquals(BeatPattern.DEFAULT, BeatPattern.defaultFor(3, BeatUnit.QUARTER))
        assertEquals(BeatPattern.DEFAULT, BeatPattern.defaultFor(6, BeatUnit.QUARTER))   // 6/4 es simple
        assertEquals(BeatPattern.DEFAULT, BeatPattern.defaultFor(3, BeatUnit.EIGHTH))    // 3/8: un solo grupo
        assertEquals(BeatPattern.DEFAULT, BeatPattern.defaultFor(7, BeatUnit.EIGHTH))    // 7/8: sin grupos de 3
    }

    @Test fun `compound meters group the beats in threes`() {
        val six = BeatPattern.defaultFor(6, BeatUnit.EIGHTH)
        assertEquals(
            listOf(BeatLevel.ACCENT, BeatLevel.SOFT, BeatLevel.SOFT, BeatLevel.MEDIUM, BeatLevel.SOFT, BeatLevel.SOFT),
            (0 until 6).map { six[it] },
        )
        val twelve = BeatPattern.defaultFor(12, BeatUnit.EIGHTH)
        assertEquals(BeatLevel.ACCENT, twelve[0])
        assertEquals(listOf(3, 6, 9), (0 until 12).filter { twelve[it] == BeatLevel.MEDIUM })
        assertEquals(BeatLevel.SOFT, twelve[11])
        assertEquals(BeatPattern.defaultFor(12, BeatUnit.EIGHTH), BeatPattern.defaultFor(12, BeatUnit.SIXTEENTH))
    }

    @Test fun `beats per bar of a unit are covered by the pattern length`() {
        for (unit in BeatUnit.values()) for (beats in 1..BeatPattern.MAX_BEATS) {
            // Nunca lanza y siempre produce un patrón empaquetable en 16 bits por palabra.
            val p = BeatPattern.defaultFor(beats, unit)
            assertEquals(true, p.packedLow in 0..0xFFFF && p.packedHigh in 0..0xFFFF)
        }
    }

    @Test fun `beat unit duration in quarters follows the denominator`() {
        assertEquals(2.0, BeatUnit.HALF.quartersPerPulse, 0.0)
        assertEquals(1.0, BeatUnit.QUARTER.quartersPerPulse, 0.0)
        assertEquals(0.5, BeatUnit.EIGHTH.quartersPerPulse, 0.0)
        assertEquals(0.25, BeatUnit.SIXTEENTH.quartersPerPulse, 0.0)
    }
}
