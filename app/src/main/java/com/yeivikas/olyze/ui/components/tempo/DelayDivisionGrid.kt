package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import com.yeivikas.olyze.ui.theme.FlMuted

/** Divisiones del delay agrupadas en filas: (etiqueta, recta, con puntillo, tresillo). */
private data class DivisionRow(
    val label: String,
    val straight: NoteDivision,
    val dotted: NoteDivision?,
    val triplet: NoteDivision?,
)

private val DivisionRows = listOf(
    DivisionRow("1/4", NoteDivision.QUARTER, NoteDivision.QUARTER_DOTTED, NoteDivision.QUARTER_TRIPLET),
    DivisionRow("1/8", NoteDivision.EIGHTH, NoteDivision.EIGHTH_DOTTED, NoteDivision.EIGHTH_TRIPLET),
    DivisionRow("1/16", NoteDivision.SIXTEENTH, NoteDivision.SIXTEENTH_DOTTED, NoteDivision.SIXTEENTH_TRIPLET),
)

/** Rejilla de divisiones: filas 1/4, 1/8, 1/16 × recta, con puntillo, tresillo; más 1/2 y 1/1. */
@Composable
internal fun DelayDivisionGrid(
    syncEnabled: Boolean,
    selected: NoteDivision,
    onSelect: (NoteDivision) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderSpacer()
            ColumnHeader("RECTA"); ColumnHeader("PUNTILLO"); ColumnHeader("TRESILLO")
        }
        DivisionRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = monoStyle(11.sp, FlMuted), modifier = Modifier.widthIn(min = 40.dp))
                DivisionChip("${row.label}", row.straight, syncEnabled, selected, onSelect)
                row.dotted?.let { DivisionChip("${row.label}·", it, syncEnabled, selected, onSelect) }
                row.triplet?.let { DivisionChip("${row.label}T", it, syncEnabled, selected, onSelect) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LARGO", style = monoStyle(11.sp, FlMuted), modifier = Modifier.widthIn(min = 40.dp))
            DivisionChip("1/2", NoteDivision.HALF, syncEnabled, selected, onSelect)
            DivisionChip("1/1", NoteDivision.WHOLE, syncEnabled, selected, onSelect)
        }
    }
}

@Composable
private fun HeaderSpacer() = Box(Modifier.widthIn(min = 40.dp))

@Composable
private fun ColumnHeader(text: String) =
    Text(text, style = monoStyle(8.sp, FlMuted, letterSpacing = 1.sp), modifier = Modifier.widthIn(min = 64.dp))

@Composable
private fun DivisionChip(
    label: String,
    division: NoteDivision,
    syncEnabled: Boolean,
    selected: NoteDivision,
    onSelect: (NoteDivision) -> Unit,
) {
    TempoChip(
        label = label,
        selected = syncEnabled && selected == division,
        // Con el sync apagado las divisiones se ven atenuadas pero siguen siendo tocables:
        // elegir una es pedir el sync (el ViewModel lo reactiva).
        dimmed = !syncEnabled,
        description = "Delay a $label",
        onClick = { onSelect(division) },
        minWidth = 64.dp,
    )
}

/** Etiqueta corta de una división (la misma que usan los chips de la rejilla): 1/8·, 1/4T, 1/16… */
internal fun NoteDivision.shortLabel(): String = when (this) {
    NoteDivision.WHOLE -> "1/1"
    NoteDivision.HALF -> "1/2"
    NoteDivision.QUARTER_DOTTED -> "1/4·"
    NoteDivision.QUARTER -> "1/4"
    NoteDivision.QUARTER_TRIPLET -> "1/4T"
    NoteDivision.EIGHTH_DOTTED -> "1/8·"
    NoteDivision.EIGHTH -> "1/8"
    NoteDivision.EIGHTH_TRIPLET -> "1/8T"
    NoteDivision.SIXTEENTH_DOTTED -> "1/16·"
    NoteDivision.SIXTEENTH -> "1/16"
    NoteDivision.SIXTEENTH_TRIPLET -> "1/16T"
}
