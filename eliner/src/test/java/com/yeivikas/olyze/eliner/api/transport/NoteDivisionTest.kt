package com.yeivikas.olyze.eliner.api.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDivisionTest {

    @Test fun `dotted divisions are one and a half times the base`() {
        assertEquals(NoteDivision.QUARTER.beats * 1.5f, NoteDivision.QUARTER_DOTTED.beats, 1e-6f)
        assertEquals(NoteDivision.EIGHTH.beats * 1.5f, NoteDivision.EIGHTH_DOTTED.beats, 1e-6f)
        assertEquals(NoteDivision.SIXTEENTH.beats * 1.5f, NoteDivision.SIXTEENTH_DOTTED.beats, 1e-6f)
    }

    @Test fun `triplets are two thirds of the base`() {
        assertEquals(NoteDivision.QUARTER.beats * 2f / 3f, NoteDivision.QUARTER_TRIPLET.beats, 1e-6f)
        assertEquals(NoteDivision.EIGHTH.beats * 2f / 3f, NoteDivision.EIGHTH_TRIPLET.beats, 1e-6f)
        assertEquals(NoteDivision.SIXTEENTH.beats * 2f / 3f, NoteDivision.SIXTEENTH_TRIPLET.beats, 1e-6f)
    }

    @Test fun `each plain division halves the previous one`() {
        assertEquals(NoteDivision.WHOLE.beats / 2f, NoteDivision.HALF.beats, 0f)
        assertEquals(NoteDivision.HALF.beats / 2f, NoteDivision.QUARTER.beats, 0f)
        assertEquals(NoteDivision.QUARTER.beats / 2f, NoteDivision.EIGHTH.beats, 0f)
        assertEquals(NoteDivision.EIGHTH.beats / 2f, NoteDivision.SIXTEENTH.beats, 0f)
    }

    @Test fun `the default division is the dotted eighth, 0_375 s at 120 bpm`() {
        assertEquals(0.75f, NoteDivision.EIGHTH_DOTTED.beats, 0f)
        assertEquals(0.375, 60.0 / 120.0 * NoteDivision.EIGHTH_DOTTED.beats, 1e-9)
    }

    @Test fun `every division fits the range the native engine accepts`() {
        // TempoSync/AudioEngine::setDelayTempoSync acepta 0.0625 – 16 pulsos
        for (d in NoteDivision.values()) assertTrue("${d.name} fuera de rango", d.beats in 0.0625f..16f)
    }

    @Test fun `divisions are all distinct`() {
        assertEquals(NoteDivision.values().size, NoteDivision.values().map { it.beats }.toSet().size)
    }
}
