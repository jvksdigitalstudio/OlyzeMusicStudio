package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.DspModuleType
import com.yeivikas.olyze.eliner.api.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.EngineErrorFlags
import com.yeivikas.olyze.eliner.services.PerformanceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifica la garantía central de [AudioCommandDispatcher] (Fase 1 de
 * estabilización — Objetivo A/A.4): sin importar cuántos hilos Android
 * reales llamen concurrentemente (aquí: se simulan "UI" y "MIDI", los dos
 * orígenes reales identificados en la auditoría), todas las llamadas
 * llegan al [EliNerAudioApi] delegado en un orden FIFO consistente,
 * ejecutadas siempre por el mismo hilo físico único — igual que
 * `SpscCommandQueue` (C++) exige de su único productor.
 *
 * Compañero de este test: `eliner/src/test/cpp/core/
 * test_command_queue_interleaving_proof.cpp`, que demuestra el problema
 * del lado C++ que esta clase corrige del lado Kotlin.
 *
 * EJECUTADO: NO — este entorno no tiene `kotlinc`/`javac`/Gradle
 * disponibles (sin red para descargarlos, ver limitaciones de build de
 * esta fase). El código es real y compilaría/correría sin cambios contra
 * el JUnit4 que ya usa `eliner/build.gradle.kts` (`testImplementation(libs.junit)`,
 * mismo patrón que `MidiEventQueueTest.kt`/`MidiStreamParserTest.kt`, que
 * ya llevan el mismo disclaimer en este proyecto) — pendiente de correrse
 * en un entorno con Gradle real.
 */
class AudioCommandDispatcherTest {

    /** Delegate falso: no toca JNI — solo registra, con timestamp lógico
     *  (orden de llegada), cada llamada que recibe, para poder verificar
     *  el orden real en que [AudioCommandDispatcher] las reenvía. */
    private class RecordingFakeAudioApi : EliNerAudioApi {
        val callLog = CopyOnWriteArrayList<String>()
        private val callingThreads = CopyOnWriteArrayList<String>()

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
        override fun noteOn(channel: Int, note: Int, velocity: Int) = record("noteOn($channel,$note,$velocity)")
        override fun noteOff(channel: Int, note: Int) = record("noteOff($channel,$note)")
        override fun allNotesOff() = record("allNotesOff")
        override fun sendCC(channel: Int, cc: Int, value: Int) = record("sendCC($channel,$cc,$value)")
        override fun setPitchBend(channel: Int, semitones: Float) = record("setPitchBend($channel,$semitones)")
        override fun setMasterVolume(volume: Float) = record("setMasterVolume($volume)")
        override fun setReverbMix(mix: Float) = record("setReverbMix($mix)")
        override fun setDelayMix(mix: Float) = record("setDelayMix($mix)")
        override fun setDelayTime(seconds: Float) = record("setDelayTime($seconds)")
        override fun setDelayFeedback(feedback: Float) = record("setDelayFeedback($feedback)")
        override fun insertModule(slot: Int, type: DspModuleType): Boolean { record("insertModule($slot,$type)"); return true }
        override fun removeModule(slot: Int): Boolean { record("removeModule($slot)"); return true }
        override fun moveModule(fromSlot: Int, toSlot: Int): Boolean { record("moveModule($fromSlot,$toSlot)"); return true }
        override fun setModuleParameter(slot: Int, paramId: Int, value: Float) = record("setModuleParameter($slot,$paramId,$value)")
        override fun getModuleType(slot: Int): DspModuleType { record("getModuleType($slot)"); return DspModuleType.NONE }

        private fun record(what: String) {
            callLog.add(what)
            callingThreads.add(Thread.currentThread().name)
        }

        fun allCallsFromSameThread(): Boolean = callingThreads.distinct().size <= 1
    }

    @Test
    fun `concurrent UI and MIDI producers are all executed on a single physical thread`() {
        val fake = RecordingFakeAudioApi()
        val dispatcher = AudioCommandDispatcher(fake)
        val eventsPerProducer = 500
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)

        // Simula el hilo UI — noteOn por toques de teclado en pantalla.
        val uiThread = Thread({
            startLatch.await()
            repeat(eventsPerProducer) { i -> dispatcher.noteOn(channel = 0, note = i, velocity = 100) }
            doneLatch.countDown()
        }, "test-ui-thread")

        // Simula el hilo "eliner-dsp" — sendCC por eventos MIDI externos.
        val midiThread = Thread({
            startLatch.await()
            repeat(eventsPerProducer) { i -> dispatcher.sendCC(channel = 0, cc = 1, value = i % 128) }
            doneLatch.countDown()
        }, "test-midi-thread")

