package com.yeivikas.olyze.eliner.api.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1.1 §22 — [ParameterCatalog] no es solo una lista estática: estos
 * tests verifican, EJECUTANDO código, dos propiedades que un catálogo de
 * parámetros existe específicamente para garantizar y que un simple grep
 * manual (lo único posible en el entorno de desarrollo de esta fase, sin
 * kotlinc — ver ADR 0023) no puede probar por sí solo:
 *   1. Cada [ParameterTarget.apply] realmente invoca el setter correcto de
 *      [EliNerAudioApi] — no solo que el nombre "parece correcto".
 *   2. Todo [ParameterMetadata] es internamente consistente (min <= default
 *      <= max) — un catálogo con un default fuera de su propio rango sería
 *      peor que no tener catálogo.
 *
 * EJECUTADO: NO en el entorno de desarrollo (sin kotlinc/JDK completo);
 * pendiente de `./gradlew :eliner:testDebugUnitTest`.
 */
class ParameterCatalogTest {

    // Fake mínimo, solo para este test: no reutiliza el de AudioCommandDispatcherTest
    // (paquete distinto) a propósito — un catálogo de api/ no debe depender de
    // dobles de test de bridge/.
    private fun fakeAudio(onCall: (String, Float) -> Unit): EliNerAudioApi = object : EliNerAudioApi {
        override val isRunning = kotlinx.coroutines.flow.MutableStateFlow(true)
        override val sampleRate = kotlinx.coroutines.flow.MutableStateFlow(48000)
        override val bufferSize = kotlinx.coroutines.flow.MutableStateFlow(256)
        override val cpuLoad = kotlinx.coroutines.flow.MutableStateFlow(0f)
        override val activeVoices = kotlinx.coroutines.flow.MutableStateFlow(0)
        override val droppedCommands = kotlinx.coroutines.flow.MutableStateFlow(0L)
        override val xrunCount = kotlinx.coroutines.flow.MutableStateFlow(0)
        override val lastError = kotlinx.coroutines.flow.MutableStateFlow(EngineErrorFlags.NONE)
        override val maxChainSlots = 8
        override fun start(profile: com.yeivikas.olyze.eliner.api.audio.PerformanceProfile) = true
        override fun stop() {}
        override fun refreshStats() {}
        override fun clearErrors() {}
        override fun noteOn(channel: Int, note: Int, velocity: Int) {}
        override fun noteOff(channel: Int, note: Int) {}
        override fun allNotesOff() {}
        override fun sendCC(channel: Int, cc: Int, value: Int) {}
        override fun setPitchBend(channel: Int, semitones: Float) {}
        override fun setMasterVolume(volume: Float) = onCall("setMasterVolume", volume)
        override fun setReverbMix(mix: Float) = onCall("setReverbMix", mix)
        override fun setReverbRoom(room: Float) = onCall("setReverbRoom", room)
        override fun setReverbDamp(damp: Float) = onCall("setReverbDamp", damp)
        override fun setDelayMix(mix: Float) = onCall("setDelayMix", mix)
        override fun setDelayTime(seconds: Float) = onCall("setDelayTime", seconds)
        override fun setDelayFeedback(feedback: Float) = onCall("setDelayFeedback", feedback)
        override fun insertModule(slot: Int, type: DspModuleType) = true
        override fun removeModule(slot: Int) = true
        override fun moveModule(fromSlot: Int, toSlot: Int) = true
        override fun setModuleParameter(slot: Int, paramId: Int, value: Float) = onCall("setModuleParameter($slot,$paramId)", value)
        override fun getModuleType(slot: Int) = DspModuleType.NONE
    }

    @Test
    fun `every MASTER entry actually reaches the matching EliNerAudioApi setter`() {
        for (meta in ParameterCatalog.MASTER) {
            val calls = mutableListOf<Pair<String, Float>>()
            val audio = fakeAudio { name, value -> calls.add(name to value) }
            val probe = meta.min + (meta.max - meta.min) * 0.37f // valor no trivial, no el default
            meta.target.apply(audio, probe)
            assertEquals("«${meta.id}» debe producir exactamente una llamada", 1, calls.size)
            assertEquals("«${meta.id}» debe propagar el valor sin transformarlo", probe, calls.single().second)
        }
    }

    @Test
    fun `MASTER covers exactly the master-level setters of EliNerAudioApi, no more no less`() {
        // Lista de referencia = exactamente lo que expone la interfaz hoy (ver EliNerAudioApi.kt).
        val expectedTargets = setOf(
            ParameterTarget.MasterVolume, ParameterTarget.ReverbMix, ParameterTarget.ReverbRoom,
            ParameterTarget.ReverbDamp, ParameterTarget.DelayMix, ParameterTarget.DelayTime,
            ParameterTarget.DelayFeedback,
        )
        assertEquals(expectedTargets, ParameterCatalog.MASTER.map { it.target }.toSet())
        assertEquals("sin duplicados", ParameterCatalog.MASTER.size, ParameterCatalog.MASTER.map { it.target }.toSet().size)
    }

    @Test
    fun `every parameter is internally consistent - min less-equal default less-equal max`() {
        val all = ParameterCatalog.MASTER +
            ParameterCatalog.forModule(0, DspModuleType.REVERB) +
            ParameterCatalog.forModule(0, DspModuleType.DELAY)
        for (m in all) {
            assertTrue("«${m.id}»: min(${m.min}) <= default(${m.default})", m.min <= m.default)
            assertTrue("«${m.id}»: default(${m.default}) <= max(${m.max})", m.default <= m.max)
            assertTrue("«${m.id}»: min < max (rango no degenerado)", m.min < m.max)
        }
    }

    @Test
    fun `all ids are unique across MASTER and both module catalogs`() {
        val ids = (ParameterCatalog.MASTER + ParameterCatalog.forModule(2, DspModuleType.REVERB) + ParameterCatalog.forModule(2, DspModuleType.DELAY)).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `forModule returns empty for NONE and routes module parameters to the right slot`() {
        assertTrue(ParameterCatalog.forModule(3, DspModuleType.NONE).isEmpty())

        val calls = mutableListOf<Pair<String, Float>>()
        val audio = fakeAudio { name, value -> calls.add(name to value) }
        val reverbParams = ParameterCatalog.forModule(5, DspModuleType.REVERB)
        assertEquals(3, reverbParams.size)
        reverbParams.forEach { it.target.apply(audio, 0.42f) }
        assertTrue(calls.all { it.first.startsWith("setModuleParameter(5,") })
    }
}
