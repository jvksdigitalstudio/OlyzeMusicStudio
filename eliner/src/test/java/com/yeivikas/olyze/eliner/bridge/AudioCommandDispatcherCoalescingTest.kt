package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.audio.DspModuleType
import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.audio.EngineErrorFlags
import com.yeivikas.olyze.eliner.api.audio.PerformanceProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1.1 §14 — coalescing "latest-value" y cola acotada de
 * [AudioCommandDispatcher] (ver el doc de clase, sección "Cola acotada,
 * backpressure y coalescing").
 *
 * DETERMINISTA, sin `Thread.sleep` para "dar tiempo": cada test cierra una
 * compuerta ([RecordingFakeAudioApi.gate]) que mantiene ocupado al hilo
 * único del dispatcher dentro de un `noteOn`; mientras está bloqueado, todas
 * las llamadas a `setXxx()` se acumulan sin poder ser drenadas, así que el
 * coalescing es una consecuencia lógica (no una carrera de planificación).
 * Al abrir la compuerta se espera con un timeout explícito a que el
 * dispatcher termine ([awaitCalls]).
 *
 * Réplica ejecutada de la misma lógica (sin JVM):
 * eliner/src/test/cpp/core/test_dispatcher_coalescing_and_backpressure_model.cpp.
 *
 * EJECUTADO: NO en el entorno de desarrollo (sin kotlinc/JDK completo);
 * pendiente de `./gradlew :eliner:testDebugUnitTest`.
 */
class AudioCommandDispatcherCoalescingTest {

    private class RecordingFakeAudioApi : EliNerAudioApi {
        val callLog = CopyOnWriteArrayList<String>()

        /** Si no es null, `noteOn` espera aquí — mantiene ocupado al hilo del dispatcher. */
        @Volatile var gate: CountDownLatch? = null

        override val isRunning: StateFlow<Boolean> = MutableStateFlow(true)
        override val sampleRate: StateFlow<Int> = MutableStateFlow(48000)
        override val bufferSize: StateFlow<Int> = MutableStateFlow(256)
        override val cpuLoad: StateFlow<Float> = MutableStateFlow(0f)
        override val activeVoices: StateFlow<Int> = MutableStateFlow(0)
        override val droppedCommands: StateFlow<Long> = MutableStateFlow(0L)
        override val xrunCount: StateFlow<Int> = MutableStateFlow(0)
        override val lastError: StateFlow<EngineErrorFlags> = MutableStateFlow(EngineErrorFlags.NONE)
        override val maxChainSlots: Int = 8

        override fun start(profile: PerformanceProfile): Boolean { record("start"); return true }
        override fun stop() = record("stop")
        override fun refreshStats() = record("refreshStats")
        override fun clearErrors() = record("clearErrors")
        override fun noteOn(channel: Int, note: Int, velocity: Int) {
            record("noteOn($channel,$note,$velocity)")
            gate?.await(10, TimeUnit.SECONDS)
        }
        override fun noteOff(channel: Int, note: Int) = record("noteOff($channel,$note)")
        override fun allNotesOff() = record("allNotesOff")
        override fun sendCC(channel: Int, cc: Int, value: Int) = record("sendCC($channel,$cc,$value)")
        override fun setPitchBend(channel: Int, semitones: Float) = record("setPitchBend($channel,$semitones)")
        override fun setMasterVolume(volume: Float) = record("setMasterVolume($volume)")
        override fun setReverbMix(mix: Float) = record("setReverbMix($mix)")
        override fun setReverbRoom(room: Float) = record("setReverbRoom($room)")
        override fun setReverbDamp(damp: Float) = record("setReverbDamp($damp)")
        override fun setDelayMix(mix: Float) = record("setDelayMix($mix)")
        override fun setDelayTime(seconds: Float) = record("setDelayTime($seconds)")
        override fun setDelayFeedback(feedback: Float) = record("setDelayFeedback($feedback)")
        override fun insertModule(slot: Int, type: DspModuleType): Boolean { record("insertModule($slot,$type)"); return true }
        override fun removeModule(slot: Int): Boolean { record("removeModule($slot)"); return true }
        override fun moveModule(fromSlot: Int, toSlot: Int): Boolean { record("moveModule($fromSlot,$toSlot)"); return true }
        override fun setModuleParameter(slot: Int, paramId: Int, value: Float) = record("setModuleParameter($slot,$paramId,$value)")
        override fun getModuleType(slot: Int): DspModuleType { record("getModuleType($slot)"); return DspModuleType.NONE }

        // Unit explícito: un `= callLog.add(...)` devolvería Boolean y no
        // encajaría con los `override fun x() = record(...)` de arriba,
        // que la interfaz declara como Unit.
        private fun record(what: String) {
            callLog.add(what)
        }
    }

    /** Espera (con timeout duro, sin dormir a ciegas) a que el log alcance [n] entradas. */
    private fun awaitCalls(fake: RecordingFakeAudioApi, n: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (fake.callLog.size < n) {
            check(System.nanoTime() < deadline) { "timeout esperando $n llamadas; log=${fake.callLog}" }
            Thread.sleep(1)
        }
    }

    /** Ocupa al dispatcher dentro de un noteOn hasta que se abra [gate]. */
    private fun blockDispatcher(d: AudioCommandDispatcher, fake: RecordingFakeAudioApi): CountDownLatch {
        val gate = CountDownLatch(1)
        fake.gate = gate
        d.noteOn(0, 60, 100)
        awaitCalls(fake, 1) // el hilo del dispatcher ya está dentro de noteOn, bloqueado en la compuerta
        return gate
    }

    @Test
    fun `200 rapid calls to the same parameter collapse into ONE delivery with the latest value`() {
        val fake = RecordingFakeAudioApi()
        val d = AudioCommandDispatcher(fake)
        val gate = blockDispatcher(d, fake)

        repeat(200) { i -> d.setMasterVolume(i / 200f) }
        gate.countDown()
        awaitCalls(fake, 2)
        d.close()

        val volume = fake.callLog.filter { it.startsWith("setMasterVolume") }
        assertEquals("el coalescing debe dejar exactamente UNA entrega", 1, volume.size)
        assertEquals("y debe ser el ÚLTIMO valor recibido", "setMasterVolume(${199 / 200f})", volume.single())
    }

    @Test
    fun `different parameters are batched in one flush and none is lost`() {
        val fake = RecordingFakeAudioApi()
        val d = AudioCommandDispatcher(fake)
        val gate = blockDispatcher(d, fake)

        d.setMasterVolume(0.5f)
        d.setReverbMix(0.3f)
        d.setDelayMix(0.2f)
        d.setModuleParameter(1, 5, 0.9f)
        d.setModuleParameter(1, 6, 0.4f) // mismo slot, OTRO paramId: clave distinta, no debe colapsar con la anterior
        gate.countDown()
        awaitCalls(fake, 6)
        d.close()

        val log = fake.callLog.toList()
        assertTrue(log.contains("setMasterVolume(0.5)"))
        assertTrue(log.contains("setReverbMix(0.3)"))
        assertTrue(log.contains("setDelayMix(0.2)"))
        assertTrue(log.contains("setModuleParameter(1,5,0.9)"))
        assertTrue(log.contains("setModuleParameter(1,6,0.4)"))
    }

    @Test
    fun `discrete note events around a parameter burst are never coalesced and keep FIFO order`() {
        val fake = RecordingFakeAudioApi()
        val d = AudioCommandDispatcher(fake)
        val gate = blockDispatcher(d, fake) // ya contiene noteOn(0,60,100) como 1ª entrada

        repeat(50) { i -> d.setMasterVolume(i / 50f) } // ráfaga -> un solo flush encolado
        d.noteOff(0, 60)
        gate.countDown()
        awaitCalls(fake, 3)
        d.close()

        assertEquals(
            listOf("noteOn(0,60,100)", "setMasterVolume(${49 / 50f})", "noteOff(0,60)"),
            fake.callLog.toList(),
        )
    }

    @Test
    fun `coalescing works again in a second cycle, not only the first`() {
        val fake = RecordingFakeAudioApi()
        val d = AudioCommandDispatcher(fake)

        d.setMasterVolume(0.1f)
        awaitCalls(fake, 1) // primer ciclo completo: flush ejecutado, flushScheduled liberado

        val gate = CountDownLatch(1)
        fake.gate = gate
        d.noteOn(0, 60, 100)
        awaitCalls(fake, 2) // dispatcher bloqueado dentro de noteOn
        repeat(30) { i -> d.setMasterVolume(0.5f + i / 100f) }
        gate.countDown()
        awaitCalls(fake, 3)
        d.close()

        val volume = fake.callLog.filter { it.startsWith("setMasterVolume") }
        assertEquals(2, volume.size) // 1 del primer ciclo + 1 coalescida del segundo
        assertEquals("setMasterVolume(${0.5f + 29 / 100f})", volume.last())
    }

    @Test
    fun `concurrent producers hammering one parameter deliver exactly one of the written values`() {
        val fake = RecordingFakeAudioApi()
        val d = AudioCommandDispatcher(fake)
        val gate = blockDispatcher(d, fake)

        val threads = 4
        val perThread = 2000
        val written = CopyOnWriteArrayList<String>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread({
                start.await()
                repeat(perThread) { i ->
                    val v = (t * perThread + i) / (threads * perThread).toFloat()
                    d.setDelayFeedback(v)
                    if (i == perThread - 1) written.add("setDelayFeedback($v)")
                }
                done.countDown()
            }, "test-param-hammer-$t").start()
        }
        start.countDown()
        assertTrue("los productores deben terminar sin excepciones", done.await(10, TimeUnit.SECONDS))
        gate.countDown()
        awaitCalls(fake, 2)
        d.close()

        val delivered = fake.callLog.filter { it.startsWith("setDelayFeedback") }
        assertEquals("con el dispatcher ocupado, 8000 llamadas concurrentes colapsan en UNA entrega", 1, delivered.size)
        assertTrue("el valor entregado es el último que escribió alguno de los hilos", delivered.single() in written)
    }
}
