package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.runtime.Immutable
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickMode
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.ClickSwing
import com.yeivikas.olyze.eliner.api.transport.CountInLength
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
    /** Sync del tapper: cada toque reinicia el tiempo 1. */
    val tapSync: Boolean,
    /** Mute del tapper: el click se silencia al empezar a tocar y vuelve gradualmente. */
    val tapMute: Boolean,
    /** Swing de la subdivisión del click (ADR 0039). Solo tiene efecto con subdivisión de 2 o 4 por pulso. */
    val clickSwing: ClickSwing,
    /** Compases de conteo previo a grabar (ADR 0035). */
    val countInLength: CountInLength,
    /** Cuándo suena el click: reproduciendo y grabando, solo grabando o solo el conteo. */
    val clickMode: ClickMode,
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
    val onTempoStep: (Float) -> Unit,
    /** Un toque del pad de TAP, con la hora REAL del toque (`uptimeMillis`) y no la de su procesado. */
    val onTap: (eventUptimeMs: Long) -> Unit,
    val onTapSyncChange: (Boolean) -> Unit,
    val onTapMuteChange: (Boolean) -> Unit,
    /** Empieza el nudge (tempo TEMPORAL mientras se mantiene): 1 = acelerar, −1 = frenar. */
    val onNudgeStart: (direction: Int) -> Unit,
    /** Termina el nudge: el tempo vuelve al que había antes. Se llama SIEMPRE, también si el gesto se cancela. */
    val onNudgeEnd: () -> Unit,
    val onMetronomeToggle: () -> Unit,
    val onMetronomeVolumeChange: (Float) -> Unit,
    val onMetronomeSoundChange: (MetronomeSound) -> Unit,
    val onClickSubdivisionChange: (ClickSubdivision) -> Unit,
    val onClickSwingChange: (ClickSwing) -> Unit,
    val onCountInLengthChange: (CountInLength) -> Unit,
    val onClickModeChange: (ClickMode) -> Unit,
    /** Cambia el compás (pulsos y figura); quien lo recibe restablece el patrón de acentos de ese compás. */
    val onTimeSignatureChange: (beats: Int, unit: BeatUnit) -> Unit,
    val onBeatPatternChange: (BeatPattern) -> Unit,
    val onPresetSave: (MetronomePreset) -> Unit,
    val onPresetLoad: (MetronomePreset) -> Unit,
    val onPresetDelete: (MetronomePreset) -> Unit,
    val onDelaySyncToggle: (Boolean) -> Unit,
    val onDelayDivisionChange: (NoteDivision) -> Unit,
)
