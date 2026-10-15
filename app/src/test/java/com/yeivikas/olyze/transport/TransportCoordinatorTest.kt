package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fija la SECUENCIA de reproducir/parar. Todos los dobles escriben en un único
 * registro común, de modo que el test comprueba el ORDEN entre componentes distintos
 * (motor, MIDI, reloj, silenciado), que es justo lo que importa.
 */
class TransportCoordinatorTest {

    private val log = mutableListOf<String>()

    private inner class FakeMidiOut : MidiOutputApi {
        override val connected: StateFlow<Boolean> = MutableStateFlow(false)
        override val statusText: StateFlow<String> = MutableStateFlow("")
        override fun sendNoteOn(channel: Int, note: Int, velocity: Int) {}
        override fun sendNoteOff(channel: Int, note: Int) {}
        override fun sendCC(channel: Int, cc: Int, value: Int) {}
        override fun sendPitchBend(channel: Int, value: Int) {}
        override fun sendClock() {}
        override fun sendStart() { log += "midi.start" }
        override fun sendStop() { log += "midi.stop" }
        override fun sendContinue() {}
    }

    private val clock = object : MidiClock {
        override fun start() { log += "clock.start" }
        override fun stop() { log += "clock.stop" }
    }

    private val coordinator = TransportCoordinator(FakeTransport(log), FakeMidiOut(), clock) { log += "notes.off" }

    @Test fun `starting runs engine, then MIDI START, then the clock`() {
        coordinator.togglePlay()
        assertEquals(listOf("engine.start", "midi.start", "clock.start"), log)
    }

    @Test fun `stopping halts the clock first, then engine, MIDI STOP and finally silences notes`() {
        coordinator.togglePlay()
        log.clear()
        coordinator.togglePlay()
        assertEquals(listOf("clock.stop", "engine.stop", "midi.stop", "notes.off"), log)
    }

    @Test fun `toggle alternates between start and stop`() {
        coordinator.togglePlay(); coordinator.togglePlay(); coordinator.togglePlay()
        assertEquals(
            listOf(
                "engine.start", "midi.start", "clock.start",
                "clock.stop", "engine.stop", "midi.stop", "notes.off",
                "engine.start", "midi.start", "clock.start",
            ),
            log,
        )
    }

    @Test fun `rewind stops with the same sequence as stop`() {
        coordinator.togglePlay()
        log.clear()
        coordinator.rewind()
        assertEquals(listOf("clock.stop", "engine.stop", "midi.stop", "notes.off"), log)
    }

    @Test fun `rewind while already stopped is safe and idempotent`() {
        coordinator.rewind()
        assertEquals(listOf("clock.stop", "engine.stop", "midi.stop", "notes.off"), log)
    }
}
