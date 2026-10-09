package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.ui.theme.FlBorder
import com.yeivikas.olyze.ui.theme.FlPurple
import com.yeivikas.olyze.ui.theme.FlPurpleLight
import com.yeivikas.olyze.ui.theme.FlText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Piezas visuales comunes del panel de tempo. Sin lógica de dominio: reciben estado y emiten
 * eventos. Las secciones ([MetronomeSection], [MeterSection], [DelaySection]) y el dial las comparten
 * para que todo el panel tenga la misma tipografía, bordes y zonas táctiles.
 */

internal fun monoStyle(
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = weight,
    fontSize = size,
    color = color,
    letterSpacing = letterSpacing,
)

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = monoStyle(9.sp, FlPurple.copy(alpha = 0.7f), FontWeight.Bold, letterSpacing = 2.sp))
}

/** Tarjeta de sección: título (+ control opcional a la derecha) y su contenido. */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, FlBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel(title)
            if (trailing != null) trailing()
        }
        content()
    }
}

/** Chip seleccionable (≥ 40 dp de alto: zona táctil cómoda en móvil). */
@Composable
internal fun TempoChip(
    label: String,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    dimmed: Boolean = false,
    minWidth: Dp = 0.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    val textColor = when {
        selected -> FlPurpleLight
        dimmed -> FlText.copy(alpha = 0.35f)
        else -> FlText.copy(alpha = 0.8f)
    }
    Box(
        modifier = Modifier
            .widthIn(min = maxOf(minWidth, 44.dp))
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) FlPurple.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (selected) FlPurple.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.1f), shape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = monoStyle(12.sp, textColor, FontWeight.Bold))
    }
}

/**
 * Pulsación con REPETICIÓN: da un paso al pulsar y, si se mantiene, sigue repitiendo cada
 * [intervalMs] tras [initialDelayMs] (como los botones +/− de un DAW). Un toque rápido da
 * exactamente UN paso.
 *
 * Usa `onPress` (no `onTap`): el primer paso ocurre al TOCAR, no al soltar, y la repetición se
 * cancela en cuanto el dedo se levanta o el gesto se cancela.
 */
@Composable
internal fun Modifier.repeatingPress(
    initialDelayMs: Long = 400L,
    intervalMs: Long = 90L,
    onStep: () -> Unit,
): Modifier {
    val currentOnStep by rememberUpdatedState(onStep) // pointerInput(Unit) captura una sola vez
    return this.pointerInput(Unit) {
        detectTapGestures(onPress = {
            coroutineScope {
                val repeater = launch {
                    currentOnStep()
                    delay(initialDelayMs)
                    while (true) {
                        currentOnStep()
                        delay(intervalMs)
                    }
                }
                tryAwaitRelease()
                repeater.cancel()
            }
        })
    }
}

/** Botón circular de paso (−10, −1, +1, +10, ±0,1…) con repetición al mantener. */
@Composable
internal fun RoundStepButton(
    label: String,
    description: String,
    onStep: () -> Unit,
    size: Dp = 44.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                onClick(label = description) { onStep(); true }
            }
            .repeatingPress(onStep = onStep),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = monoStyle(13.sp, FlText, FontWeight.Bold))
    }
}
