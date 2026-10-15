package com.yeivikas.olyze.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeivikas.olyze.MainViewModel
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.ui.components.tempo.TempoPanel
import com.yeivikas.olyze.ui.components.tempo.TempoPanelActions
import com.yeivikas.olyze.ui.components.tempo.TempoPanelState

/**
 * Une el panel de tempo con el [MainViewModel]: recoge el estado del motor y reenvía cada evento.
 *
 * Existe aparte de `MainScreen` por dos razones: (1) el panel es SIN ESTADO y no debe conocer el
 * ViewModel; (2) el tempo cambia a la velocidad del dedo mientras se gira el dial, y recoger esos
 * flujos aquí hace que solo se recomponga este host, no toda la pantalla (teclado incluido).
 */
@Composable
internal fun TempoPanelHost(
    vm: MainViewModel,
    visible: Boolean,
    pulse: State<BeatPulse>,
    onDismiss: () -> Unit,
) {
    val bpm by vm.tempoBpm.collectAsStateWithLifecycle()
    val beatsPerBar by vm.beatsPerBar.collectAsStateWithLifecycle()
    val beatUnit by vm.beatUnit.collectAsStateWithLifecycle()
    val beatPattern by vm.beatPattern.collectAsStateWithLifecycle()
    val metronomeEnabled by vm.metronomeEnabled.collectAsStateWithLifecycle()
    val metronomeVolume by vm.metronomeVolume.collectAsStateWithLifecycle()
    val metronomeSound by vm.metronomeSound.collectAsStateWithLifecycle()
    val clickSubdivision by vm.clickSubdivision.collectAsStateWithLifecycle()
    val delaySync by vm.delaySync.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val tap by vm.tapStatus.collectAsStateWithLifecycle()

    TempoPanel(
        visible = visible,
        state = TempoPanelState(
            bpm = bpm,
            beatsPerBar = beatsPerBar,
            beatUnit = beatUnit,
            beatPattern = beatPattern,
            metronomeEnabled = metronomeEnabled,
            metronomeVolume = metronomeVolume,
            metronomeSound = metronomeSound,
            clickSubdivision = clickSubdivision,
            delaySync = delaySync,
            presets = presets,
            maxPresets = vm.maxPresets,
            tap = tap,
        ),
        pulse = pulse,
        actions = TempoPanelActions(
            onDismiss = onDismiss,
            onTempoChange = { vm.setTempoBpm(it) },
            onTempoNudge = { vm.nudgeTempo(it) },
            onTap = { vm.tapTempo(it) },
            onMetronomeToggle = { vm.toggleMetronome() },
            onMetronomeVolumeChange = { vm.setMetronomeVolume(it) },
            onMetronomeSoundChange = { vm.setMetronomeSound(it) },
            onClickSubdivisionChange = { vm.setClickSubdivision(it) },
            onTimeSignatureChange = { beats, unit -> vm.setTimeSignature(beats, unit) },
            onBeatPatternChange = { vm.setBeatPattern(it) },
            onPresetSave = { vm.savePreset(it) },
            onPresetLoad = { vm.loadPreset(it) },
            onPresetDelete = { vm.deletePreset(it) },
            onDelaySyncToggle = { vm.setDelaySyncEnabled(it) },
            onDelayDivisionChange = { vm.setDelayDivision(it) },
        ),
    )
}
