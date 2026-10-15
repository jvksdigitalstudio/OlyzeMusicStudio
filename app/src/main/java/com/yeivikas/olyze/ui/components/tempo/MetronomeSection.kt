package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import com.yeivikas.olyze.ui.theme.FlBorder
import com.yeivikas.olyze.ui.theme.FlMuted
import com.yeivikas.olyze.ui.theme.FlPurple
import com.yeivikas.olyze.ui.theme.FlPurpleLight
import com.yeivikas.olyze.ui.theme.FlText
import kotlin.math.roundToInt

/**
 * Metrónomo: ON/OFF y volumen, sonido del click y subdivisión. (La intensidad de cada tiempo, el antiguo
 * "acento", vive en [MeterSection] con el patrón del compás.)
 *
 * Solo presenta el estado y emite eventos ([TempoPanelState] / [TempoPanelActions]).
 */
@Composable
internal fun MetronomeSection(
    state: TempoPanelState,
    actions: TempoPanelActions,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "METRÓNOMO",
        modifier = modifier,
        trailing = {
            TempoChip(
                label = if (state.metronomeEnabled) "ON" else "OFF",
                selected = state.metronomeEnabled,
                description = "Metrónomo, ${if (state.metronomeEnabled) "activado" else "desactivado"}",
                onClick = actions.onMetronomeToggle,
            )
        },
    ) {
        // Volumen
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("VOL", style = monoStyle(10.sp, FlMuted, letterSpacing = 1.sp))
            Slider(
                value = state.metronomeVolume,
                onValueChange = actions.onMetronomeVolumeChange,
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Volumen del metrónomo" },
                colors = SliderDefaults.colors(
                    thumbColor = FlPurpleLight,
                    activeTrackColor = FlPurple,
                    inactiveTrackColor = FlBorder,
                ),
            )
            Text(
                text = "${(state.metronomeVolume * 100).roundToInt()}%",
                style = monoStyle(11.sp, FlText),
                modifier = Modifier.widthIn(min = 36.dp),
            )
        }

        // Sonido
        LabeledChips("SONIDO") {
            MetronomeSound.values().forEach { sound ->
                TempoChip(
                    label = sound.label(),
                    selected = state.metronomeSound == sound,
                    description = "Sonido del click: ${sound.label()}",
                    onClick = { actions.onMetronomeSoundChange(sound) },
                )
            }
        }

        // Subdivisión
        LabeledChips("SUBDIVISIÓN") {
            ClickSubdivision.values().forEach { sub ->
                TempoChip(
                    label = sub.label(),
                    selected = state.clickSubdivision == sub,
                    description = sub.description(),
                    onClick = { actions.onClickSubdivisionChange(sub) },
                )
            }
        }
    }
}
