package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El BUCLE del reloj MIDI con tiempo virtual: cuenta los pulsos que de verdad salen hacia el dispositivo. Los
 * valores esperados son los de la rejilla ideal (`k · 60000 / bpm / 24` ms), no los que daría el código bajo prueba:
 * con el esquema anterior (intervalo truncado a milisegundos enteros y esperas acumuladas) salían 51 pulsos en 1010 ms
 * a 120 BPM y 589 en 10 010 ms a 140 BPM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MidiClockGeneratorLoopTest {

    private class CountingMidiOut(private val nowMs: () -> Long) : MidiOutputApi {
        val clockTimesMs = mutableListOf<Long>()
        override val connected: StateFlow<Boolean> = MutableStateFlow(false)
        override val statusText: StateFlow<String> = MutableStateFlow("")
        override fun sendNoteOn(channel: Int, note: Int, velocity: Int) {}
        override fun sendNoteOff(channel: Int, note: Int) {}
        override fun sendCC(channel: Int, cc: Int, value: Int) {}
        override fun sendPitchBend(channel: Int, value: Int) {}
        override fun sendClock() { clockTimesMs += nowMs() }
        override fun sendStart() {}
        override fun sendStop() {}
        override fun sendContinue() {}
        override fun sendSongPosition(songPosition: Int) {}
    }

    @Test fun `120 bpm emits one pulse every twenty and a third milliseconds`() = runTest {
        val out = CountingMidiOut { testScheduler.currentTime }
        val generator = MidiClockGenerator(out, MutableStateFlow(120f), backgroundScope) { testScheduler.currentTime * 1_000_000L }
        generator.start()
        advanceTimeBy(1_010L); runCurrent()
        assertEquals("pulsos 0 a 48 (el 48 cae en 1000 ms)", 49, out.clockTimesMs.size)
        assertEquals("el primer pulso sale al arrancar", 0L, out.clockTimesMs.first())
        generator.stop()
    }

    @Test fun `140 bpm does not run fast over ten seconds`() = runTest {
        val out = CountingMidiOut { testScheduler.currentTime }
        val generator = MidiClockGenerator(out, MutableStateFlow(140f), backgroundScope) { testScheduler.currentTime * 1_000_000L }
        generator.start()
        advanceTimeBy(10_010L); runCurrent()
        assertEquals("pulsos 0 a 560 (el 560 cae en 10 000 ms)", 561, out.clockTimesMs.size)
        generator.stop()
    }

    @Test fun `a tempo change applies from the next pulse without restarting the clock`() = runTest {
        val out = CountingMidiOut { testScheduler.currentTime }
        val bpm = MutableStateFlow(120f)
        val generator = MidiClockGenerator(out, bpm, backgroundScope) { testScheduler.currentTime * 1_000_000L }
        generator.start()
        advanceTimeBy(505L); runCurrent()
        val before = out.clockTimesMs.size
        assertEquals("pulsos 0 a 24 a 120 BPM", 25, before)
        bpm.value = 240f
        advanceTimeBy(505L); runCurrent()
        val after = out.clockTimesMs.size - before
        assertTrue("a 240 BPM caben unos 47 pulsos en 505 ms, fueron $after", after in 45..49)
        generator.stop()
    }

    @Test fun `no pulse leaves after stop`() = runTest {
        val out = CountingMidiOut { testScheduler.currentTime }
        val generator = MidiClockGenerator(out, MutableStateFlow(120f), backgroundScope) { testScheduler.currentTime * 1_000_000L }
        generator.start()
        advanceTimeBy(100L); runCurrent()
        generator.stop()
        val atStop = out.clockTimesMs.size
        assertFalse(generator.isRunning)
        advanceTimeBy(5_000L); runCurrent()
        assertEquals(atStop, out.clockTimesMs.size)
        generator.stop()                                    // parar de nuevo es inocuo
        assertEquals(atStop, out.clockTimesMs.size)
    }

    @Test fun `starting twice does not double the clock`() = runTest {
        val out = CountingMidiOut { testScheduler.currentTime }
        val generator = MidiClockGenerator(out, MutableStateFlow(120f), backgroundScope) { testScheduler.currentTime * 1_000_000L }
        generator.start(); generator.start()
        advanceTimeBy(1_010L); runCurrent()
        assertEquals(49, out.clockTimesMs.size)
        generator.stop()
    }

    @Test fun `a restart after a stop emits a first pulse immediately and keeps the grid`() = runTest {
        val out = CountingMidiOut { testScheduler.currentTime }
        val generator = MidiClockGenerator(out, MutableStateFlow(120f), backgroundScope) { testScheduler.currentTime * 1_000_000L }
        generator.start()
        advanceTimeBy(100L); runCurrent()
        generator.stop()
        val stoppedAt = out.clockTimesMs.size
        advanceTimeBy(900L); runCurrent()                   // t = 1000 ms, parado
        generator.start()
        runCurrent()
        assertEquals("el primero sale en el acto", stoppedAt + 1, out.clockTimesMs.size)
        assertEquals(1_000L, out.clockTimesMs.last())
        advanceTimeBy(505L); runCurrent()                   // 24 pulsos más a 120 BPM: 1 + 24 en 500 ms
        assertEquals(stoppedAt + 25, out.clockTimesMs.size)
        generator.stop()
    }
}
