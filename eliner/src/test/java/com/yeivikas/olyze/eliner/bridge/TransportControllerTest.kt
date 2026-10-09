package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [TransportController] sin JNI: un doble de [TransportNative] registra cada
 * orden que recibiría el motor. Cubre validación, límites, idempotencia, la
 * coalescencia, el orden de envío y —lo más importante— que NINGÚN setter llama
 * al motor en el hilo del llamador (la UI nunca debe esperar a JNI).
 */
class TransportControllerTest {

    private class FakeNative : TransportNative {
        val calls = mutableListOf<String>()
        @Volatile var lastTempo: Float = Float.NaN
        @Volatile var lastCallerThread: Thread? = null

        /** Valores que devolverá [readPulse], en orden; al agotarse repite el último (como un motor parado). */
        private val pulseScript = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        @Volatile private var lastPulse = 0L
        val pulseReads = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var pulseThread: Thread? = null
        fun scriptPulse(vararg raw: Long) { raw.forEach { pulseScript.add(it) } }
        override fun readPulse(): Long {
            pulseReads.incrementAndGet(); pulseThread = Thread.currentThread()
            pulseScript.poll()?.let { lastPulse = it }
            return lastPulse
        }
        override fun setTempo(bpm: Float) { rec("tempo=$bpm"); lastTempo = bpm }
        override fun setTransportRunning(running: Boolean) = rec("running=$running")
        override fun setBeatsPerBar(beats: Int) = rec("bar=$beats")
        override fun setMetronomeEnabled(enabled: Boolean) = rec("metro=$enabled")
        override fun setMetronomeVolume(volume: Float) = rec("vol=$volume")
        override fun setDelayTempoSync(enabled: Boolean, beats: Float) = rec("sync=$enabled,$beats")
        override fun setMetronomeSound(sound: Int) = rec("sound=$sound")
        override fun setMetronomeAccent(enabled: Boolean) = rec("accent=$enabled")
        override fun setMetronomeSubdivision(perBeat: Int) = rec("subdiv=$perBeat")
        private fun rec(c: String) { synchronized(calls) { calls += c }; lastCallerThread = Thread.currentThread() }
        fun snapshot(): List<String> = synchronized(calls) { calls.toList() }
        fun clear() = synchronized(calls) { calls.clear() }
    }

    /** Ejecuta cada tarea en el acto, en el hilo que llama: cada setter se vacía inmediatamente. */
    private val direct = Executor { it.run() }

    /** Acumula las tareas hasta que el test decide ejecutarlas (modela un hilo del motor ocupado). */
    private class ManualExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private fun controller(executor: Executor = direct): Pair<TransportController, FakeNative> {
        val n = FakeNative()
        return TransportController(n, executor) to n
    }

    // ── Estado, límites, validación ──────────────────────────────────────────

    @Test fun `defaults match the engine defaults`() {
        val (c, n) = controller()
        assertEquals(120f, c.tempoBpm.value, 0f)
        assertFalse(c.isRunning.value)
        assertEquals(4, c.beatsPerBar.value)
        assertFalse(c.metronomeEnabled.value)
        assertEquals(0.7f, c.metronomeVolume.value, 0f)
        assertEquals(DelayTempoSync(true, NoteDivision.EIGHTH_DOTTED), c.delaySync.value)
        assertTrue("construir el controlador no debe tocar el motor", n.snapshot().isEmpty())
    }

    @Test fun `tempo is clamped and forwarded`() {
        val (c, n) = controller()
        c.setTempo(90f);   assertEquals(90f, c.tempoBpm.value, 0f)
        c.setTempo(500f);  assertEquals(300f, c.tempoBpm.value, 0f)
        c.setTempo(1f);    assertEquals(20f, c.tempoBpm.value, 0f)
        c.setTempo(-40f);  assertEquals(20f, c.tempoBpm.value, 0f)
        assertEquals(listOf("tempo=90.0", "tempo=300.0", "tempo=20.0", "tempo=20.0"), n.snapshot())
    }

