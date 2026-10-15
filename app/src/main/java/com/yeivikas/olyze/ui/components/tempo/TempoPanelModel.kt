package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.runtime.Immutable
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import com.yeivikas.olyze.transport.MetronomePreset
import com.yeivikas.olyze.transport.TapStatus

/**
 * Lo que el panel de tempo MUESTRA. Es una instantánea del estado del motor: el panel no guarda
 * nada propio (salvo detalles visuales como una sección desplegada).
 */
@Immutable
internal data class TempoPanelState(
    val bpm: Float,
    val beatsPerBar: Int,
    val beatUnit: BeatUnit,
    val beatPattern: BeatPattern,
    val metronomeEnabled: Boolean,
    val metronomeVolume: Float,
    val metronomeSound: MetronomeSound,
    val clickSubdivision: ClickSubdivision,
    val delaySync: DelayTempoSync,
    val presets: List<MetronomePreset>,
    val maxPresets: Int,
    /** Medición de tap tempo en curso (toques, tempo estimado, fiabilidad). */
    val tap: TapStatus,
) {
    /** La configuración actual como preset: lo que GUARDAR guardaría, y con lo que se marca el preset activo. */
    val current: MetronomePreset
        get() = MetronomePreset(bpm, beatsPerBar, beatUnit, beatPattern, metronomeSound, clickSubdivision, metronomeVolume)
}

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
    /** Un toque del pad de TAP, con la hora REAL del toque (`uptimeMillis`) y no la de su procesado. */
    val onTap: (eventUptimeMs: Long) -> Unit,
    val onMetronomeToggle: () -> Unit,
    val onMetronomeVolumeChange: (Float) -> Unit,
    val onMetronomeSoundChange: (MetronomeSound) -> Unit,
    val onClickSubdivisionChange: (ClickSubdivision) -> Unit,
    /** Cambia el compás (pulsos y figura); quien lo recibe restablece el patrón de acentos de ese compás. */
    val onTimeSignatureChange: (beats: Int, unit: BeatUnit) -> Unit,
    val onBeatPatternChange: (BeatPattern) -> Unit,
    val onPresetSave: (MetronomePreset) -> Unit,
    val onPresetLoad: (MetronomePreset) -> Unit,
    val onPresetDelete: (MetronomePreset) -> Unit,
    val onDelaySyncToggle: (Boolean) -> Unit,
    val onDelayDivisionChange: (NoteDivision) -> Unit,
)
