package com.yeivikas.olyze.ui.components.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato de [NoteHoldTracker]: `noteOn` solo en 0→1 y `noteOff` solo en 1→0.
 * Es el arreglo del "solapamiento de doble fila" (la misma nota sostenida por dos
 * dedos no debe cortarse al soltar uno).
 */
class NoteHoldTrackerTest {

    @Test fun `first press forwards noteOn, second does not`() {
        val t = NoteHoldTracker()
        assertTrue(t.press(60))
        assertFalse(t.press(60))
    }

    @Test fun `releasing one of two holds does not forward noteOff`() {
        val t = NoteHoldTracker()
        t.press(60); t.press(60)
        assertFalse("sigue habiendo un dedo", t.release(60))
        assertTrue("el último dedo sí corta la nota", t.release(60))
        assertTrue(t.isEmpty)
    }

    @Test fun `notes are counted independently`() {
        val t = NoteHoldTracker()
        t.press(60); t.press(64)
        assertTrue(t.release(64))
        assertFalse(t.isEmpty)
        assertTrue(t.release(60))
        assertTrue(t.isEmpty)
    }

    @Test fun `releasing an unknown note forwards noteOff (safe side) and stays empty`() {
        val t = NoteHoldTracker()
        assertTrue(t.release(72))
        assertTrue(t.isEmpty)
    }

    @Test fun `a note can be pressed again after a full release`() {
        val t = NoteHoldTracker()
        t.press(60); t.release(60)
        assertTrue("0→1 otra vez", t.press(60))
    }

    @Test fun `releaseAll returns every held note once and clears the tracker`() {
        val t = NoteHoldTracker()
        t.press(60); t.press(60); t.press(67)
        assertEquals(setOf(60, 67), t.releaseAll().toSet())
        assertTrue(t.isEmpty)
        assertEquals(emptyList<Int>(), t.releaseAll())
    }
}