    @Test fun `non finite tempo is ignored entirely`() {
        val (c, n) = controller()
        c.setTempo(100f)
        c.setTempo(Float.NaN)
        c.setTempo(Float.POSITIVE_INFINITY)
        c.setTempo(Float.NEGATIVE_INFINITY)
        assertEquals(100f, c.tempoBpm.value, 0f)
        assertEquals(listOf("tempo=100.0"), n.snapshot())
    }

    @Test fun `start and stop are idempotent`() {
        val (c, n) = controller()
        c.stop()                       // ya detenido: nada
        c.start(); c.start()           // el segundo no reinicia el compás
        assertTrue(c.isRunning.value)
        c.stop(); c.stop()
        assertFalse(c.isRunning.value)
        assertEquals(listOf("running=true", "running=false"), n.snapshot())
    }

    @Test fun `beats per bar is clamped`() {
        val (c, n) = controller()
        c.setBeatsPerBar(3);   assertEquals(3, c.beatsPerBar.value)
        c.setBeatsPerBar(0);   assertEquals(1, c.beatsPerBar.value)
        c.setBeatsPerBar(99);  assertEquals(16, c.beatsPerBar.value)
        assertEquals(listOf("bar=3", "bar=1", "bar=16"), n.snapshot())
    }

    @Test fun `metronome volume is clamped and non finite is ignored`() {
        val (c, n) = controller()
        c.setMetronomeVolume(0.5f)
        c.setMetronomeVolume(4f);  assertEquals(1f, c.metronomeVolume.value, 0f)
        c.setMetronomeVolume(-1f); assertEquals(0f, c.metronomeVolume.value, 0f)
        c.setMetronomeVolume(Float.NaN)
        assertEquals(0f, c.metronomeVolume.value, 0f)
        assertEquals(listOf("vol=0.5", "vol=1.0", "vol=0.0"), n.snapshot())
    }

    @Test fun `delay sync sends the division in beats`() {
        val (c, n) = controller()
        c.setDelayTempoSync(true, NoteDivision.QUARTER)
        c.setDelayTempoSync(false, NoteDivision.EIGHTH_TRIPLET)
        c.setDelayTempoSync(true)      // división por defecto: corchea con puntillo
        assertEquals(DelayTempoSync(true, NoteDivision.EIGHTH_DOTTED), c.delaySync.value)
        assertEquals(
            listOf("sync=true,1.0", "sync=false,${1f / 3f}", "sync=true,0.75"),
            n.snapshot(),
        )
    }

    @Test fun `state flows reflect the desired state immediately`() {
        val (c, _) = controller(ManualExecutor())   // el motor ni siquiera ha recibido nada todavía
        c.setTempo(133f); c.setMetronomeEnabled(true); c.start()
        assertEquals(133f, c.tempoBpm.value, 0f)
        assertTrue(c.metronomeEnabled.value)
        assertTrue(c.isRunning.value)
    }

    // ── Reaplicación al crearse el motor ─────────────────────────────────────

    @Test fun `syncToNative replays the whole desired state, configuration first and transport last`() {
        val (c, n) = controller()
        c.setTempo(97f); c.setBeatsPerBar(3); c.setMetronomeVolume(0.4f)
        c.setMetronomeEnabled(true); c.setDelayTempoSync(true, NoteDivision.HALF); c.start()
        n.clear()                      // "el motor nuevo no sabe nada"

        c.syncToNative()

        assertEquals(
            listOf("tempo=97.0", "bar=3", "vol=0.4", "metro=true", "sound=0", "accent=true", "subdiv=1", "sync=true,2.0", "running=true"),
            n.snapshot(),
        )
    }

    @Test fun `syncToNative with defaults sends the defaults`() {
        val (c, n) = controller()
        c.syncToNative()
        assertEquals(
            listOf("tempo=120.0", "bar=4", "vol=0.7", "metro=false", "sound=0", "accent=true", "subdiv=1", "sync=true,0.75", "running=false"),
            n.snapshot(),
        )
    }

    // ── La UI nunca espera al motor ──────────────────────────────────────────

