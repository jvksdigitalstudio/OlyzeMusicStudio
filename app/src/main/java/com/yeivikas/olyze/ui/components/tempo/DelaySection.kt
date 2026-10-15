package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.DelayTempoSync
import com.yeivikas.olyze.eliner.api.transport.NoteDivision
import com.yeivikas.olyze.ui.theme.FlMuted
import com.yeivikas.olyze.ui.theme.FlPurple
import com.yeivikas.olyze.ui.theme.FlPurpleLight

/**
 * Delay sincronizado al tempo: interruptor y división. Las 11 divisiones ocupan mucho, así que la
 * rejilla está PLEGADA por defecto y la cabecera ya muestra la división activa; se despliega al
 * tocarla. (El plegado es un detalle visual local: no se guarda ni cuenta como estado de la app.)
 */
@Composable
internal fun DelaySection(
    delaySync: DelayTempoSync,
    onToggle: (Boolean) -> Unit,
    onDivisionChange: (NoteDivision) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    SectionCard(
        title = "DELAY AL TEMPO",
        modifier = modifier,
        trailing = {
            Switch(
                checked = delaySync.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = FlPurpleLight,
                    checkedTrackColor = FlPurple.copy(alpha = 0.35f),
                    uncheckedThumbColor = FlMuted,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.06f),
                ),
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Plegar divisiones" else "Mostrar divisiones") {
                    expanded = !expanded
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("DIVISIÓN  ${delaySync.division.shortLabel()}", style = monoStyle(11.sp, FlPurpleLight))
            Text(if (expanded) "▲" else "▼", style = monoStyle(11.sp, FlMuted))
        }
        if (expanded) {
            DelayDivisionGrid(
                syncEnabled = delaySync.enabled,
                selected = delaySync.division,
                onSelect = onDivisionChange,
            )
        }
    }
}
