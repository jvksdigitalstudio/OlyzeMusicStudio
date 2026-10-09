package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse

/**
 * Compases ofrecidos (pulsos por compás, siempre en NEGRAS).
 *
 * Límite del motor, declarado y no disimulado: el reloj cuenta PULSOS DE NEGRA
 * (`EliNerTransportApi.beatsPerBar` = negras por compás). Por eso solo se ofrecen compases x/4.
 * Un 6/8 o 12/8 "de verdad" (pulso de corchea o de negra con puntillo) necesita una unidad de
 * pulso configurable en el motor; mostrar "6/8" aquí mapeándolo a 6 negras sonaría como un 6/4,
 * es decir, mentiría. Ver ADR 0029.
 */
private val TimeSignatures = listOf(2, 3, 4, 5, 6, 7)

/** Compás: indicador de pulso (un punto por tiempo) y selector de compás. */
@Composable
internal fun MeterSection(
    beatsPerBar: Int,
    pulse: State<BeatPulse>,
    onBeatsPerBarChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "COMPÁS", modifier = modifier) {
        BeatDots(beatsPerBar = beatsPerBar, pulse = pulse)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TimeSignatures.forEach { beats ->
                TempoChip(
                    label = "$beats/4",
                    selected = beatsPerBar == beats,
                    description = "Compás $beats por 4",
                    onClick = { onBeatsPerBarChange(beats) },
                )
            }
        }
    }
}
