package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fija la SECUENCIA de reproducir, pausar, rebobinar y grabar. Todos los dobles escriben en un único registro
 * común, de modo que el test comprueba el ORDEN entre componentes distintos (motor, MIDI, reloj, silenciado),
 * que es justo lo que importa.
 */
class TransportCoordinatorTest {

    private val log = mutableListOf<String>()
    private val transport = FakeTransport(log)

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
        override fun sendContinue() { log += "midi.continue" }
        override fun sendSongPosition(songPosition: Int) { log += "midi.spp=$songPosition" }
    }

    private val clock = object : MidiClock {
        override fun start() { log += "clock.start" }
        override fun stop() { log += "clock.stop" }
    }

    private val coordinator = TransportCoordinator(transport, FakeMidiOut(), clock) { log += "notes.off" }

    private val pauseSequence =
        listOf("recordArmed=false", "clock.stop", "engine.stop", "midi.stop", "notes.off")

    // ── Reproducir y pausar ──

    @Test fun `playing from the beginning runs engine, then MIDI START, then the clock`() {
        coordinator.togglePlay()
        assertEquals(listOf("engine.start", "midi.start", "clock.start"), log)
    }

    @Test fun `pausing halts the clock first, then engine, MIDI STOP and finally silences notes`() {
        coordinator.togglePlay()
        log.clear()
        coordinator.togglePlay()
        assertEquals(pauseSequence, log)
    }

    @Test fun `pausing disarms the recording because recording ends with the transport`() {
        coordinator.toggleRecord()
        assertEquals(true, transport.recordArmed.value)
        coordinator.togglePlay()
        assertEquals(false, transport.recordArmed.value)
    }

    @Test fun `resuming from a paused position sends SPP then CONTINUE, not START`() {
        coordinator.togglePlay()
        coordinator.togglePlay()                 // pausa
        transport.position = 1920L               // el motor se paró en 2 negras = 8 semicorcheas
        log.clear()
        coordinator.togglePlay()
        assertEquals(listOf("engine.start", "midi.spp=8", "midi.continue", "clock.start"), log)
    }

    @Test fun `SPP rounds the position down to a sixteenth note`() {
        transport.position = 959L                // 3 semicorcheas y 239 ticks
        coordinator.togglePlay()
        assertEquals(listOf("engine.start", "midi.spp=3", "midi.continue", "clock.start"), log)
    }

    @Test fun `a position at the very beginning still sends START`() {
        transport.position = 0L
        coordinator.togglePlay()
        assertEquals(listOf("engine.start", "midi.start", "clock.start"), log)
    }

    @Test fun `play and pause alternate with the right MIDI each time`() {
        coordinator.togglePlay()                                  // desde 0: START
        coordinator.togglePlay()                                  // pausa
        transport.position = 960L
        coordinator.togglePlay()                                  // reanuda: SPP 4 + CONTINUE
        assertEquals(
            listOf("engine.start", "midi.start", "clock.start") + pauseSequence +
                listOf("engine.start", "midi.spp=4", "midi.continue", "clock.start"),
            log,
        )
    }

    // ── Rebobinar ──

    @Test fun `rewinding while playing jumps to the start WITHOUT stopping and sends START`() {
        coordinator.togglePlay()
        log.clear()
        coordinator.rewind()
        assertEquals(listOf("locate=0", "midi.start"), log)
        assertEquals(true, transport.isRunning.value)
    }

    @Test fun `rewinding while stopped locates to 0 and cues the external gear with SPP 0`() {
        coordinator.rewind()
        assertEquals(listOf("locate=0", "midi.spp=0"), log)
    }

    @Test fun `play right after a rewind starts from the beginning even if the engine has not applied it yet`() {
        coordinator.togglePlay(); coordinator.togglePlay()        // sonó y se pausó
        transport.position = 4800L
        coordinator.rewind()
        transport.position = 4800L                                // el motor AÚN no aplicó el locate: lectura vieja
        log.clear()
        coordinator.togglePlay()
        assertEquals(listOf("engine.start", "midi.start", "clock.start"), log)
    }

    @Test fun `the rewind memory is used once and the next resume reads the engine again`() {
        coordinator.rewind()
        coordinator.togglePlay()                                  // consume la memoria
        coordinator.togglePlay()                                  // pausa
        transport.position = 1920L
        log.clear()
        coordinator.togglePlay()
        assertEquals(listOf("engine.start", "midi.spp=8", "midi.continue", "clock.start"), log)
    }

    @Test fun `rewinding while stopped and then pausing does not leave a stale rewind behind`() {
        coordinator.rewind()
        coordinator.togglePlay()                                  // arranca desde 0
        coordinator.rewind()                                      // sonando: no activa la memoria
        coordinator.togglePlay()                                  // pausa
        transport.position = 2880L
        log.clear()
        coordinator.togglePlay()
        assertEquals(listOf("engine.start", "midi.spp=12", "midi.continue", "clock.start"), log)
    }

    // ── Grabar ──

    @Test fun `recording from stopped arms BEFORE starting so the engine can count in`() {
        coordinator.toggleRecord()
        assertEquals(listOf("recordArmed=true", "engine.start", "midi.start", "clock.start"), log)
    }

    @Test fun `arming while already playing does not start again`() {
        coordinator.togglePlay()
        log.clear()
        coordinator.toggleRecord()
        assertEquals(listOf("recordArmed=true"), log)
    }

    @Test fun `pressing record while recording disarms but keeps playing`() {
        coordinator.toggleRecord()
        log.clear()
        coordinator.toggleRecord()
        assertEquals(listOf("recordArmed=false"), log)
        assertEquals(true, transport.isRunning.value)
    }

    @Test fun `recording from a paused position cues the receiver at that position`() {
        transport.position = 3840L
        coordinator.toggleRecord()
        assertEquals(listOf("recordArmed=true", "engine.start", "midi.spp=16", "midi.continue", "clock.start"), log)
    }
}
