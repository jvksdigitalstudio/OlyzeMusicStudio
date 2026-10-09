package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fija la SECUENCIA de reproducir/parar. Todos los dobles escriben en un único
 * registro común, de modo que el test comprueba el ORDEN entre componentes distintos
 * (motor, MIDI, reloj, silenciado), que es justo lo que importa.
 */
class TransportCoordinatorTest {

    private val log = mutableListOf<String>()

    private inner class FakeTransport : EliNerTransportApi {
        private val running = MutableStateFlow(false)
        override val tempoBpm: StateFlow<Float> = MutableStateFlow(120f)
        override val isRunning: StateFlow<Boolean> = running
        override val beatsPerBar: StateFlow<Int> = MutableStateFlow(4)
        override val metronomeEnabled: StateFlow<Boolean> = MutableStateFlow(false)
        override val metronomeVolume: StateFlow<Float> = MutableStateFlow(0.7f)
        override val metronomeSound: StateFlow<MetronomeSound> = MutableStateFlow(MetronomeSound.CLASSIC)
        override val metronomeAccent: StateFlow<Boolean> = MutableStateFlow(true)
        override val clickSubdivision: StateFlow<ClickSubdivision> = MutableStateFlow(ClickSubdivision.NONE)
        override val delaySync: StateFlow<DelayTempoSync> = MutableStateFlow(DelayTempoSync(true, NoteDivision.EIGHTH_DOTTED))
        override val pulse: Flow<BeatPulse> = emptyFlow()
        override fun setTempo(bpm: Float) {}
        override fun start() { log += "engine.start"; running.value = true }
        override fun stop() { log += "engine.stop"; running.value = false }
        override fun setBeatsPerBar(beats: Int) {}
        override fun setMetronomeEnabled(enabled: Boolean) {}
        override fun setMetronomeVolume(volume: Float) {}
        override fun setMetronomeSound(sound: MetronomeSound) {}
        override fun setMetronomeAccent(enabled: Boolean) {}
        override fun setClickSubdivision(subdivision: ClickSubdivision) {}
        override fun setDelayTempoSync(enabled: Boolean, division: NoteDivision) {}
    }

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

    private val coordinator = TransportCoordinator(FakeTransport(), FakeMidiOut(), clock) { log += "notes.off" }

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
