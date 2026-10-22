package com.yeivikas.olyze.eliner.api.transport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Los límites de tempo y de compás existen en DOS lenguajes: constantes de
 * Kotlin (para que la UI muestre el rango correcto) y de C++ (que son las que
 * el motor aplica de verdad). Este test lee las cabeceras C++ y falla si se
 * desalinean, en vez de dejar que la UI mienta en silencio.
 */
class TransportConstantsMatchNativeTest {

    private fun header(name: String): String {
        // Gradle ejecuta los tests de un módulo con el directorio del módulo como cwd.
        val candidates = listOf(
            File("include/eliner/transport/$name"),
            File("eliner/include/eliner/transport/$name"),
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: error(
                "No se encontró $name (cwd=${File(".").absolutePath}); este test debe poder leer la " +
                    "cabecera C++ para detectar desalineaciones de constantes.",
            )
        return f.readText()
    }

    private fun constant(src: String, name: String): Double {
        val m = Regex("""\b$name\s*=\s*([0-9]+(?:\.[0-9]+)?)""").find(src)
            ?: error("No se encontró la constante $name en la cabecera C++")
        return m.groupValues[1].toDouble()
    }

    @Test fun `tempo limits match TempoSync_h`() {
        val h = header("TempoSync.h")
        assertEquals(constant(h, "kMinBpm"), EliNerTransportApi.MIN_TEMPO_BPM.toDouble(), 0.0)
        assertEquals(constant(h, "kMaxBpm"), EliNerTransportApi.MAX_TEMPO_BPM.toDouble(), 0.0)
        assertEquals(constant(h, "kDefaultBpm"), EliNerTransportApi.DEFAULT_TEMPO_BPM.toDouble(), 0.0)
    }

    @Test fun `beats per bar limits match TempoClock_h`() {
        val h = header("TempoClock.h")
        assertEquals(constant(h, "kMinBeatsPerBar").toInt(), EliNerTransportApi.MIN_BEATS_PER_BAR)
        assertEquals(constant(h, "kMaxBeatsPerBar").toInt(), EliNerTransportApi.MAX_BEATS_PER_BAR)
    }

    @Test fun `packed pulse format matches BeatPulse_h`() {
        val h = header("BeatPulse.h")
        val mask = Regex("""kBeatMask\s*=\s*0x([0-9A-Fa-f]+)""").find(h)?.groupValues?.get(1)?.toLong(16)
            ?: error("No se encontró kBeatMask en BeatPulse.h")
        val runningShift = Regex("""kRunningBit\s*=\s*1ull\s*<<\s*([0-9]+)""").find(h)?.groupValues?.get(1)?.toInt()
            ?: error("No se encontró kRunningBit en BeatPulse.h")
        val seqShift = Regex("""kSeqShift\s*=\s*([0-9]+)""").find(h)?.groupValues?.get(1)?.toInt()
            ?: error("No se encontró kSeqShift en BeatPulse.h")
        assertEquals(mask, BeatPulse.BEAT_MASK)
        assertEquals(1L shl runningShift, BeatPulse.RUNNING_BIT)
        assertEquals(seqShift, BeatPulse.SEQUENCE_SHIFT)
    }

    @Test fun `fromPacked decodes every field and keeps them independent`() {
        // Mismo valor que fija el static_assert de test_engine_beat_pulse.cpp: pack(1, 15, true).
        val raw = (1L shl 16) or (1L shl 8) or 15L
        assertEquals(BeatPulse(sequence = 1, beatInBar = 15, isRunning = true), BeatPulse.fromPacked(raw))
        assertEquals(BeatPulse.IDLE, BeatPulse.fromPacked(0L))
        val maxSeq = (1L shl 47) - 1
        val max = BeatPulse.fromPacked((maxSeq shl 16) or 15L)
        assertEquals(maxSeq, max.sequence)
        assertEquals(15, max.beatInBar)
    }

    @Test fun `MetronomeSound matches the ClickSound enum in ClickSound_h`() {
        val h = header("ClickSound.h")
        val body = Regex("""enum class ClickSound\s*:\s*int\s*\{([^}]*)\}""").find(h)?.groupValues?.get(1)
            ?: error("No se encontró enum class ClickSound en ClickSound.h")
        // Enumeradores con su valor explícito, sin el centinela Count.
        val native = Regex("""(\w+)\s*=\s*(\d+)""").findAll(body)
            .map { it.groupValues[1].uppercase() to it.groupValues[2].toInt() }
            .filter { it.first != "COUNT" }.toList()
        val kotlin = MetronomeSound.values().map { it.name to it.nativeId }
        assertEquals("Kotlin y C++ deben tener los mismos sonidos, con los mismos índices y orden", native, kotlin)
        val count = Regex("""Count\s*=\s*(\d+)""").find(body)?.groupValues?.get(1)?.toInt()
        assertEquals("el centinela Count debe ser el número de sonidos", MetronomeSound.values().size, count)
    }

    @Test fun `ClickSubdivision matches ClickSound_h`() {
        val h = header("ClickSound.h")
        val body = Regex("""enum class ClickSubdivision\s*:\s*int\s*\{([^}]*)\}""").find(h)?.groupValues?.get(1)
            ?: error("No se encontró enum class ClickSubdivision en ClickSound.h")
        val native = Regex("""(\w+)\s*=\s*(\d+)""").findAll(body).map { it.groupValues[1].uppercase() to it.groupValues[2].toInt() }.toList()
        assertEquals(
            "mismos nombres y valores (clicks por pulso) en Kotlin y C++",
            listOf("NONE" to 1, "EIGHTHS" to 2, "TRIPLETS" to 3, "SIXTEENTHS" to 4),
            native,
        )
        assertEquals(native, ClickSubdivision.values().map { it.name to it.perBeat })
        val min = Regex("""kMinClickSubdivision\s*=\s*(\d+)""").find(h)?.groupValues?.get(1)?.toInt()
        val max = Regex("""kMaxClickSubdivision\s*=\s*(\d+)""").find(h)?.groupValues?.get(1)?.toInt()
        assertEquals(ClickSubdivision.values().minOf { it.perBeat }, min)
        assertEquals(ClickSubdivision.values().maxOf { it.perBeat }, max)
    }

    @Test fun `BeatLevel matches the BeatLevel enum in ClickSound_h`() {
        val h = header("ClickSound.h")
        val body = Regex("""enum class BeatLevel\s*:\s*int\s*\{([^}]*)\}""").find(h)?.groupValues?.get(1)
            ?: error("No se encontró enum class BeatLevel en ClickSound.h")
        val native = Regex("""(\w+)\s*=\s*(\d+)""").findAll(body)
            .map { it.groupValues[1].uppercase() to it.groupValues[2].toInt() }.filter { it.first != "COUNT" }.toList()
        assertEquals("mismos niveles, valores y orden en Kotlin y C++", native, BeatLevel.values().map { it.name to it.nativeId })
        val count = Regex("""Count\s*=\s*(\d+)""").find(body)?.groupValues?.get(1)?.toInt()
        assertEquals(BeatLevel.values().size, count)
    }

    @Test fun `pattern packing constants match ClickSound_h`() {
        val h = header("ClickSound.h")
        fun constant(name: String) = Regex("""$name\s*=\s*(\d+)""").find(h)?.groupValues?.get(1)?.toInt()
            ?: error("No se encontró $name en ClickSound.h")
        assertEquals(constant("kMaxPatternBeats"), BeatPattern.MAX_BEATS)
        assertEquals(constant("kBeatLevelBits"), BeatPattern.BITS_PER_BEAT)
        assertEquals(constant("kBeatsPerPatternWord"), BeatPattern.BEATS_PER_WORD)
        assertEquals("el patrón debe ocupar tantos tiempos como admite un compás", EliNerTransportApi.MAX_BEATS_PER_BAR, BeatPattern.MAX_BEATS)
        val wordMax = Regex("""kPatternWordMax\s*=\s*0x([0-9A-Fa-f]+)""").find(h)?.groupValues?.get(1)?.toInt(16)
        assertEquals(0xFFFF, wordMax)
    }

    @Test fun `BeatUnit matches BeatUnit_h`() {
        val h = header("BeatUnit.h")
        val body = Regex("""enum class BeatUnit\s*:\s*int\s*\{([^}]*)\}""").find(h)?.groupValues?.get(1)
            ?: error("No se encontró enum class BeatUnit en BeatUnit.h")
        val native = Regex("""(\w+)\s*=\s*(\d+)""").findAll(body).map { it.groupValues[1].uppercase() to it.groupValues[2].toInt() }.toList()
        assertEquals("mismos nombres y denominadores en Kotlin y C++", native, BeatUnit.values().map { it.name to it.denominator })
    }

    // ── ADR 0035: conteo previo, modo del click y campos nuevos del pulso ───────────────────────────

    @Test fun `count-in lengths match CountIn_h`() {
        val h = header("CountIn.h")
        // Duraciones válidas: las del isValidCountInBars del header (0, 1, 2 y el máximo).
        val body = Regex("""isValidCountInBars\(int bars\)\s*\{\s*return([^;]+);""").find(h)?.groupValues?.get(1)
            ?: error("No se encontró isValidCountInBars en CountIn.h")
        val native = (Regex("""bars\s*==\s*([0-9]+)""").findAll(body).map { it.groupValues[1].toInt() }.toSet() +
            Regex("""bars\s*==\s*(kCountInBarsMax)""").findAll(body).map { constant(h, "kCountInBarsMax").toInt() }.toSet())
        assertEquals(native, CountInLength.values().map { it.bars }.toSet())
        assertEquals(constant(h, "kCountInBarsMax").toInt(), CountInLength.values().maxOf { it.bars })
        assertEquals(constant(h, "kCountInBarsOff").toInt(), CountInLength.OFF.bars)
        assertEquals(constant(h, "kCountInBarsDefault").toInt(), EliNerTransportApi.DEFAULT_COUNT_IN_LENGTH.bars)
    }

    @Test fun `ClickMode matches the ClickMode enum in ClickPolicy_h`() {
        val h = header("ClickPolicy.h")
        val body = Regex("""enum class ClickMode\s*:\s*int\s*\{([^}]*)\}""").find(h)?.groupValues?.get(1)
            ?: error("No se encontró enum class ClickMode en ClickPolicy.h")
        // `Count` es el centinela (cuántos modos hay), no un modo.
        val native = Regex("""(\w+)\s*=\s*([0-9]+)""").findAll(body)
            .map { it.groupValues[1].uppercase() to it.groupValues[2].toInt() }
            .filter { it.first != "COUNT" }
            .toList()
        val kotlin = ClickMode.values().map { it.name.replace("_", "") to it.nativeId }
        assertEquals(native, kotlin)
        assertEquals(constant(body, "Count").toInt(), ClickMode.values().size)
        assertEquals(ClickMode.ALWAYS, EliNerTransportApi.DEFAULT_CLICK_MODE)
    }

    @Test fun `count-in fields of the packed pulse match BeatPulse_h`() {
        val h = header("BeatPulse.h")
        val bit = Regex("""kCountInBit\s*=\s*1ull\s*<<\s*([0-9]+)""").find(h)?.groupValues?.get(1)?.toInt()
            ?: error("No se encontró kCountInBit en BeatPulse.h")
        val shift = Regex("""kCountInBarShift\s*=\s*([0-9]+)""").find(h)?.groupValues?.get(1)?.toInt()
            ?: error("No se encontró kCountInBarShift en BeatPulse.h")
        val mask = Regex("""kCountInBarMask\s*=\s*0x([0-9A-Fa-f]+)""").find(h)?.groupValues?.get(1)?.toLong(16)
            ?: error("No se encontró kCountInBarMask en BeatPulse.h")
        assertEquals(1L shl bit, BeatPulse.COUNT_IN_BIT)
        assertEquals(shift, BeatPulse.COUNT_IN_BAR_SHIFT)
        assertEquals(mask, BeatPulse.COUNT_IN_BAR_MASK)
    }

    @Test fun `fromPacked decodes the count-in flag and bar without disturbing the other fields`() {
        // Mismo valor que pulse::pack(5, 2, true, true, 3) del test nativo test_count_in.
        val raw = (5L shl 16) or (3L shl 10) or (1L shl 9) or (1L shl 8) or 2L
        assertEquals(
            BeatPulse(sequence = 5L, beatInBar = 2, isRunning = true, isCountIn = true, countInBar = 3),
            BeatPulse.fromPacked(raw),
        )
        // Un compás de conteo sin la marca de conteo se ignora.
        assertEquals(0, BeatPulse.fromPacked((3L shl 10) or (1L shl 8)).countInBar)
        // El formato anterior (sin campos de conteo) sigue decodificándose igual.
        assertEquals(
            BeatPulse(sequence = 1L, beatInBar = 15, isRunning = true),
            BeatPulse.fromPacked((1L shl 16) or (1L shl 8) or 15L),
        )
    }

    // ── ADR 0036: posición del transporte ───────────────────────────────────────────────────────────

    @Test fun `position constants match Position_h`() {
        val h = header("Position.h")
        assertEquals(constant(h, "kTicksPerQuarter").toInt(), TransportPosition.TICKS_PER_QUARTER)
        // kTicksPerMidiBeat es una expresión en el header (una semicorchea = un cuarto de negra).
        assertEquals(true, Regex("""kTicksPerMidiBeat\s*=\s*kTicksPerQuarter\s*/\s*4""").containsMatchIn(h))
        assertEquals(TransportPosition.TICKS_PER_QUARTER / 4, TransportPosition.TICKS_PER_MIDI_BEAT)
        assertEquals(constant(h, "kMaxSongPosition").toInt(), TransportPosition.MAX_SONG_POSITION)
        // kMaxPositionTicks se escribe como `1 << 24` en el header.
        val max = Regex("""kMaxPositionTicks\s*=\s*1\s*<<\s*([0-9]+)""").find(h)?.groupValues?.get(1)?.toInt()
            ?: error("No se encontró kMaxPositionTicks = 1 << N en Position.h")
        assertEquals(1L shl max, TransportPosition.MAX_TICKS)
        // Debe ser exacto en float: viaja al motor como parámetro float.
        assertEquals(TransportPosition.MAX_TICKS, TransportPosition.MAX_TICKS.toFloat().toLong())
    }

    @Test fun `songPositionFor gives the same values as the native test_position`() {
        // Mismos casos que test_position.cpp.
        assertEquals(0, TransportPosition.songPositionFor(0))
        assertEquals(0, TransportPosition.songPositionFor(-500))
        assertEquals(0, TransportPosition.songPositionFor(239))
        assertEquals(1, TransportPosition.songPositionFor(240))
        assertEquals(3, TransportPosition.songPositionFor(959))
        assertEquals(4, TransportPosition.songPositionFor(960))
        assertEquals(16383, TransportPosition.songPositionFor(16383L * 240))
        assertEquals(16383, TransportPosition.songPositionFor((16383L + 1) * 240))
        assertEquals(16383, TransportPosition.songPositionFor(TransportPosition.MAX_TICKS))
    }

    @Test fun `clamp keeps positions inside what the engine accepts`() {
        assertEquals(0L, TransportPosition.clamp(-1L))
        assertEquals(960L, TransportPosition.clamp(960L))
        assertEquals(TransportPosition.MAX_TICKS, TransportPosition.clamp(Long.MAX_VALUE))
    }

    // ── ADR 0039: swing del click ───────────────────────────────────────────────────────────────────

    @Test fun `click swing presets stay inside the native range and straight is the default`() {
        val h = header("ClickSound.h")
        val min = constant(h, "kMinClickSwingPercent").toInt()
        val max = constant(h, "kMaxClickSwingPercent").toInt()
        val def = constant(h, "kDefaultClickSwingPercent").toInt()
        ClickSwing.values().forEach { assertEquals("${it.name}: dentro de $min..$max", true, it.percent in min..max) }
        assertEquals(def, ClickSwing.STRAIGHT.percent)
        assertEquals(def, EliNerTransportApi.DEFAULT_CLICK_SWING.percent)
        assertEquals(min, ClickSwing.values().minOf { it.percent })     // el recto es el extremo inferior del eje
        assertEquals(max, ClickSwing.values().maxOf { it.percent })     // y el más fuerte llega al tope nativo
        assertEquals(ClickSwing.values().size, ClickSwing.values().map { it.percent }.toSet().size)  // sin repetidos
    }
}
