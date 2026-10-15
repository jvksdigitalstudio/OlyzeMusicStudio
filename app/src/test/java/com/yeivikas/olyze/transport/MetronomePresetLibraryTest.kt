package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reglas de la colección de presets (sin duplicados, tope, orden, persistencia) con un almacén en memoria. */
class MetronomePresetLibraryTest {

    private class InMemoryStore(var lines: List<String> = emptyList()) : PresetStore {
        var writes = 0
        override fun readAll() = lines
        override fun writeAll(lines: List<String>) { this.lines = lines; writes++ }
    }

    private fun preset(bpm: Float) =
        MetronomePreset(bpm, 4, BeatUnit.QUARTER, BeatPattern.DEFAULT, MetronomeSound.CLASSIC, ClickSubdivision.NONE, 0.7f)

    @Test fun `starts empty with an empty store`() {
        assertEquals(emptyList<MetronomePreset>(), MetronomePresetLibrary(InMemoryStore()).presets.value)
    }

    @Test fun `add keeps the order, newest last`() {
        val lib = MetronomePresetLibrary(InMemoryStore())
        assertTrue(lib.add(preset(100f)))
        assertTrue(lib.add(preset(140f)))
        assertEquals(listOf(100f, 140f), lib.presets.value.map { it.bpm })
    }

    @Test fun `an identical preset is not stored twice`() {
        val store = InMemoryStore()
        val lib = MetronomePresetLibrary(store)
        assertTrue(lib.add(preset(100f)))
        assertFalse(lib.canAdd(preset(100f)))
        assertFalse(lib.add(preset(100f)))
        assertEquals(1, lib.presets.value.size)
        assertEquals("un rechazo no debe escribir", 1, store.writes)
    }

    @Test fun `no preset is silently dropped when the library is full`() {
        val lib = MetronomePresetLibrary(InMemoryStore(), maxPresets = 3)
        for (bpm in listOf(60f, 70f, 80f)) assertTrue(lib.add(preset(bpm)))
        assertFalse(lib.canAdd(preset(90f)))
        assertFalse(lib.add(preset(90f)))
        assertEquals(listOf(60f, 70f, 80f), lib.presets.value.map { it.bpm })
        lib.remove(preset(70f))
        assertTrue("al borrar uno vuelve a haber hueco", lib.add(preset(90f)))
        assertEquals(listOf(60f, 80f, 90f), lib.presets.value.map { it.bpm })
    }

    @Test fun `remove deletes only the matching preset and ignores unknown ones`() {
        val store = InMemoryStore()
        val lib = MetronomePresetLibrary(store)
        lib.add(preset(100f)); lib.add(preset(140f))
        val writes = store.writes
        lib.remove(preset(999f))
        assertEquals("borrar uno que no existe no escribe", writes, store.writes)
        lib.remove(preset(100f))
        assertEquals(listOf(140f), lib.presets.value.map { it.bpm })
    }

    @Test fun `every change is persisted and a new library reads it back`() {
        val store = InMemoryStore()
        val first = MetronomePresetLibrary(store)
        first.add(preset(100f)); first.add(preset(140f)); first.remove(preset(100f))
        val second = MetronomePresetLibrary(store)
        assertEquals(listOf(140f), second.presets.value.map { it.bpm })
    }

    @Test fun `corrupt lines are skipped on load and cleaned on the next write`() {
        val good = MetronomePresetCodec.encode(preset(100f))
        val store = InMemoryStore(listOf("basura", good, "v1;999;4;4;3222222222222222;0;1;0.7", ""))
        val lib = MetronomePresetLibrary(store)
        assertEquals(listOf(100f), lib.presets.value.map { it.bpm })
        lib.add(preset(140f))
        assertEquals("la basura desaparece al reescribir", 2, store.lines.size)
    }

    @Test fun `loading never exceeds the maximum even if the store holds more`() {
        val store = InMemoryStore((1..10).map { MetronomePresetCodec.encode(preset(50f + it)) })
        assertEquals(4, MetronomePresetLibrary(store, maxPresets = 4).presets.value.size)
    }
}