    @Test fun `no setter touches the engine on the calling thread`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.setTempo(140f); c.start(); c.setMetronomeEnabled(true); c.setMetronomeVolume(0.9f)
        c.setBeatsPerBar(6); c.setDelayTempoSync(false, NoteDivision.QUARTER); c.syncToNative()
        assertTrue("ninguna orden debe llegar al motor antes de que el hilo del motor trabaje", n.snapshot().isEmpty())
        assertEquals("y solo debe haber UN vaciado encolado", 1, exec.queue.size)
    }

    @Test fun `pending changes coalesce into one flush carrying the latest values`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        // El hilo del motor está ocupado (p. ej. nativeCreate abriendo el stream) y el usuario mantiene pulsado "+".
        for (bpm in 121..160) c.setTempo(bpm.toFloat())
        c.setMetronomeEnabled(true)
        c.setMetronomeEnabled(false)
        c.setMetronomeEnabled(true)
        assertEquals(1, exec.queue.size)
        exec.runAll()
        assertEquals(listOf("tempo=160.0", "metro=true"), n.snapshot())
    }

    @Test fun `a change made while a flush is running schedules a new flush`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.setTempo(100f)
        exec.runAll()
        c.setTempo(110f)
        assertEquals("tras un vaciado, un cambio nuevo debe encolar otro", 1, exec.queue.size)
        exec.runAll()
        assertEquals(listOf("tempo=100.0", "tempo=110.0"), n.snapshot())
    }

    @Test fun `only changed fields are sent`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.setBeatsPerBar(5)
        exec.runAll(); n.clear()
        c.setTempo(88f)
        exec.runAll()
        assertEquals("un vaciado no debe reenviar campos que no cambiaron", listOf("tempo=88.0"), n.snapshot())
    }

    @Test fun `engine created after UI changes receives the latest state exactly once`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.setTempo(97f); c.setMetronomeEnabled(true); c.start()
        exec.runAll()                  // el motor aún no existía: el nativo descartó estas órdenes
        n.clear()
        c.setTempo(101f)               // la UI sigue tocando mientras el motor termina de arrancar
        c.syncToNative()               // el puente avisa: motor creado
        exec.runAll()
        assertEquals(
            listOf("tempo=101.0", "bar=4", "vol=0.7", "metro=true", "sound=0", "accent=true", "subdiv=1", "sync=true,0.75", "running=true"),
            n.snapshot(),
        )
    }

    @Test fun `commands after the executor is shut down do not throw and keep the desired state`() {
        val rejecting = Executor { throw RejectedExecutionException("apagado") }
        val (c, n) = controller(rejecting)
        c.setTempo(150f); c.start(); c.setMetronomeEnabled(true); c.syncToNative()
        assertEquals(150f, c.tempoBpm.value, 0f)
        assertTrue(c.isRunning.value && c.metronomeEnabled.value)
        assertTrue(n.snapshot().isEmpty())
    }

    @Test fun `a rejected flush does not wedge later flushes`() {
        var reject = true
        val queue = ArrayDeque<Runnable>()
        val flaky = Executor { if (reject) throw RejectedExecutionException() else queue.addLast(it) }
        val (c, n) = controller(flaky)
        c.setTempo(111f)               // rechazado
        reject = false
        c.setTempo(122f)               // debe poder encolarse: el rechazo no dejó "pendiente" atascado
        assertEquals(1, queue.size)
        queue.removeFirst().run()
        assertEquals(listOf("tempo=122.0"), n.snapshot())
    }

    // ── Concurrencia ─────────────────────────────────────────────────────────

    @Test fun `with a real single thread executor the engine always ends with the latest tempo`() {
        // Propiedad: tras vaciar la cola, el ÚLTIMO tempo que recibió el motor es el del estado deseado,
        // con la UI y el puente (syncToNative) tocando a la vez. Y TODAS las llamadas al motor ocurren en
        // el hilo del executor, nunca en los hilos llamadores.
        repeat(100) {
            val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "test-transport") }
            val n = FakeNative()
            val c = TransportController(n, ex)
            val ui = thread { for (i in 0 until 300) c.setTempo(20f + (i % 280)) }
            val bridge = thread { for (i in 0 until 60) c.syncToNative() }
            ui.join(); bridge.join()
            ex.shutdown()
            assertTrue(ex.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(c.tempoBpm.value, n.lastTempo, 0f)
            assertEquals("test-transport", n.lastCallerThread?.name)
        }
    }

    // ── Pulso real del motor (ADR 0029) ──────────────────────────────────────

    private fun packed(seq: Long, beat: Int, running: Boolean): Long =
        (seq shl 16) or (if (running) 1L shl 8 else 0L) or beat.toLong()

    @Test fun `pulse flow is cold - the engine is not polled until someone collects`() {
        val (c, n) = controller()
        Thread.sleep(60)
        assertEquals(0, n.pulseReads.get())
        assertEquals(BeatPulse.IDLE, runBlocking { withTimeout(2_000) { c.pulse.take(1).toList() }.single() })
        assertTrue(n.pulseReads.get() >= 1)
    }

    @Test fun `pulse flow decodes the packed snapshot`() = runBlocking {
        val (c, n) = controller()
        n.scriptPulse(packed(7, 2, running = true))
        val got = withTimeout(2_000) { c.pulse.take(1).toList() }.single()
        assertEquals(BeatPulse(sequence = 7, beatInBar = 2, isRunning = true), got)
    }

    @Test fun `pulse flow skips no-data readings and never emits the same value twice`() = runBlocking {
        val (c, n) = controller()
        // -1 = motor ocupado (sin dato). Los valores repetidos simulan sondeos sin pulso nuevo.
        n.scriptPulse(-1L, packed(5, 0, true), packed(5, 0, true), packed(5, 0, true), packed(6, 1, true))
        val got = withTimeout(2_000) { c.pulse.take(2).toList() }
        assertEquals(listOf(BeatPulse(5, 0, true), BeatPulse(6, 1, true)), got)
    }

    @Test fun `pulse flow reports stop as running=false with the sequence preserved`() = runBlocking {
        val (c, n) = controller()
        n.scriptPulse(packed(9, 3, true), packed(9, 0, false))
        val got = withTimeout(2_000) { c.pulse.take(2).toList() }
        assertEquals(BeatPulse(9, 3, true), got[0])
        assertEquals(BeatPulse(9, 0, false), got[1])
        assertFalse(got[1].isRunning)
    }

    @Test fun `pulse polling never runs on the collector's thread`() = runBlocking {
        val (c, n) = controller()
        n.scriptPulse(packed(1, 0, true))
        withTimeout(2_000) { c.pulse.take(1).toList() }
        assertNotSame(Thread.currentThread(), n.pulseThread)
    }

    // ── Click del metrónomo (ADR 0031) ───────────────────────────────────────

    @Test fun `click defaults match the engine defaults`() {
        val (c, _) = controller()
        assertEquals(MetronomeSound.CLASSIC, c.metronomeSound.value)
        assertTrue(c.metronomeAccent.value)
        assertEquals(ClickSubdivision.NONE, c.clickSubdivision.value)
    }

    @Test fun `click settings reach the engine as native ids`() {
        val (c, n) = controller()
        c.setMetronomeSound(MetronomeSound.COWBELL)
        c.setMetronomeAccent(false)
        c.setClickSubdivision(ClickSubdivision.TRIPLETS)
        assertEquals(listOf("sound=3", "accent=false", "subdiv=3"), n.snapshot())
        assertEquals(MetronomeSound.COWBELL, c.metronomeSound.value)
        assertFalse(c.metronomeAccent.value)
        assertEquals(ClickSubdivision.TRIPLETS, c.clickSubdivision.value)
    }

    @Test fun `every sound and subdivision maps to a distinct id inside the native range`() {
        assertEquals(MetronomeSound.values().size, MetronomeSound.values().map { it.nativeId }.toSet().size)
        assertEquals((0 until MetronomeSound.values().size).toList(), MetronomeSound.values().map { it.nativeId }.sorted())
        assertEquals(listOf(1, 2, 3, 4), ClickSubdivision.values().map { it.perBeat })
    }

    @Test fun `pending click changes coalesce into one flush carrying the latest value`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.setMetronomeSound(MetronomeSound.WOOD)
        c.setMetronomeSound(MetronomeSound.HAT)
        c.setClickSubdivision(ClickSubdivision.EIGHTHS)
        c.setClickSubdivision(ClickSubdivision.SIXTEENTHS)
        assertEquals(1, exec.queue.size)
        exec.runAll()
        assertEquals(listOf("sound=4", "subdiv=4"), n.snapshot())
    }
}
