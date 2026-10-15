package com.yeivikas.olyze.ui.components.transport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse

/**
 * Agrupa los controles de transporte en el orden REC · ⏮ · ▶ · ♪ · BPM.
 *
 * Solo COMPONE piezas existentes y les reenvía sus eventos: no guarda estado ni
 * decide nada.
 */
@Composable
fun TransportGroup(
    isPlaying: Boolean,
    isRecording: Boolean,
    bpm: Int,
    metronomeEnabled: Boolean,
    pulse: State<BeatPulse>,
    onPlay: () -> Unit,
    onRec: () -> Unit,
    onRewind: () -> Unit,
    onMetronomeToggle: () -> Unit,
    onBpmStep: (Int) -> Unit,
    onBpmClick: () -> Unit,
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

        // METRONOME
        MetronomeButton(enabled = metronomeEnabled, pulse = pulse, onClick = onMetronomeToggle)

        // BPM
        BpmControl(bpm = bpm, onBpmStep = onBpmStep, onBpmClick = onBpmClick)
    }
}
