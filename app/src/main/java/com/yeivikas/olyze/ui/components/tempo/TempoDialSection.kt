package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.ui.theme.FlMuted
import com.yeivikas.olyze.ui.theme.FlPurple
import com.yeivikas.olyze.ui.theme.FlPurpleLight

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
                timeSignature = "Compás ${state.beatsPerBar}/4",
                pulse = pulse,
                onTempoChange = actions.onTempoChange,
                modifier = Modifier.size(184.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RoundStepButton("+10", "Subir el tempo 10", { actions.onTempoNudge(10f) })
                RoundStepButton("+1", "Subir el tempo 1", { actions.onTempoNudge(1f) })
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val nudgeShape = RoundedCornerShape(12.dp)
            RoundStepButton("−0.1", "Bajar el tempo una décima", { actions.onTempoNudge(-0.1f) }, size = 52.dp, shape = nudgeShape)
            TapPad(onTap = actions.onTap, modifier = Modifier.width(112.dp))
            RoundStepButton("+0.1", "Subir el tempo una décima", { actions.onTempoNudge(0.1f) }, size = 52.dp, shape = nudgeShape)
        }
    }
}

/**
 * Pad de TAP. Dispara al TOCAR (no al soltar): la precisión del tap tempo depende de cuándo toca el
 * dedo, y esperar a que lo levante metería ~50–100 ms de jitter en cada intervalo.
 */
@Composable
private fun TapPad(onTap: () -> Unit, modifier: Modifier = Modifier) {
    val currentOnTap by rememberUpdatedState(onTap) // pointerInput(Unit) captura una sola vez
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(if (pressed) FlPurple.copy(alpha = 0.45f) else FlPurple.copy(alpha = 0.16f))
            .border(1.5.dp, if (pressed) FlPurpleLight else FlPurple.copy(alpha = 0.7f), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = "Tap tempo: toca al ritmo para medir el tempo"
                role = Role.Button
                onClick(label = "Tap tempo") { currentOnTap(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    currentOnTap()
                    tryAwaitRelease()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TAP", style = monoStyle(18.sp, FlPurpleLight, FontWeight.Bold, letterSpacing = 3.sp))
            Text("al ritmo", style = monoStyle(8.sp, FlMuted, letterSpacing = 1.sp))
        }
    }
}
