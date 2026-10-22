package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickMode
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.ClickSwing
import com.yeivikas.olyze.eliner.api.transport.CountInLength
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Doble de test de [EliNerTransportApi] compartido por los tests de `:app`: guarda el estado en flujos
 * reales y APUNTA cada orden en [log], de modo que un test puede comprobar qué se pidió y en qué orden
 * (incluso entre varios componentes si comparten el mismo [log]).
 *
 * Una única copia: antes cada test escribía la suya y cada miembro nuevo de la API obligaba a tocarlas todas.
 */
class FakeTransport(private val log: MutableList<String> = mutableListOf()) : EliNerTransportApi {

    private val running = MutableStateFlow(false)
    private val tempo = MutableStateFlow(120f)
    private val bar = MutableStateFlow(4)
    private val volume = MutableStateFlow(0.7f)
    private val sound = MutableStateFlow(MetronomeSound.CLASSIC)
    private val pattern = MutableStateFlow(BeatPattern.DEFAULT)
    private val unit = MutableStateFlow(BeatUnit.QUARTER)
    private val swing = MutableStateFlow(ClickSwing.STRAIGHT)
    private val subdivision = MutableStateFlow(ClickSubdivision.NONE)
    private val armed = MutableStateFlow(false)
    private val countIn = MutableStateFlow(CountInLength.ONE_BAR)
    private val mode = MutableStateFlow(ClickMode.ALWAYS)

    override val tempoBpm: StateFlow<Float> = tempo
    override val isRunning: StateFlow<Boolean> = running
    override val beatsPerBar: StateFlow<Int> = bar
    override val metronomeEnabled: StateFlow<Boolean> = MutableStateFlow(false)
    override val metronomeVolume: StateFlow<Float> = volume
    override val metronomeSound: StateFlow<MetronomeSound> = sound
    override val beatPattern: StateFlow<BeatPattern> = pattern
    override val beatUnit: StateFlow<BeatUnit> = unit
    override val clickSubdivision: StateFlow<ClickSubdivision> = subdivision
    override val delaySync: StateFlow<DelayTempoSync> = MutableStateFlow(DelayTempoSync(true, NoteDivision.EIGHTH_DOTTED))
    override val outputLatencyMillis: StateFlow<Float?> = MutableStateFlow(null)
    override val positionUpdates: Flow<Long> = emptyFlow()
    override val clickSwing: StateFlow<ClickSwing> = swing
    override val recordArmed: StateFlow<Boolean> = armed
    override val countInLength: StateFlow<CountInLength> = countIn
    override val clickMode: StateFlow<ClickMode> = mode
    override val pulse: Flow<BeatPulse> = emptyFlow()

    /** Posición que «publica el motor»: la fija el test, o la cambia [locate]. */
    var position: Long = 0L

    override fun setTempo(bpm: Float) { log += "tempo=$bpm"; tempo.value = bpm }
    override fun start() { log += "engine.start"; running.value = true }
    override fun stop() { log += "engine.stop"; running.value = false }
    override fun setBeatsPerBar(beats: Int) { log += "bar=$beats"; bar.value = beats }
    override fun setBeatUnit(unit: BeatUnit) { log += "unit=${unit.denominator}"; this.unit.value = unit }
    override fun setBeatPattern(pattern: BeatPattern) { log += "pattern=$pattern"; this.pattern.value = pattern }
    override fun setMetronomeEnabled(enabled: Boolean) { log += "metronome=$enabled" }
    override fun setMetronomeVolume(volume: Float) { log += "volume=$volume"; this.volume.value = volume }
    override fun setMetronomeSound(sound: MetronomeSound) { log += "sound=$sound"; this.sound.value = sound }
    override fun setMetronomeDuck(level: Float) { log += "duck=$level" }
    override fun resyncBar() { log += "resync" }
    override fun setClickSubdivision(subdivision: ClickSubdivision) { log += "subdivision=$subdivision"; this.subdivision.value = subdivision }
    override fun positionTicks(): Long = position
    override fun locate(ticks: Long) { log += "locate=$ticks"; position = ticks }
    override fun setClickSwing(swing: ClickSwing) { log += "swing=${swing.percent}"; this.swing.value = swing }
    override fun setRecordArmed(armed: Boolean) { log += "recordArmed=$armed"; this.armed.value = armed }
    override fun setCountInLength(length: CountInLength) { log += "countIn=${length.bars}"; this.countIn.value = length }
    override fun setClickMode(mode: ClickMode) { log += "clickMode=$mode"; this.mode.value = mode }
    override fun setDelayTempoSync(enabled: Boolean, division: NoteDivision) { log += "delaySync=$enabled,$division" }
}
