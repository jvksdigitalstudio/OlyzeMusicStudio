package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.ui.components.transport.AppHeaderHeight
import com.yeivikas.olyze.ui.theme.FlBorder
import com.yeivikas.olyze.ui.theme.FlPanel
import com.yeivikas.olyze.ui.theme.FlPurpleLight
import com.yeivikas.olyze.ui.theme.FlText

/** Ancho a partir del cual el panel se reparte en dos columnas (tempo | sonido). */
private val TwoColumnMinWidth = 600.dp

/** Ancho de la columna del tempo: cabe el dial con sus pasos (284 dp) y los 6 chips de compás sin desplazar. */
private val LeftColumnWidth = 320.dp

/**
 * Panel de tempo: un POPOVER que nace bajo la barra superior, desde el control de BPM (referencias:
 * FL Studio Mobile y FL Studio de escritorio), no una hoja inferior.
 *
 *  - **Izquierda (tempo):** el dial, los pasos ±1/±10, el pad de TAP y el nudge fino
 *    ([TempoDialSection]), y debajo el compás con el patrón de acentos por tiempo, que a la vez es el
 *    indicador de pulso ([MeterSection]).
 *  - **Derecha (sonido):** metrónomo ([MetronomeSection]), delay al tempo ([DelaySection]) y los presets
 *    guardados ([PresetsSection]).
 *  - En pantallas estrechas las dos columnas pasan a una sola, y el panel se desplaza si no cabe.
 *
 * Es SIN ESTADO propio: lo que muestra es [state] (instantánea del motor) y lo que pide sale por
 * [actions]. Solo COMPONE: cada control vive en su archivo. [pulse] es un [State] para que cada pulso
 * invalide solo el dibujo del dial y de los puntos, no el panel.
 */
@Composable
internal fun TempoPanel(
    visible: Boolean,
    state: TempoPanelState,
    pulse: State<BeatPulse>,
    actions: TempoPanelActions,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        // Fondo: oscurece y, al tocarlo, cierra.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(Unit) { detectTapGestures(onTap = { actions.onDismiss() }) },
            contentAlignment = Alignment.TopCenter,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    // Entra deslizando desde arriba (de donde nace); animateEnterExit y no un segundo
                    // AnimatedVisibility anidado, para que el fondo espere a que el panel termine de salir.
                    .animateEnterExit(
                        enter = slideInVertically { -it / 4 } + fadeIn(),
                        exit = slideOutVertically { -it / 4 } + fadeOut(),
                    )
                    .statusBarsPadding()
                    .padding(top = AppHeaderHeight + 6.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .widthIn(max = 780.dp),
            ) {
                val twoColumns = maxWidth >= TwoColumnMinWidth
                val shape = RoundedCornerShape(18.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .clip(shape)
                        .background(FlPanel)
                        .border(1.dp, FlBorder, shape)
                        // Los toques dentro del panel no deben llegar al fondo (cerraría el panel).
                        .pointerInput(Unit) { detectTapGestures(onTap = { /* consumir */ }) }
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PanelHeader(onDismiss = actions.onDismiss)

                    if (twoColumns) {
                        // Izquierda: lo que se TOCA con el ritmo (dial, TAP, compás). Derecha: el sonido y los
                        // efectos. Reparte la altura por igual y deja el delay desplegado crecer sin tapar el teclado.
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            TempoColumn(state, pulse, actions, modifier = Modifier.width(LeftColumnWidth))
                            SoundColumn(state, actions, modifier = Modifier.weight(1f))
                        }
                    } else {
                        TempoColumn(state, pulse, actions, modifier = Modifier.fillMaxWidth())
                        SoundColumn(state, actions)
                    }
                }
            }
        }
    }
}

/** Columna del tempo: dial con sus pasos y TAP, y debajo el compás con el indicador de pulso. */
@Composable
private fun TempoColumn(
    state: TempoPanelState,
    pulse: State<BeatPulse>,
    actions: TempoPanelActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TempoDialSection(state = state, pulse = pulse, actions = actions, modifier = Modifier.fillMaxWidth())
        MeterSection(
            beatsPerBar = state.beatsPerBar,
            beatUnit = state.beatUnit,
            beatPattern = state.beatPattern,
            pulse = pulse,
            onTimeSignatureChange = actions.onTimeSignatureChange,
            onBeatPatternChange = actions.onBeatPatternChange,
        )
    }
}

/** Columna del sonido: metrónomo, grabación (conteo y modo del click), delay al tempo y presets. */
@Composable
private fun SoundColumn(
    state: TempoPanelState,
    actions: TempoPanelActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MetronomeSection(state = state, actions = actions)
        RecordingSection(state = state, actions = actions)
        DelaySection(
            delaySync = state.delaySync,
            onToggle = actions.onDelaySyncToggle,
            onDivisionChange = actions.onDelayDivisionChange,
        )
        PresetsSection(
            current = state.current,
            presets = state.presets,
            maxPresets = state.maxPresets,
            onSave = actions.onPresetSave,
            onLoad = actions.onPresetLoad,
            onDelete = actions.onPresetDelete,
        )
    }
}

@Composable
private fun PanelHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TEMPO", style = monoStyle(14.sp, FlText, FontWeight.Bold, letterSpacing = 2.sp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
                .clickable(role = Role.Button, onClickLabel = "Cerrar", onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = Icons.Filled.Close, contentDescription = "Cerrar panel de tempo", tint = FlPurpleLight)
        }
    }
}
