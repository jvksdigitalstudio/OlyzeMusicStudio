package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.ui.theme.FlPurpleLight

/** Pulsos por compás que se ofrecen (el motor admite 1 a 16). */
private val PulseCounts = (1..BeatPattern.MAX_BEATS).toList()

/**
 * Compás: el patrón de intensidad por tiempo (que además es el indicador de pulso), el número de pulsos y
 * la figura que cuenta cada pulso (el denominador: 1/4, 1/8…), como el selector de compás de un metrónomo
 * profesional.
 *
 * Cambiar el número de pulsos o la figura RESTABLECE el patrón al propio de ese compás (ver
 * `MainViewModel.setTimeSignature`); el botón RESET lo restablece sin cambiar de compás. El tempo sigue
 * siendo en negras por minuto: la figura solo cambia la duración de cada pulso (ver [BeatUnit]).
 */
@Composable
internal fun MeterSection(
    beatsPerBar: Int,
    beatUnit: BeatUnit,
    beatPattern: BeatPattern,
    pulse: State<BeatPulse>,
    onTimeSignatureChange: (beats: Int, unit: BeatUnit) -> Unit,
    onBeatPatternChange: (BeatPattern) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "COMPÁS",
        modifier = modifier,
        trailing = {
            TempoChip(
                label = "RESET",
                selected = false,
                description = "Restablecer el patrón de acentos de este compás",
                onClick = { onBeatPatternChange(BeatPattern.defaultFor(beatsPerBar, beatUnit)) },
            )
        },
    ) {
        Text("$beatsPerBar/${beatUnit.denominator}", style = monoStyle(20.sp, FlPurpleLight, FontWeight.Bold))

        BeatPatternEditor(
            beatsPerBar = beatsPerBar,
            pattern = beatPattern,
            pulse = pulse,
            onPatternChange = onBeatPatternChange,
        )

        LabeledChips("PULSOS POR COMPÁS") {
            PulseCounts.forEach { n ->
                TempoChip(
                    label = "$n",
                    selected = beatsPerBar == n,
                    description = "$n pulsos por compás",
                    onClick = { onTimeSignatureChange(n, beatUnit) },
                )
            }
        }

        LabeledChips("FIGURA DEL PULSO") {
            BeatUnit.values().forEach { unit ->
                TempoChip(
                    label = "1/${unit.denominator}",
                    selected = beatUnit == unit,
                    description = "Pulso de ${unit.description()}",
                    onClick = { onTimeSignatureChange(beatsPerBar, unit) },
                )
            }
        }
    }
}

private fun BeatUnit.description(): String = when (this) {
    BeatUnit.HALF -> "blanca"
    BeatUnit.QUARTER -> "negra"
    BeatUnit.EIGHTH -> "corchea"
    BeatUnit.SIXTEENTH -> "semicorchea"
}
