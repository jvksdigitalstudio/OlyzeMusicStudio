package com.yeivikas.olyze.ui.components.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.CountInLength
import com.yeivikas.olyze.eliner.api.transport.TransportPosition
import com.yeivikas.olyze.ui.theme.FlPurple

/**
 * Estado del transporte sobre el área de trabajo (ADR 0036): el CONTEO previo a grabar mientras suena, y la
 * posición en compás | pulso | tick cuando hay transporte.
 *
 * Vive en el área de trabajo y NO en [com.yeivikas.olyze.ui.components.transport.AppHeader]: esa barra tiene
 * una restricción de producto explícita («no añadir nada sin que se pida»). Quitarlo es borrar su única llamada
 * en `MainScreen`; no toca nada más.
 *
 *  - **Conteo:** durante él la posición está retenida (no se mueve), así que se muestra el conteo en su lugar:
 *    el pulso dentro del compás, grande, y «compás n/N» debajo.
 *  - **Posición:** `compás.pulso.tick` en base 1 para compás y pulso (el primer tiempo es 1.1.000). Se muestra
 *    mientras el transporte suena o está en una posición distinta del origen; parado en el origen no se pinta nada.
 *
 * Solo PRESENTA: recibe el pulso y la posición como `State` (se leen aquí, dentro de este composable, de modo que
 * solo se recompone este pequeño bloque y no `MainScreen`).
 */
@Composable
fun TransportStatus(
    pulse: State<BeatPulse>,
    positionTicks: State<Long>,
    beatsPerBar: Int,
    beatUnit: BeatUnit,
    countInLength: CountInLength,
    modifier: Modifier = Modifier,
) {
    val p = pulse.value
    val ticks = positionTicks.value
    when {
        p.isCountIn -> CountInBadge(
            beat = p.beatInBar + 1,
            bar = p.countInBar + 1,
            totalBars = countInLength.bars,
            modifier = modifier,
        )
        p.isRunning || ticks > 0L -> PositionBadge(
            position = TransportPosition.barBeatTickFor(ticks, beatsPerBar, beatUnit.quartersPerPulse),
            modifier = modifier,
        )
        else -> Unit
    }
}

@Composable
private fun CountInBadge(beat: Int, bar: Int, totalBars: Int, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xCC120A22), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 10.dp)
            .semantics { contentDescription = "Conteo previo: compás $bar de $totalBars, tiempo $beat" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "CONTEO",
            color = FlPurple.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Text(
            text = beat.toString(),
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "compás $bar/$totalBars",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PositionBadge(position: TransportPosition.BarBeatTick, modifier: Modifier) {
    // Base 1 para compás y pulso; el tick va con tres dígitos para que el ancho no baile.
    val text = "%d.%d.%03d".format(position.bar + 1, position.beat + 1, position.tick)
    Text(
        text = text,
        modifier = modifier
            .background(Color(0x99120A22), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .semantics { contentDescription = "Posición: compás ${position.bar + 1}, tiempo ${position.beat + 1}" },
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
    )
}
