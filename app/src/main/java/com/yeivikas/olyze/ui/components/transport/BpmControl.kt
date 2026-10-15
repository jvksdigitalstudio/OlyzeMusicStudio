package com.yeivikas.olyze.ui.components.transport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Control de tempo del header: − número +.
 *
 * Emite PASOS (+1/−1), no valores absolutos (ver el comentario interno sobre por qué),
 * y un toque en el número pide abrir el panel de tempo.
 */
@Composable
fun BpmControl(bpm: Int, onBpmStep: (Int) -> Unit, onBpmClick: () -> Unit) {
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    // pointerInput(Unit) ejecuta su bloque UNA sola vez y captura los valores de la primera
    // composición. Antes este control calculaba `bpm ± 1` DENTRO de ese bloque: `bpm` quedaba fijo
    // en su valor inicial (120), "+" calculaba siempre 121 y "−" siempre 119, y mantener pulsado
    // repetía ese mismo valor — el control solo podía oscilar entre 119 y 121.
    // Ahora el control no calcula nada: emite un PASO (+1 / −1) y quien lo recibe lo aplica sobre
    // el estado actual del motor. Así ningún valor capturado puede quedar obsoleto por construcción;
    // rememberUpdatedState solo protege el propio callback.
    val currentOnBpmStep by rememberUpdatedState(onBpmStep)
    val currentOnBpmClick by rememberUpdatedState(onBpmClick)

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
                        onTap = { currentOnBpmStep(-1) },
                        onLongPress = {
                            holdJob = scope.launch {
                                while (true) { currentOnBpmStep(-1); delay(80) }
                            }
                        },
                        onPress = {
                            awaitRelease()
                            holdJob?.cancel()
                        }
                    )
                }
        )

        // Tocar el número abre el panel de tempo (volumen del metrónomo, compás, delay).
        // No añade ningún elemento al header: la zona táctil es la del propio número.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnBpmClick() })
            }
        ) {
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
                        onTap = { currentOnBpmStep(+1) },
                        onLongPress = {
                            holdJob = scope.launch {
                                while (true) { currentOnBpmStep(+1); delay(80) }
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
