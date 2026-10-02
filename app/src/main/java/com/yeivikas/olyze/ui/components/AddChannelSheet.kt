package com.yeivikas.olyze.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.ui.theme.FlMuted

/**
 * Blank white panel triggered by [AddChannelButton].
 * This is the entry point for the "Add Channel" flow — currently empty,
 * ready to be built out with channel/synth selection content.
 */
@Composable
fun AddChannelSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn() + expandIn(expandFrom = Alignment.Center),
        exit    = fadeOut() + shrinkOut(shrinkTowards = Alignment.Center),
    ) {
        // Scrim — tap outside to dismiss
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                },
            contentAlignment = Alignment.Center
        ) {
            // The panel itself — block taps from passing through to the scrim
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { /* consume */ })
                    }
            ) {
                // Header bar with close button — content body intentionally blank for now
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Añadir canal",
                        style = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF1A1A1A)
                        )
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(FlMuted.copy(alpha = 0.12f))
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { onDismiss() })
                            }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = Color(0xFF1A1A1A)
                        )
                    }
                }

                // ── Body — blank canvas, ready to build channel/synth picker here ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.White)
                )
            }
        }
    }
}
