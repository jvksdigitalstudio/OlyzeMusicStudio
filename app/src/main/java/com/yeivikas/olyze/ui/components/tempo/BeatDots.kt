package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.ui.theme.FlGreen
import com.yeivikas.olyze.ui.theme.FlPurple
import com.yeivikas.olyze.ui.theme.FlPurpleLight

/**
 * Un punto por tiempo del compás; se enciende el actual (el primero, más grande y verde).
 *
 * Lee [pulse] AQUÍ, en composición, de modo que cada pulso recompone solo esta fila.
 */
@Composable
internal fun BeatDots(beatsPerBar: Int, pulse: State<BeatPulse>) {
    val p = pulse.value
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until beatsPerBar) {
            val lit = p.isRunning && p.beatInBar == i
            val downbeat = i == 0
            val size: Dp = if (downbeat) 22.dp else 16.dp
            val onColor = if (downbeat) FlGreen else FlPurpleLight
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(if (lit) onColor else Color.White.copy(alpha = 0.06f))
                    .border(
                        1.5.dp,
                        if (lit) onColor else (if (downbeat) FlGreen.copy(alpha = 0.35f) else FlPurple.copy(alpha = 0.3f)),
                        CircleShape,
                    ),
            )
        }
    }
}
