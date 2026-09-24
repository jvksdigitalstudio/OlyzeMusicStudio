package com.yeivikas.olyze.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.yeivikas.olyze.ui.theme.FlGreen

/**
 * Floating "+" button — entry point to add channels/instruments/tracks.
 * Styled after FL Studio Mobile's add-channel button: a glowing green
 * circle centered in the work area.
 */
@Composable
fun AddChannelButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "addGlow")
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue  = 0.75f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = modifier
            .size(64.dp)
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                ambientColor = FlGreen.copy(alpha = glow),
                spotColor = FlGreen.copy(alpha = glow)
            )
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        FlGreen.copy(alpha = 0.95f),
                        Color(0xFF0F9D58)
                    ),
                    center = Offset(0.3f, 0.3f)
                )
            )
            .border(2.dp, FlGreen.copy(alpha = glow), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Añadir canal",
            tint = Color.White,
            modifier = Modifier.size(30.dp)
        )
    }
}
