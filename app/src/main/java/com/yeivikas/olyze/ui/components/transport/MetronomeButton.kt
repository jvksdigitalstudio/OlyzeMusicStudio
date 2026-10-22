package com.yeivikas.olyze.ui.components.transport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.ui.components.pulse.rememberBeatFlash
import com.yeivikas.olyze.ui.theme.*

/**
 * Botón del metrónomo + indicador visual de pulso (ADR 0029): mientras el transporte corre, el
 * botón destella en cada pulso — más intenso en el primer tiempo del compás — con o sin click
 * audible (un metrónomo visual silencioso es un uso legítimo). No añade ningún elemento nuevo al
 * header: reutiliza este botón.
 *
 * [pulse] llega como [State] (no como valor) A PROPÓSITO: este composable nunca lee `.value` en
 * composición, solo en un `snapshotFlow` y en la fase de dibujo. Así cada pulso invalida solo el
 * dibujo del botón, no recompone el header ni la pantalla.
 */
@Composable
fun MetronomeButton(enabled: Boolean, pulse: State<BeatPulse>, onClick: () -> Unit) {
    val currentOnClick by rememberUpdatedState(onClick) // pointerInput(Unit) captura una sola vez
    val flash = rememberBeatFlash(pulse)

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (enabled) FlPurple.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.04f)
            )
            .drawBehind {
                val a = flash.value // lectura en la fase de dibujo: no recompone
                if (a > 0f) drawCircle(color = FlPurpleLight.copy(alpha = 0.7f * a))
            }
            .border(
                1.5.dp,
                if (enabled) FlPurple.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.1f),
                CircleShape
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "♪",
            fontSize = 13.sp,
            color = if (enabled) FlPurpleLight else FlPurpleLight.copy(alpha = 0.6f)
        )
    }
}
