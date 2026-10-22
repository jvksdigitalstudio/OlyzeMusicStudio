package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yeivikas.olyze.eliner.api.transport.ClickMode
import com.yeivikas.olyze.eliner.api.transport.CountInLength

/**
 * Grabación: conteo previo y cuándo suena el click (ADR 0035). Es la sección donde el metrónomo se relaciona con
 * grabar; el sonido, el volumen y la subdivisión del click siguen en [MetronomeSection].
 *
 *  - **CONTEO:** cuántos compases suenan antes de empezar a grabar. Suena aunque el botón del metrónomo esté
 *    apagado: el conteo existe precisamente para oírse.
 *  - **CLICK SUENA:** en qué momentos puede sonar el metrónomo cuando está activado (siempre, solo al grabar, o
 *    solo en el conteo).
 *
 * Solo presenta el estado y emite eventos ([TempoPanelState] / [TempoPanelActions]).
 */
@Composable
internal fun RecordingSection(
    state: TempoPanelState,
    actions: TempoPanelActions,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "GRABACIÓN", modifier = modifier) {
        LabeledChips("CONTEO PREVIO") {
            CountInLength.values().forEach { length ->
                TempoChip(
                    label = length.label(),
                    selected = state.countInLength == length,
                    description = length.description(),
                    onClick = { actions.onCountInLengthChange(length) },
                )
            }
        }
        LabeledChips("CLICK SUENA") {
            ClickMode.values().forEach { mode ->
                TempoChip(
                    label = mode.label(),
                    selected = state.clickMode == mode,
                    description = mode.description(),
                    onClick = { actions.onClickModeChange(mode) },
                )
            }
        }
    }
}
