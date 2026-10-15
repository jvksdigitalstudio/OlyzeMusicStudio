package com.yeivikas.olyze.ui.components.tempo

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.transport.TapStatus
import com.yeivikas.olyze.transport.TempoMarking
import com.yeivikas.olyze.ui.theme.FlGreen
import com.yeivikas.olyze.ui.theme.FlMuted
import com.yeivikas.olyze.ui.theme.FlPurple
import com.yeivikas.olyze.ui.theme.FlPurpleLight
import java.util.Locale

/**
 * Columna izquierda del panel: el dial con sus botones de paso (−10 −1 · +1 +10) y, debajo, el pad
 * de TAP flanqueado por el nudge fino (−0,1 / +0,1). Composición de las referencias de FL Studio
 * Mobile (dial y pasos) y de FL Studio de escritorio (pad de TAP y nudge).
 *
 * Solo COMPONE y enruta eventos: el cálculo está en [TempoDialMath], en el ViewModel (pasos) y en
 * `TapTempo` (estadística del tap).
 */
@Composable
internal fun TempoDialSection(
    state: TempoPanelState,
    pulse: State<BeatPulse>,
    actions: TempoPanelActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RoundStepButton("−10", "Bajar el tempo 10", { actions.onTempoNudge(-10f) })
                RoundStepButton("−1", "Bajar el tempo 1", { actions.onTempoNudge(-1f) })
            }
            TempoDial(
                bpm = state.bpm,
                marking = TempoMarking.forBpm(state.bpm),
                timeSignature = "Compás ${state.beatsPerBar}/${state.beatUnit.denominator}",
                pulseInfo = pulseInfo(state),
                pulse = pulse,
                onTempoChange = actions.onTempoChange,
                modifier = Modifier.size(184.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RoundStepButton("+10", "Subir el tempo 10", { actions.onTempoNudge(10f) })
                RoundStepButton("+1", "Subir el tempo 1", { actions.onTempoNudge(1f) })
            }
        }

        // El pad de TAP ocupa todo el ancho que dejan los nudge: es lo que se golpea al ritmo y cuanto más
        // grande, más fácil y preciso (como el pad grande del "Tempo tapper" de FL Studio).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val nudgeShape = RoundedCornerShape(12.dp)
            RoundStepButton("−0.1", "Bajar el tempo una décima", { actions.onTempoNudge(-0.1f) }, size = 56.dp, shape = nudgeShape)
            TapPad(status = state.tap, onTap = actions.onTap, modifier = Modifier.weight(1f))
            RoundStepButton("+0.1", "Subir el tempo una décima", { actions.onTempoNudge(0.1f) }, size = 56.dp, shape = nudgeShape)
        }
    }
}

/**
 * Pad de TAP.
 *
 * ## Qué lo hace "de profesional"
 *  - **Hora real del toque:** usa `PointerInputChange.uptimeMillis` (cuándo tocó el dedo), no la hora a
 *    la que Compose procesa el gesto: Android entrega los toques agrupados por fotograma.
 *  - **Todos los dedos:** atiende cada `changedToDown`, no solo el primero. Tocar rápido alternando dos
 *    dedos no pierde pulsaciones, como en un pad de batería.
 *  - **Dispara al TOCAR**, no al soltar.
 *  - **Respuesta inmediata:** destello en cada toque (se dibuja sin recomponer) y vibración corta.
 *  - **Estado en vivo:** cuántos toques cuentan y el tempo estimado; se marca «estable» cuando la
 *    estimación ya es fiable y se puede dejar de tocar.
 */
@Composable
private fun TapPad(status: TapStatus, onTap: (Long) -> Unit, modifier: Modifier = Modifier) {
    val currentOnTap by rememberUpdatedState(onTap) // pointerInput(Unit) captura una sola vez
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf(0) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(hits) {
        if (hits > 0) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(durationMillis = FLASH_MS))
        }
    }

    val shape = RoundedCornerShape(18.dp)
    val accent = if (status.isStable) FlGreen else FlPurple
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(shape)
            .background(if (pressed) accent.copy(alpha = 0.40f) else accent.copy(alpha = 0.14f))
            .drawBehind {
                val a = flash.value // lectura en la fase de dibujo: no recompone
                if (a > 0f) drawRoundRect(color = FlPurpleLight.copy(alpha = 0.45f * a), cornerRadius = CornerRadius(18.dp.toPx()))
            }
            .border(1.5.dp, if (pressed) FlPurpleLight else accent.copy(alpha = 0.75f), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = "Tap tempo: toca al ritmo para medir el tempo. ${tapSummary(status)}"
                role = Role.Button
                onClick(label = "Tap tempo") { currentOnTap(SystemClock.uptimeMillis()); true }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (change.changedToDown()) {
                                change.consume()
                                currentOnTap(change.uptimeMillis)
                                hits++
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        pressed = event.changes.any { it.pressed }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TAP", style = monoStyle(22.sp, if (status.isStable) FlGreen else FlPurpleLight, FontWeight.Bold, letterSpacing = 4.sp))
            Text(tapSummary(status), style = monoStyle(10.sp, FlMuted, letterSpacing = 0.5.sp))
        }
    }
}

/** Línea de estado del pad: «toca al ritmo», «1 toque», «128.4 · 5 toques», «128.4 · 8 toques · estable». */
private fun tapSummary(status: TapStatus): String {
    val bpm = status.bpm
    return when {
        status.count == 0 -> "toca al ritmo"
        bpm == null -> "1 toque · sigue"
        else -> buildString {
            append(String.format(Locale.US, "%.1f", bpm))
            append(" · ${status.count} toques")
            if (status.isStable) append(" · estable")
        }
    }
}

/** Duración del destello del pad en cada toque (ms). */
private const val FLASH_MS = 160

/**
 * Cadencia de pulsos cuando el pulso no es la negra (6/8, 12/16…): el tempo sigue siendo en negras por
 * minuto, así que se muestra cuántos pulsos por minuto suenan de verdad. `null` en x/4 (no aporta nada).
 */
private fun pulseInfo(state: TempoPanelState): String? {
    if (state.beatUnit == BeatUnit.QUARTER) return null
    val perMinute = state.bpm / state.beatUnit.quartersPerPulse.toFloat()
    return "1/${state.beatUnit.denominator} = ${String.format(Locale.US, "%.1f", perMinute)}"
}
