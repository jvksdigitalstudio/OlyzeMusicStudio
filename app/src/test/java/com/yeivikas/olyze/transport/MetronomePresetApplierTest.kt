package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.BeatLevel
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Cargar un preset cambia CÓMO suena: nunca el estado de reproducción ni si el metrónomo está activado. */
class MetronomePresetApplierTest {

    private val preset = MetronomePreset(
        bpm = 97.4f, beatsPerBar = 6, beatUnit = BeatUnit.EIGHTH,
        pattern = BeatPattern.defaultFor(6, BeatUnit.EIGHTH).with(5, BeatLevel.MUTE),
        sound = MetronomeSound.COWBELL, subdivision = ClickSubdivision.EIGHTHS, volume = 0.4f,
    )

    @Test fun `every field of the preset reaches the transport`() {
        val log = mutableListOf<String>()
        val t = FakeTransport(log)
        t.applyPreset(preset)
        assertEquals(97.4f, t.tempoBpm.value, 0f)
        assertEquals(6, t.beatsPerBar.value)
        assertEquals(BeatUnit.EIGHTH, t.beatUnit.value)
        assertEquals(preset.pattern, t.beatPattern.value)
        assertEquals(MetronomeSound.COWBELL, t.metronomeSound.value)
        assertEquals(ClickSubdivision.EIGHTHS, t.clickSubdivision.value)
        assertEquals(0.4f, t.metronomeVolume.value, 0f)
    }

    @Test fun `the saved pattern is applied as saved, not reset to the meter default`() {
        val t = FakeTransport()
        t.applyPreset(preset)
        assertEquals(BeatLevel.MUTE, t.beatPattern.value[5])   // el default de 6/8 tendría SOFT ahí
    }

    @Test fun `loading a preset does not start, stop or toggle the metronome`() {
        val log = mutableListOf<String>()
        val t = FakeTransport(log)
        t.applyPreset(preset)
        assertFalse(log.any { it.startsWith("engine.") || it.startsWith("metronome=") })
        assertEquals(false, t.isRunning.value)
    }

    @Test fun `the transport receives the meter before the pattern so they never disagree`() {
        val log = mutableListOf<String>()
        FakeTransport(log).applyPreset(preset)
        val bar = log.indexOfFirst { it.startsWith("bar=") }
        val unit = log.indexOfFirst { it.startsWith("unit=") }
        val pattern = log.indexOfFirst { it.startsWith("pattern=") }
        assertEquals(true, bar in 0 until pattern && unit in 0 until pattern)
    }
}
