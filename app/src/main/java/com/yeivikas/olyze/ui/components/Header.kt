package com.yeivikas.olyze.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppHeader(
    bpm: Int,
    isPlaying: Boolean,
    isRecording: Boolean,
    keyboardVisible: Boolean,
    onPlayToggle: () -> Unit,
    onRecToggle: () -> Unit,
    onRewind: () -> Unit,
    onBpmChange: (Int) -> Unit,
    onKeyboardToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF080612), Color(0xFF120a22), Color(0xFF080612))
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        FlPurple.copy(alpha = 0.6f),
                        Color.White.copy(alpha = 0.9f),
                        FlPurple.copy(alpha = 0.6f),
                        Color.Transparent
                    )
                ),
                shape = RoundedCornerShape(0.dp)
            )
    ) {
        // Transport centered
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TransportGroup(
                isPlaying = isPlaying,
                isRecording = isRecording,
                bpm = bpm,
                onPlay = onPlayToggle,
                onRec = onRecToggle,
                onRewind = onRewind,
                onBpmChange = onBpmChange
            )
        }

        // (Keyboard toggle removed from header — use the × button on the keyboard itself,
        //  or the expand handle that appears when the keyboard is hidden.)
    }
}

@Composable
fun TransportGroup(
    isPlaying: Boolean,
    isRecording: Boolean,
    bpm: Int,
    onPlay: () -> Unit,
    onRec: () -> Unit,
    onRewind: () -> Unit,
    onBpmChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.025f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // REC button
        RecButton(isRecording = isRecording, onClick = onRec)

        // REW button
        CircleIconBtn(label = "⏮", onClick = onRewind)

        // PLAY button
        PlayButton(isPlaying = isPlaying, onClick = onPlay)

        // BPM
        BpmControl(bpm = bpm, onBpmChange = onBpmChange)
    }
}

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
                else Brush.linearGradient(
                    listOf(FlPurple.copy(alpha = 0.15f), FlPurpleDim.copy(alpha = 0.08f))
                ).let { Color(0xFF13101F) }
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

@Composable
fun BpmControl(bpm: Int, onBpmChange: (Int) -> Unit) {
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FlPurple.copy(alpha = 0.06f))
            .border(1.dp, FlPurple.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Minus
        Text(
            text = "−",
            fontSize = 14.sp,
            color = FlPurpleLight.copy(alpha = 0.9f),
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onBpmChange(bpm - 1) },
                        onLongPress = {
                            holdJob = scope.launch {
                                while (true) { onBpmChange(bpm - 1); delay(80) }
                            }
                        },
                        onPress = {
                            awaitRelease()
                            holdJob?.cancel()
                        }
                    )
                }
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = bpm.toString(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = FlText,
                    letterSpacing = 1.sp
                )
            )
            Text(
                text = "BPM",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    color = FlPurple.copy(alpha = 0.5f),
                    letterSpacing = 2.sp
                )
            )
        }

        // Plus
        Text(
            text = "+",
            fontSize = 14.sp,
            color = FlPurpleLight.copy(alpha = 0.9f),
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onBpmChange(bpm + 1) },
                        onLongPress = {
                            holdJob = scope.launch {
                                while (true) { onBpmChange(bpm + 1); delay(80) }
                            }
                        },
                        onPress = {
                            awaitRelease()
                            holdJob?.cancel()
                        }
                    )
                }
        )
    }
}

@Composable
fun KeyboardToggleBtn(visible: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (visible) FlPurple.copy(alpha = 0.1f) else FlPurple.copy(alpha = 0.25f)
            )
            .border(
                1.dp,
                if (visible) FlPurple.copy(alpha = 0.35f) else FlPurple.copy(alpha = 0.7f),
                RoundedCornerShape(20.dp)
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "⌨ TECLADO",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = if (visible) FlPurpleLight.copy(alpha = 0.8f) else FlPurpleLight,
                letterSpacing = 1.sp
            )
        )
    }
}
