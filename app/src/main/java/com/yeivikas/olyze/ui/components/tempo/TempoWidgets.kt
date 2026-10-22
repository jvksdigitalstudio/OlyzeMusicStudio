package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.yeivikas.olyze.ui.theme.FlMuted
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

/** Etiqueta pequeña + fila de chips que se desplaza si no cabe (nunca recorta ni parte el contenido). */
@Composable
internal fun LabeledChips(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = monoStyle(10.sp, FlMuted, letterSpacing = 1.sp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
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

/**
 * Botón de NUDGE: mientras se mantiene pulsado [onStart] está activo y al soltar se llama a [onEnd]. A
 * diferencia de [RoundStepButton] (un paso permanente, repetido al mantener), aquí el efecto es TEMPORAL: lo
 * que hace [onStart] se deshace en [onEnd] (ver `TempoNudge`).
 *
 * [onEnd] se llama SIEMPRE que [onStart] se llamó, aunque el gesto se cancele (p. ej. el panel se cierra con
 * el dedo puesto): está en un `finally`. Si no, el tempo se quedaría acelerado o frenado para siempre.
 */
@Composable
internal fun NudgeButton(
    label: String,
    description: String,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnEnd by rememberUpdatedState(onEnd)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (pressed) FlPurple.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f))
            .border(1.dp, if (pressed) FlPurpleLight else Color.White.copy(alpha = 0.14f), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentOnStart()
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                        currentOnEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = monoStyle(18.sp, if (pressed) FlPurpleLight else FlText, FontWeight.Bold))
            Text("NUDGE", style = monoStyle(8.sp, FlMuted, letterSpacing = 1.sp))
        }
    }
}
