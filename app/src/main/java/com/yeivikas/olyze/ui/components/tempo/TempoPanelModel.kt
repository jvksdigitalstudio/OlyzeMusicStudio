package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.runtime.Immutable
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision

/**
 * Lo que el panel de tempo MUESTRA. Es una instantánea del estado del motor: el panel no guarda
 * nada propio (salvo detalles visuales como una sección desplegada).
 */
@Immutable
internal data class TempoPanelState(
    val bpm: Float,
    val beatsPerBar: Int,
    val metronomeEnabled: Boolean,
    val metronomeVolume: Float,
    val metronomeSound: MetronomeSound,
    val metronomeAccent: Boolean,
    val clickSubdivision: ClickSubdivision,
    val delaySync: DelayTempoSync,
)

/**
 * Lo que el panel PUEDE PEDIR. Agrupar los eventos aquí (en vez de 14 lambdas sueltas) hace que
 * añadir un control sea tocar un sitio, y que el panel se pueda previsualizar y probar con acciones
 * de mentira.
 */
internal class TempoPanelActions(
    val onDismiss: () -> Unit,
    /** Tempo absoluto (el dial). */
    val onTempoChange: (Float) -> Unit,
    /** Paso relativo (±0,1, ±1, ±10): lo calcula quien lo recibe sobre el tempo ACTUAL del motor. */
    val onTempoNudge: (Float) -> Unit,
    val onTap: () -> Unit,
    val onMetronomeToggle: () -> Unit,
    val onMetronomeVolumeChange: (Float) -> Unit,
    val onMetronomeSoundChange: (MetronomeSound) -> Unit,
    val onMetronomeAccentChange: (Boolean) -> Unit,
    val onClickSubdivisionChange: (ClickSubdivision) -> Unit,
    val onBeatsPerBarChange: (Int) -> Unit,
    val onDelaySyncToggle: (Boolean) -> Unit,
    val onDelayDivisionChange: (NoteDivision) -> Unit,
)
