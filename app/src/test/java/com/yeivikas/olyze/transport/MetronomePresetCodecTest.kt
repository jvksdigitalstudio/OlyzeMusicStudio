package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.BeatLevel
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo guardado es ENTRADA NO FIABLE (puede corromperse o venir de otra versión) y acaba en el motor:
 * el codec debe ser un ida y vuelta exacto para lo válido y rechazar CUALQUIER campo dudoso.
 */
class MetronomePresetCodecTest {

    private val sample = MetronomePreset(
        bpm = 96.5f, beatsPerBar = 6, beatUnit = BeatUnit.EIGHTH,
        pattern = BeatPattern.defaultFor(6, BeatUnit.EIGHTH).with(4, BeatLevel.MUTE),
        sound = MetronomeSound.WOOD, subdivision = ClickSubdivision.TRIPLETS, volume = 0.35f,
    )

    @Test fun `encoding has the documented v1 shape`() {
        val default = MetronomePreset(120f, 4, BeatUnit.QUARTER, BeatPattern.DEFAULT, MetronomeSound.CLASSIC, ClickSubdivision.NONE, 0.7f)
        assertEquals("v1;120.0;4;4;3222222222222222;0;1;0.7", MetronomePresetCodec.encode(default))
    }

    @Test fun `a valid preset survives a round trip exactly`() {
        assertEquals(sample, MetronomePresetCodec.decode(MetronomePresetCodec.encode(sample)))
    }

    @Test fun `every sound subdivision unit and level survives a round trip`() {
        for (sound in MetronomeSound.values()) for (sub in ClickSubdivision.values()) for (unit in BeatUnit.values()) {
            val p = sample.copy(sound = sound, subdivision = sub, beatUnit = unit)
            assertEquals(p, MetronomePresetCodec.decode(MetronomePresetCodec.encode(p)))
        }
        for (level in BeatLevel.values()) {
            val p = sample.copy(pattern = BeatPattern.of(List(16) { level }))
            assertEquals(p, MetronomePresetCodec.decode(MetronomePresetCodec.encode(p)))
        }
    }

    @Test fun `fractional tempos and volumes round trip with no drift`() {
        for (bpm in listOf(20f, 20.1f, 97.4f, 133.3f, 299.9f, 300f)) {
            for (vol in listOf(0f, 0.1f, 0.54f, 1f)) {
                val p = sample.copy(bpm = bpm, volume = vol)
                assertEquals(p, MetronomePresetCodec.decode(MetronomePresetCodec.encode(p)))
            }
        }
    }

    @Test fun `decoding tolerates surrounding whitespace`() {
        assertNotNull(MetronomePresetCodec.decode("  " + MetronomePresetCodec.encode(sample) + "\n"))
    }

    private fun mutate(index: Int, value: String): String {
        val f = MetronomePresetCodec.encode(sample).split(';').toMutableList()
        f[index] = value
        return f.joinToString(";")
    }

    @Test fun `rejects an unknown or missing version`() {
        assertNull(MetronomePresetCodec.decode(mutate(0, "v2")))
        assertNull(MetronomePresetCodec.decode(mutate(0, "")))
        assertNull(MetronomePresetCodec.decode(MetronomePresetCodec.encode(sample).removePrefix("v1;")))
    }

    @Test fun `rejects the wrong number of fields`() {
        assertNull(MetronomePresetCodec.decode(""))
        assertNull(MetronomePresetCodec.decode("v1"))
        assertNull(MetronomePresetCodec.decode(MetronomePresetCodec.encode(sample) + ";0"))
        assertNull(MetronomePresetCodec.decode(MetronomePresetCodec.encode(sample).substringBeforeLast(';')))
    }

    @Test fun `rejects a tempo outside the engine range or not finite`() {
        for (bad in listOf("19.9", "300.1", "0", "-5", "NaN", "Infinity", "abc", "")) {
            assertNull("bpm=$bad", MetronomePresetCodec.decode(mutate(1, bad)))
        }
    }

    @Test fun `rejects beats per bar outside 1 to 16`() {
        for (bad in listOf("0", "17", "-1", "x", "")) assertNull("beats=$bad", MetronomePresetCodec.decode(mutate(2, bad)))
    }

    @Test fun `rejects a beat unit that is not 2 4 8 or 16`() {
        for (bad in listOf("0", "1", "3", "7", "32", "x")) assertNull("unit=$bad", MetronomePresetCodec.decode(mutate(3, bad)))
    }

    @Test fun `rejects a malformed pattern`() {
        for (bad in listOf("", "322222222222222", "32222222222222222", "4222222222222222", "322222222222222x", "-222222222222222")) {
            assertNull("pattern=$bad", MetronomePresetCodec.decode(mutate(4, bad)))
        }
    }

    @Test fun `rejects non-ASCII digits in the pattern`() {
        // «٣» es un dígito árabe-índico: digitToIntOrNull lo leería como 3; el codec solo admite '0'..'3'.
        assertNull(MetronomePresetCodec.decode(mutate(4, "٣222222222222222")))
    }

    @Test fun `rejects an unknown sound or subdivision`() {
        for (bad in listOf("-1", "5", "99", "x")) assertNull("sound=$bad", MetronomePresetCodec.decode(mutate(5, bad)))
        for (bad in listOf("0", "5", "-1", "x")) assertNull("subdivision=$bad", MetronomePresetCodec.decode(mutate(6, bad)))
    }

    @Test fun `rejects a volume outside 0 to 1 or not finite`() {
        for (bad in listOf("-0.1", "1.1", "NaN", "Infinity", "x", "")) assertNull("volume=$bad", MetronomePresetCodec.decode(mutate(7, bad)))
    }

    @Test fun `title shows the tempo name the bpm and the time signature`() {
        assertEquals("Andante 96.5 · 6/8", sample.title)
        assertEquals("6/8", sample.timeSignature)
        assertEquals("Allegro 120.0 · 4/4", sample.copy(bpm = 120f, beatsPerBar = 4, beatUnit = BeatUnit.QUARTER).title)
    }
}
