package com.yeivikas.olyze.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** El nombre del tempo es una partición sin huecos ni solapes: cada BPM cae en exactamente un nombre. */
class TempoMarkingTest {

    @Test fun `each band starts exactly at its lower limit`() {
        val table = listOf(
            20f to "Grave", 39.9f to "Grave", 40f to "Largo", 59.9f to "Largo", 60f to "Larghetto",
            65.9f to "Larghetto", 66f to "Adagio", 75.9f to "Adagio", 76f to "Andante", 107.9f to "Andante",
            108f to "Moderato", 119.9f to "Moderato", 120f to "Allegro", 155.9f to "Allegro",
            156f to "Vivace", 175.9f to "Vivace", 176f to "Presto", 199.9f to "Presto",
            200f to "Prestissimo", 300f to "Prestissimo",
        )
        for ((bpm, name) in table) assertEquals("bpm=$bpm", name, TempoMarking.forBpm(bpm))
    }

    @Test fun `120 BPM, the default tempo, is Allegro`() {
        assertEquals("Allegro", TempoMarking.forBpm(120f))
    }

    @Test fun `every tempo of the engine range has a name and names never go backwards`() {
        val order = listOf("Grave", "Largo", "Larghetto", "Adagio", "Andante", "Moderato", "Allegro", "Vivace", "Presto", "Prestissimo")
        var last = 0
        var bpm = 20f
        while (bpm <= 300f) {
            val idx = order.indexOf(TempoMarking.forBpm(bpm))
            assertEquals("bpm=$bpm sin nombre", true, idx >= 0)
            assertEquals("el nombre retrocede en bpm=$bpm", true, idx >= last)
            last = idx
            bpm += 0.1f
        }
    }

    @Test fun `values outside the engine range and NaN still give a name`() {
        assertEquals("Grave", TempoMarking.forBpm(0f))
        assertEquals("Grave", TempoMarking.forBpm(-50f))
        assertEquals("Grave", TempoMarking.forBpm(Float.NaN))
        assertEquals("Prestissimo", TempoMarking.forBpm(1000f))
    }
}
