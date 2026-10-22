package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.transport.BeatLevel
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickMode
import com.yeivikas.olyze.eliner.api.transport.ClickSwing
import com.yeivikas.olyze.eliner.api.transport.CountInLength
import com.yeivikas.olyze.eliner.api.transport.TransportPosition
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
        override fun setMetronomeBeatLevels(low: Int, high: Int) = rec("levels=$low,$high")
        override fun setBeatUnit(denominator: Int) = rec("unit=$denominator")
        override fun setMetronomeDuck(level: Float) = rec("duck=$level")
        override fun resyncBar() = rec("resync")
        override fun setMetronomeSubdivision(perBeat: Int) = rec("subdiv=$perBeat")
        override fun setClickSwing(percent: Int) = rec("swing=$percent")
        override fun setRecordArmed(armed: Boolean) = rec("armed=$armed")
        override fun setCountInBars(bars: Int) = rec("countin=$bars")
        override fun setClickMode(mode: Int) = rec("clickmode=$mode")
        override fun setPosition(ticks: Long) = rec("position=$ticks")
        /** Latencia que «mide» el motor: negativa = sin medida. */
        @Volatile var latencyMs = -1f
        override fun sampleOutputLatency(): Float = latencyMs
        /** Posición que «publica el motor»: la fija el test. */
        @Volatile var enginePosition = 0L
        override fun readPosition(): Long = enginePosition
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

    private fun controller(
        executor: Executor = direct,
        nanoClock: () -> Long = System::nanoTime,
    ): Pair<TransportController, FakeNative> {
        val n = FakeNative()
        return TransportController(n, executor, nanoClock) to n
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

    @Test fun `a stop and a start merged into one flush still restart the transport`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.start(); exec.runAll(); n.clear()              // el motor ya está en marcha
        c.stop(); c.start()                              // el hilo del motor estaba ocupado: se fusionan
        exec.runAll()
        assertEquals(listOf("running=false", "running=true"), n.snapshot())
    }

    @Test fun `a plain start after an already flushed stop sends no extra stop`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.start(); exec.runAll()
        c.stop(); exec.runAll(); n.clear()
        c.start(); exec.runAll()
        assertEquals(listOf("running=true"), n.snapshot())
    }

    @Test fun `a merged start stop start pair on a stopped engine ends running with the stop first`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.start(); c.stop(); c.start()
        exec.runAll()
        assertEquals(listOf("running=false", "running=true"), n.snapshot())
    }

    @Test fun `a merged stop that stays stopped sends a single stop`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.start(); exec.runAll(); n.clear()
        c.stop(); c.start(); c.stop()
        exec.runAll()
        assertEquals(listOf("running=false"), n.snapshot())
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
            listOf("tempo=97.0", "bar=3", "unit=4", "vol=0.4", "metro=true", "sound=0", "levels=43691,43690", "subdiv=1", "swing=50", "duck=1.0", "sync=true,2.0", "clickmode=0", "countin=1", "armed=false", "running=true"),
            n.snapshot(),
        )
    }

    @Test fun `syncToNative with defaults sends the defaults`() {
        val (c, n) = controller()
        c.syncToNative()
        assertEquals(
            listOf("tempo=120.0", "bar=4", "unit=4", "vol=0.7", "metro=false", "sound=0", "levels=43691,43690", "subdiv=1", "swing=50", "duck=1.0", "sync=true,0.75", "running=false"),
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
            listOf("tempo=101.0", "bar=4", "unit=4", "vol=0.7", "metro=true", "sound=0", "levels=43691,43690", "subdiv=1", "swing=50", "duck=1.0", "sync=true,0.75", "clickmode=0", "countin=1", "armed=false", "running=true"),
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
        assertEquals(BeatPattern.DEFAULT, c.beatPattern.value)
        assertEquals(BeatUnit.QUARTER, c.beatUnit.value)
        assertEquals(ClickSubdivision.NONE, c.clickSubdivision.value)
    }

    @Test fun `click settings reach the engine as native ids`() {
        val (c, n) = controller()
        val allMedium = BeatPattern.DEFAULT.with(0, BeatLevel.MEDIUM)
        c.setMetronomeSound(MetronomeSound.COWBELL)
        c.setBeatPattern(allMedium)
        c.setClickSubdivision(ClickSubdivision.TRIPLETS)
        assertEquals(listOf("sound=3", "levels=43690,43690", "subdiv=3"), n.snapshot())
        assertEquals(MetronomeSound.COWBELL, c.metronomeSound.value)
        assertEquals(allMedium, c.beatPattern.value)
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

    // ── Patrón por tiempo y unidad de pulso (ADR 0032) ───────────────────────

    @Test fun `beat pattern travels as ONE call with both packed words`() {
        val (c, n) = controller()
        // Tiempo 0 mudo, tiempo 1 suave, 2 normal, 3 acento, y el tiempo 8 acentuado (segunda palabra).
        val p = BeatPattern.DEFAULT
            .with(0, BeatLevel.MUTE).with(1, BeatLevel.SOFT).with(2, BeatLevel.MEDIUM).with(3, BeatLevel.ACCENT)
            .with(8, BeatLevel.ACCENT)
        c.setBeatPattern(p)
        assertEquals(listOf("levels=${p.packedLow},${p.packedHigh}"), n.snapshot())
        assertEquals(p, c.beatPattern.value)
    }

    @Test fun `beat unit reaches the engine as its denominator`() {
        val (c, n) = controller()
        c.setBeatUnit(BeatUnit.EIGHTH)
        assertEquals(listOf("unit=8"), n.snapshot())
        assertEquals(BeatUnit.EIGHTH, c.beatUnit.value)
    }

    @Test fun `pending pattern and unit changes coalesce into one flush carrying the latest values`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.setBeatUnit(BeatUnit.EIGHTH)
        c.setBeatUnit(BeatUnit.SIXTEENTH)
        c.setBeatPattern(BeatPattern.DEFAULT.with(1, BeatLevel.MUTE))
        c.setBeatPattern(BeatPattern.DEFAULT.with(2, BeatLevel.MUTE))
        assertEquals(1, exec.queue.size)
        exec.runAll()
        val expected = BeatPattern.DEFAULT.with(2, BeatLevel.MUTE)
        assertEquals(listOf("unit=16", "levels=${expected.packedLow},${expected.packedHigh}"), n.snapshot())
    }

    // ── Tap tempo: Mute (duck) y Sync (reinicio de compás) — ADR 0033 ─────────

    @Test fun `duck reaches the engine and is clamped to 0 to 1`() {
        val (c, n) = controller()
        c.setMetronomeDuck(0.25f)
        c.setMetronomeDuck(-3f)
        c.setMetronomeDuck(9f)
        assertEquals(listOf("duck=0.25", "duck=0.0", "duck=1.0"), n.snapshot())
    }

    @Test fun `a non finite duck is ignored and sends nothing`() {
        val (c, n) = controller()
        c.setMetronomeDuck(Float.NaN)
        c.setMetronomeDuck(Float.POSITIVE_INFINITY)
        assertEquals(emptyList<String>(), n.snapshot())
    }

    @Test fun `the current duck is replayed when the engine is recreated`() {
        val (c, n) = controller()
        c.setMetronomeDuck(0.4f)
        n.clear()
        c.syncToNative()
        assertTrue("el duck vigente se reenvía al nuevo motor", n.snapshot().contains("duck=0.4"))
    }

    @Test fun `resync reaches the engine AFTER an earlier tempo change`() {
        val exec = ManualExecutor()
        val (c, n) = controller(exec)
        c.setTempo(100f)
        c.resyncBar()
        exec.runAll()
        val calls = n.snapshot()
        assertEquals(listOf("tempo=100.0", "resync"), calls)
    }

    // ── Grabación y conteo previo (ADR 0035) ────────────────────────────────

    @Test fun `record armed, count-in and click mode are desired state with sensible defaults`() {
        val (c, _) = controller()
        assertFalse(c.recordArmed.value)
        assertEquals(CountInLength.ONE_BAR, c.countInLength.value)
        assertEquals(ClickMode.ALWAYS, c.clickMode.value)
    }

    @Test fun `each new setter sends exactly its own order with the native value`() {
        val (c, n) = controller()
        c.setRecordArmed(true)
        c.setCountInLength(CountInLength.FOUR_BARS)
        c.setClickMode(ClickMode.COUNT_IN_ONLY)
        assertEquals(listOf("armed=true", "countin=4", "clickmode=2"), n.snapshot())
        assertTrue(c.recordArmed.value)
        assertEquals(CountInLength.FOUR_BARS, c.countInLength.value)
        assertEquals(ClickMode.COUNT_IN_ONLY, c.clickMode.value)
    }

    @Test fun `arming and starting in one burst sends the arming BEFORE the start`() {
        // El motor decide si hay conteo en el instante de arrancar: el orden es parte del contrato.
        val manual = ManualExecutor()
        val (c, n) = controller(manual)
        c.setCountInLength(CountInLength.TWO_BARS)
        c.setRecordArmed(true)
        c.start()
        manual.runAll()
        val calls = n.snapshot()
        assertTrue(calls.indexOf("countin=2") in 0 until calls.indexOf("running=true"))
        assertTrue(calls.indexOf("armed=true") in 0 until calls.indexOf("running=true"))
    }

    @Test fun `no new setter touches the engine on the caller thread`() {
        val manual = ManualExecutor()
        val (c, n) = controller(manual)
        c.setRecordArmed(true); c.setCountInLength(CountInLength.OFF); c.setClickMode(ClickMode.RECORD_ONLY)
        assertEquals(emptyList<String>(), n.snapshot())
        manual.runAll()
        assertEquals(listOf("clickmode=1", "countin=0", "armed=true"), n.snapshot())
    }

    @Test fun `repeated sets of the same new setting coalesce into one native call`() {
        val manual = ManualExecutor()
        val (c, n) = controller(manual)
        c.setCountInLength(CountInLength.TWO_BARS); c.setCountInLength(CountInLength.FOUR_BARS)
        c.setRecordArmed(true); c.setRecordArmed(false)
        manual.runAll()
        assertEquals(listOf("countin=4", "armed=false"), n.snapshot())
    }

    // ── Swing del click (ADR 0039) ───────────────────────────────────────────

    @Test fun `click swing defaults to straight and sends its percentage`() {
        val (c, n) = controller()
        assertEquals(ClickSwing.STRAIGHT, c.clickSwing.value)
        c.setClickSwing(ClickSwing.TRIPLET)
        assertEquals(ClickSwing.TRIPLET, c.clickSwing.value)
        assertEquals(listOf("swing=67"), n.snapshot())
    }

    @Test fun `swing coalesces with the other click settings in one flush`() {
        val manual = ManualExecutor()
        val (c, n) = controller(manual)
        c.setClickSwing(ClickSwing.LIGHT); c.setClickSwing(ClickSwing.HARD)
        manual.runAll()
        assertEquals(listOf("swing=75"), n.snapshot())
    }

    // ── Posición (ADR 0036) ─────────────────────────────────────────────────

    @Test fun `locate sends the position as a one-shot order`() {
        val (c, n) = controller()
        c.locate(1920L)
        assertEquals(listOf("position=1920"), n.snapshot())
    }

    @Test fun `locate then start sends the position BEFORE the start in the same flush`() {
        val manual = ManualExecutor()
        val (c, n) = controller(manual)
        c.locate(0L)
        c.start()
        manual.runAll()
        assertEquals(listOf("position=0", "running=true"), n.snapshot())
    }

    @Test fun `arming, locating and starting sends armed, position and then the start`() {
        // El conteo parte de la posición: armado y posición llegan antes que el arranque, en este orden.
        val manual = ManualExecutor()
        val (c, n) = controller(manual)
        c.setRecordArmed(true)
        c.locate(3840L)
        c.start()
        manual.runAll()
        assertEquals(listOf("armed=true", "position=3840", "running=true"), n.snapshot())
    }

    @Test fun `several locates before the engine hears them melt into the last one`() {
        val manual = ManualExecutor()
        val (c, n) = controller(manual)
        c.locate(100L); c.locate(200L); c.locate(300L)
        manual.runAll()
        assertEquals(listOf("position=300"), n.snapshot())
    }

    @Test fun `locate clamps to the range the engine accepts`() {
        val (c, n) = controller()
        c.locate(-50L)
        c.locate(Long.MAX_VALUE)
        assertEquals(listOf("position=0", "position=${TransportPosition.MAX_TICKS}"), n.snapshot())
    }

    @Test fun `locate is NOT replayed when the engine is recreated`() {
        val (c, n) = controller()
        c.locate(5000L)
        n.clear()
        c.syncToNative()
        assertEquals(false, n.snapshot().any { it.startsWith("position=") })
    }

    @Test fun `positionTicks reads the position the engine published`() {
        val (c, n) = controller()
        n.enginePosition = 12345L
        assertEquals(12345L, c.positionTicks())
    }

    @Test fun `locate does not touch the engine on the caller thread when the executor is busy`() {
        val manual = ManualExecutor()
        val (c, n) = controller(manual)
        c.locate(960L)
        assertEquals(emptyList<String>(), n.snapshot())
        manual.runAll()
        assertEquals(listOf("position=960"), n.snapshot())
    }

    // ── Latencia de salida y compensación del indicador (ADR 0037) ──────────

    @Test fun `output latency is unknown until the engine reports one`() = runBlocking {
        val (c, _) = controller()
        assertEquals(null, c.outputLatencyMillis.value)
        withTimeout(2_000) { c.pulse.take(1).toList() }
        assertEquals(null, c.outputLatencyMillis.value)          // el motor dijo -1: sigue sin medida
    }

    @Test fun `output latency is published while the pulse is being collected`() = runBlocking {
        val (c, n) = controller()
        n.latencyMs = 12.5f
        withTimeout(2_000) { c.pulse.take(1).toList() }
        assertEquals(12.5f, c.outputLatencyMillis.value)
    }

    @Test fun `a measured latency that disappears goes back to unknown`() = runBlocking {
        val (c, n) = controller()
        n.latencyMs = 20f
        withTimeout(2_000) { c.pulse.take(1).toList() }
        assertEquals(20f, c.outputLatencyMillis.value)
        n.latencyMs = -1f
        // El siguiente muestreo llega a los LATENCY_SAMPLE_EVERY_POLLS sondeos: se fuerza con una colección nueva.
        withTimeout(2_000) { c.pulse.take(1).toList() }
        assertEquals(null, c.outputLatencyMillis.value)
    }

    @Test fun `with a known latency the pulse is held until it would be heard`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val (c, n) = controller(nanoClock = { now.get() })
        n.latencyMs = 50f                                         // 50 ms
        // Con el reloj congelado, el pulso se ha observado pero aún no ha «sonado»: no sale.
        assertEquals(null, withTimeoutOrNull(300) { c.pulse.take(1).toList() })
        // Pasan 60 ms (más que la latencia): sale.
        val result = async(Dispatchers.Default) { withTimeout(2_000) { c.pulse.take(1).toList() } }
        delay(100)
        now.set(60_000_000L)
        assertEquals(BeatPulse.IDLE, result.await().single())
    }

    @Test fun `without a measured latency pulses are not delayed`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(0L)    // reloj congelado: si hubiera retardo, no saldría
        val (c, _) = controller(nanoClock = { now.get() })
        assertEquals(BeatPulse.IDLE, withTimeout(2_000) { c.pulse.take(1).toList() }.single())
    }

    // ── Sondeo de la posición para la UI ─────────────────────────────────────

    @Test fun `position updates emit the engine position`() = runBlocking {
        val (c, n) = controller()
        n.enginePosition = 4800L
        assertEquals(4800L, withTimeout(2_000) { c.positionUpdates.take(1).toList() }.single())
    }

    @Test fun `position updates emit only when the position changes`() = runBlocking {
        val (c, n) = controller()
        n.enginePosition = 100L
        val result = async(Dispatchers.Default) { withTimeout(3_000) { c.positionUpdates.take(2).toList() } }
        delay(150)                                   // varios sondeos con la MISMA posición: no emite repetidos
        n.enginePosition = 200L
        assertEquals(listOf(100L, 200L), result.await())
    }

    @Test fun `resync is an order, not state - two calls are two restarts`() {
        val (c, n) = controller()
        c.resyncBar()
        c.resyncBar()
        assertEquals(listOf("resync", "resync"), n.snapshot())
    }

    @Test fun `resync is not replayed when the engine is recreated`() {
        val (c, n) = controller()
        c.resyncBar()
        n.clear()
        c.syncToNative()
        assertFalse(n.snapshot().contains("resync"))
    }
}
