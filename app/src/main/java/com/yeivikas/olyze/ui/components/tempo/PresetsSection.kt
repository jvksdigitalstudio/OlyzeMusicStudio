package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.transport.MetronomePreset
import com.yeivikas.olyze.ui.theme.FlBorder
import com.yeivikas.olyze.ui.theme.FlMuted
import com.yeivikas.olyze.ui.theme.FlPurple
import com.yeivikas.olyze.ui.theme.FlPurpleLight
import com.yeivikas.olyze.ui.theme.FlText
import kotlin.math.roundToInt

/**
 * Presets del metrónomo ("Crear / Cargar"): GUARDAR guarda la configuración actual; tocar una fila la
 * carga; la papelera la borra. La fila que coincide con la configuración actual se marca como activa, y
 * GUARDAR se atenúa cuando ya está guardada o la biblioteca está llena (las reglas viven en
 * `MetronomePresetLibrary`; aquí solo se reflejan).
 */
@Composable
internal fun PresetsSection(
    current: MetronomePreset,
    presets: List<MetronomePreset>,
    maxPresets: Int,
    onSave: (MetronomePreset) -> Unit,
    onLoad: (MetronomePreset) -> Unit,
    onDelete: (MetronomePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSave = current !in presets && presets.size < maxPresets

    SectionCard(
        title = "PRESETS  ${presets.size}/$maxPresets",
        modifier = modifier,
        trailing = {
            TempoChip(
                label = "GUARDAR",
                selected = false,
                dimmed = !canSave,
                description = when {
                    current in presets -> "Guardar: esta configuración ya está guardada"
                    presets.size >= maxPresets -> "Guardar: la biblioteca está llena, borra un preset"
                    else -> "Guardar la configuración actual como preset"
                },
                onClick = { if (canSave) onSave(current) },
            )
        },
    ) {
        if (presets.isEmpty()) {
            Text(
                "Sin presets. Ajusta el metrónomo y toca GUARDAR para recuperarlo cuando quieras.",
                style = monoStyle(10.sp, FlMuted),
            )
        } else {
            // Altura acotada: un scroll vertical dentro del panel (que también se desplaza) exige un máximo finito.
            Column(
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.asReversed().forEach { preset ->
                    PresetRow(preset, active = preset == current, onLoad = { onLoad(preset) }, onDelete = { onDelete(preset) })
                }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: MetronomePreset, active: Boolean, onLoad: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (active) FlPurple.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (active) FlPurple.copy(alpha = 0.8f) else FlBorder, shape)
            .clickable(role = Role.Button, onClickLabel = "Cargar ${preset.title}", onClick = onLoad)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(preset.title, style = monoStyle(12.sp, if (active) FlPurpleLight else FlText, FontWeight.Bold))
            Text(
                "${preset.sound.label()} · subdivisión ${preset.subdivision.label()} · vol ${(preset.volume * 100).roundToInt()}%",
                style = monoStyle(10.sp, FlMuted),
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Borrar ${preset.title}", onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Borrar preset", tint = FlMuted)
        }
    }
}