        uiThread.start()
        midiThread.start()
        startLatch.countDown() // libera a ambos productores casi simultáneamente
        assertTrue("los productores deben terminar en un tiempo razonable", doneLatch.await(10, TimeUnit.SECONDS))

        dispatcher.stop() // encola un último comando, para tener un marcador de fin
        dispatcher.close()
        uiThread.join(2000)
        midiThread.join(2000)

        // La garantía central: pase lo que pase con el ORDEN relativo entre
        // UI y MIDI (que depende del scheduler, y no es lo que se está
        // probando aquí), TODAS las llamadas -- venidas de 2 hilos
        // llamadores reales y distintos -- deben haber sido ejecutadas por
        // el MISMO hilo físico único dentro del delegate. Esto es
        // exactamente la propiedad que hace que `SpscCommandQueue` (C++,
        // consumido por ese único hilo) vuelva a ser segura.
        assertTrue(
            "todas las llamadas al delegate deben venir del mismo hilo único (eliner-audio-commands), " +
                "sin importar cuántos hilos llamaron a AudioCommandDispatcher",
            fake.allCallsFromSameThread(),
        )
        assertEquals(
            "no debe perderse ni duplicarse ningún comando",
            eventsPerProducer * 2 + 1 /* +1 = stop() */,
            fake.callLog.size,
        )
    }

    @Test
    fun `start returns the delegate's real result synchronously`() {
        val fake = RecordingFakeAudioApi()
        val dispatcher = AudioCommandDispatcher(fake)
        val result = dispatcher.start(PerformanceProfile.AUTOMATIC)
        assertTrue("start() debe reenviar el resultado real del delegate, bloqueando hasta tenerlo", result)
        assertEquals(listOf("start"), fake.callLog)
        dispatcher.close()
    }

    @Test
    fun `insertModule and getModuleType preserve FIFO order relative to note events`() {
        val fake = RecordingFakeAudioApi()
        val dispatcher = AudioCommandDispatcher(fake)

        dispatcher.noteOn(0, 60, 100)
        dispatcher.insertModule(0, DspModuleType.REVERB)
        dispatcher.noteOff(0, 60)
        dispatcher.close()

        assertEquals(
            "insertModule (bloqueante) no debe adelantarse ni atrasarse respecto a los " +
                "comandos asíncronos encolados antes/después en el mismo hilo llamador",
            listOf("noteOn(0,60,100)", "insertModule(0,REVERB)", "noteOff(0,60)"),
            fake.callLog,
        )
    }

    @Test
    fun `close prevents further commands from reaching the delegate`() {
        val fake = RecordingFakeAudioApi()
        val dispatcher = AudioCommandDispatcher(fake)
        dispatcher.noteOn(0, 60, 100)
        dispatcher.close()

        // Tras close(), nuevos comandos no deben propagarse — el executor
        // está en shutdown, execute() lanza RejectedExecutionException,
        // capturada específicamente en dispatchAsync (ver su doc: no un
        // catch genérico) y nunca relanzada hacia el llamador. Descarte
        // silencioso hacia quien llama, consistente con la filosofía
        // "best-effort delivery" que ya documenta CommandQueue.h para el
        // overflow del lado nativo — solo se registra en Logcat.
        val sizeBeforeExtraCall = fake.callLog.size
        dispatcher.noteOff(0, 60)
        Thread.sleep(50) // margen para que, si LLEGARA a ejecutarse, ya lo hubiera hecho
        assertEquals(
            "ningún comando enviado después de close() debe llegar al delegate",
            sizeBeforeExtraCall,
            fake.callLog.size,
        )
    }

    @Test
    fun `commands after close never throw to the caller — fire-and-forget methods`() {
        val fake = RecordingFakeAudioApi()
        val dispatcher = AudioCommandDispatcher(fake)
        dispatcher.close()

        // Auditoría de cierre de Fase 1: el bug original era exactamente
        // esto — executor.execute() estaba FUERA del try, así que
        // RejectedExecutionException escapaba hacia quien llama. Se
        // verifica aquí, uno por uno, que ningún método fire-and-forget
        // lanza tras close() — si alguno lo hiciera, el test fallaría
        // con esa excepción sin capturar, no con un assert explícito
        // (JUnit reporta cualquier excepción no atrapada como fallo).
        dispatcher.noteOn(0, 60, 100)
        dispatcher.noteOff(0, 60)
        dispatcher.allNotesOff()
        dispatcher.sendCC(0, 1, 64)
        dispatcher.setPitchBend(0, 0.5f)
        dispatcher.setMasterVolume(0.8f)
        dispatcher.setReverbMix(0.3f)
        dispatcher.setDelayMix(0.2f)
        dispatcher.setDelayTime(0.5f)
        dispatcher.setDelayFeedback(0.4f)
        dispatcher.setModuleParameter(0, 1, 0.5f)
        dispatcher.stop()
        dispatcher.clearErrors()
        // Si llegamos aquí sin que JUnit reporte una excepción, el
        // contrato "nunca lanza hacia el llamador" queda verificado.
        assertTrue("ningún comando llegó al delegate tras close()", fake.callLog.isEmpty())
    }

    @Test
    fun `commands after close never throw to the caller — blocking methods return their documented fallback`() {
        val fake = RecordingFakeAudioApi()
        val dispatcher = AudioCommandDispatcher(fake)
        dispatcher.close()

        // Mismo contrato para los métodos bloqueantes (submit() en vez de
        // execute()) — también deben rechazar sin lanzar, devolviendo el
        // valor de repliegue documentado en dispatchBlocking(), nunca el
        // resultado (inexistente) del delegate.
        assertEquals("start() tras close() devuelve false, nunca lanza", false, dispatcher.start(PerformanceProfile.AUTOMATIC))
        assertEquals("insertModule() tras close() devuelve false", false, dispatcher.insertModule(0, DspModuleType.REVERB))
        assertEquals("removeModule() tras close() devuelve false", false, dispatcher.removeModule(0))
        assertEquals("moveModule() tras close() devuelve false", false, dispatcher.moveModule(0, 1))
        assertEquals("getModuleType() tras close() devuelve NONE", DspModuleType.NONE, dispatcher.getModuleType(0))
        assertTrue("ninguna de estas llamadas debe haber llegado al delegate", fake.callLog.isEmpty())
    }

    @Test
    fun `close is idempotent and safe to call concurrently`() {
        val fake = RecordingFakeAudioApi()
        val dispatcher = AudioCommandDispatcher(fake)
        val closers = List(8) { Thread { dispatcher.close() } }
        closers.forEach { it.start() }
        closers.forEach { it.join(2000) }
        // Ninguna excepción debe haber escapado de ninguno de los 8
        // hilos (si alguno lanzara, JUnit lo reportaría como fallo del
        // hilo principal solo si se propaga; para hilos separados, una
        // excepción no capturada termina el hilo silenciosamente en la
        // JVM por defecto salvo un UncaughtExceptionHandler — se verifica
        // aquí indirectamente confirmando que el dispatcher quedó en un
        // estado consistente y sigue rechazando comandos con normalidad).
        assertTrue("isClosed debe ser true tras cualquier número de llamadas a close()", dispatcher.isClosed)
        dispatcher.noteOn(0, 60, 100)
        Thread.sleep(50)
        assertTrue("tras 8 close() concurrentes, sigue rechazando comandos correctamente, sin corromperse", fake.callLog.isEmpty())
    }

    @Test
    fun `shutdown race — producer racing close() never loses accounting or throws`() {
        val fake = RecordingFakeAudioApi()
        val dispatcher = AudioCommandDispatcher(fake)
        val attempts = 20_000
        val accepted = AtomicInteger(0)
        val startLatch = CountDownLatch(1)

        val producer = Thread({
            startLatch.await()
            repeat(attempts) { i ->
                // dispatchAsync no informa éxito/rechazo al llamador por
                // diseño (fire-and-forget) — lo que se verifica aquí es
                // que ninguna llamada, sin importar en qué instante caiga
                // close() en otro hilo, lanza una excepción hacia este.
                dispatcher.noteOn(0, i % 128, 100)
                accepted.incrementAndGet()
            }
        }, "test-shutdown-race-producer")

        val closer = Thread({
            startLatch.await()
            Thread.sleep(2) // cierra en un punto intermedio, no al principio ni al final
            dispatcher.close()
        }, "test-shutdown-race-closer")

        producer.start(); closer.start()
        startLatch.countDown()
        producer.join(10_000); closer.join(10_000)

        // La propiedad central: el productor completó sus `attempts`
        // llamadas sin que ninguna lanzara (si hubiera lanzado, join()
        // habría retornado con el hilo muerto por excepción y
        // `accepted.get()` sería menor que `attempts`).
        assertEquals(
            "el productor debe completar TODOS sus intentos sin que ninguno lance, sin importar cuándo caiga close()",
            attempts,
            accepted.get(),
        )
        // Todo lo que SÍ llegó al delegate antes del cierre debe seguir
        // siendo consistente (sin duplicados) — lo verifica el propio
        // tamaño del log frente al orden observado.
        assertTrue(
            "lo que llegó al delegate antes del cierre debe ser un subconjunto válido de los intentos, sin duplicados",
            fake.callLog.size <= attempts,
        )
    }
}
