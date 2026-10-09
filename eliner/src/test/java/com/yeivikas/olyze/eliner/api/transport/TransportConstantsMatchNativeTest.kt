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
}
