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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.ui.theme.*

/**
 * Botones circulares del transporte: grabar, rebobinar y reproducir/detener.
 *
 * Son "tontos" a propósito: reciben su estado visual y emiten un evento. El metrónomo
 * (con indicador de pulso) y el control de BPM tienen archivo propio porque llevan
 * lógica de interacción/animación.
 */
@Composable
fun RecButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (isRecording) FlRed.copy(alpha = 0.15f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                1.5.dp,
                if (isRecording) FlRed.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.15f),
                CircleShape
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) FlRed
                    else Color.White.copy(alpha = 0.3f)
                )
        )
    }
}

@Composable
fun CircleIconBtn(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, color = FlPurpleLight.copy(alpha = 0.6f))
    }
}

@Composable
fun PlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (isPlaying) FlGreen.copy(alpha = 0.08f)
                else Color(0xFF13101F)
            )
            .border(
                1.5.dp,
                if (isPlaying) FlGreen.copy(alpha = 0.6f) else FlPurple.copy(alpha = 0.45f),
                CircleShape
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isPlaying) "■" else "▶",
            fontSize = 14.sp,
            color = if (isPlaying) FlGreen else FlPurpleLight.copy(alpha = 0.9f)
        )
    }
}
