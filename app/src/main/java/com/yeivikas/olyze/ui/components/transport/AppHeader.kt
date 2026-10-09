package com.yeivikas.olyze.ui.components.transport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.ui.theme.*

/**
 * Barra superior fija de la app (56 dp): solo CONTENEDOR y fondo.
 *
 * Coloca el grupo de transporte en el centro. No contiene lógica de transporte ni
 * botones: eso vive en [TransportGroup] y sus piezas. Restricción de producto: no
 * añadir nada a esta barra sin que se pida explícitamente.
 */
@Composable
fun AppHeader(
    bpm: Int,
    isPlaying: Boolean,
    isRecording: Boolean,
    metronomeEnabled: Boolean,
    pulse: State<BeatPulse>,
    onPlayToggle: () -> Unit,
    onRecToggle: () -> Unit,
    onRewind: () -> Unit,
    onMetronomeToggle: () -> Unit,
    onBpmStep: (Int) -> Unit,
    onBpmClick: () -> Unit,
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
                metronomeEnabled = metronomeEnabled,
                pulse = pulse,
                onPlay = onPlayToggle,
                onRec = onRecToggle,
                onRewind = onRewind,
                onMetronomeToggle = onMetronomeToggle,
                onBpmStep = onBpmStep,
                onBpmClick = onBpmClick
            )
        }

        // (Keyboard toggle — removido a pedido explícito. No agregar nada acá sin que se pida.)
    }
}
