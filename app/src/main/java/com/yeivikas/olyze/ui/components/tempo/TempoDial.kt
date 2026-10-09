package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.ui.components.pulse.rememberBeatFlash
import com.yeivikas.olyze.ui.theme.FlBorder
import com.yeivikas.olyze.ui.theme.FlGreen
import com.yeivikas.olyze.ui.theme.FlMuted
import com.yeivikas.olyze.ui.theme.FlPurple
import com.yeivikas.olyze.ui.theme.FlPurpleLight
import com.yeivikas.olyze.ui.theme.FlText
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Dial de tempo circular (referencia: el dial de FL Studio Mobile).
 *
 *  - **Arco** de 300° con el tempo actual (20–300 BPM) y una **bolita** en el extremo.
 *  - **Giro**: arrastrar el dedo alrededor del centro cambia el tempo EN RELACIÓN al giro (un "jog",
 *    sin saltar al punto tocado): una vuelta = 90 BPM, a pasos de una décima. Ver [TempoDialMath].
 *  - **Centro**: el BPM con decimales, el compás y el pulso.
 *  - **Pulso**: el anillo exterior destella en cada tiempo, más fuerte en el primero del compás
 *    (mismo destello que el botón ♪ del header: [rememberBeatFlash]).
 *
 * Es SIN ESTADO: el tempo vive en el motor y cada giro lo envía por [onTempoChange]; el valor que se
 * dibuja es el que vuelve del motor.
 *
 * [pulse] es un [State] para que cada pulso invalide solo el DIBUJO (el destello se lee en `drawBehind`).
 */
@Composable
internal fun TempoDial(
    bpm: Float,
    timeSignature: String,
    pulse: State<BeatPulse>,
    onTempoChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minBpm = EliNerTransportApi.MIN_TEMPO_BPM
    val maxBpm = EliNerTransportApi.MAX_TEMPO_BPM
    val flash = rememberBeatFlash(pulse)
    val currentBpm by rememberUpdatedState(bpm)
    val currentOnTempoChange by rememberUpdatedState(onTempoChange)
    val bpmText = String.format(Locale.US, "%.1f", bpm)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Dial de tempo, $bpmText pulsaciones por minuto. Gira el dedo para cambiarlo." }
            .pointerInput(Unit) {
                var previousAngle = 0f
                var value = 0f
                detectDragGestures(
                    onDragStart = { start ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        previousAngle = TempoDialMath.angleDeg(start.x - cx, start.y - cy)
                        value = currentBpm // el giro parte del tempo REAL, no del que se dibujó hace un fotograma
                    },
                    onDrag = { change, _ ->
                        val dx = change.position.x - size.width / 2f
                        val dy = change.position.y - size.height / 2f
                        // Zona muerta en el centro: allí el ángulo es inestable y un temblor del dedo
                        // daría saltos enormes.
                        if (hypot(dx, dy) > size.width * DEAD_ZONE_FRACTION) {
                            val angle = TempoDialMath.angleDeg(dx, dy)
                            value = TempoDialMath.bpmAfterTurn(
                                value, TempoDialMath.shortestDeltaDeg(previousAngle, angle), minBpm, maxBpm,
                            )
                            previousAngle = angle
                            currentOnTempoChange(TempoDialMath.snap(value))
                        }
                        change.consume()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // Anillo de pulso (lectura en la fase de dibujo: no recompone).
                    val a = flash.value
                    if (a > 0f) {
                        drawCircle(
                            color = FlGreen.copy(alpha = 0.55f * a),
                            radius = size.minDimension / 2f - 1.dp.toPx(),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                },
        ) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2f + 8.dp.toPx()
            val arcTopLeft = Offset(inset, inset)
            val arcSize = Size(size.width - 2f * inset, size.height - 2f * inset)

            // Pista y tramo activo.
            drawArc(
                color = FlBorder,
                startAngle = TempoDialMath.START_ANGLE_DEG,
                sweepAngle = TempoDialMath.SWEEP_DEG,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = FlPurple,
                startAngle = TempoDialMath.START_ANGLE_DEG,
                sweepAngle = TempoDialMath.SWEEP_DEG * TempoDialMath.fraction(bpm, minBpm, maxBpm),
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // Bolita en el extremo del tramo activo.
            val radius = arcSize.width / 2f
            val thumbRad = Math.toRadians(TempoDialMath.thumbAngleDeg(bpm, minBpm, maxBpm).toDouble())
            val centre = Offset(size.width / 2f, size.height / 2f)
            val thumb = Offset(
                centre.x + radius * cos(thumbRad).toFloat(),
                centre.y + radius * sin(thumbRad).toFloat(),
            )
            drawCircle(color = Color.White, radius = 9.dp.toPx(), center = thumb)
            drawCircle(color = FlPurpleLight, radius = 4.dp.toPx(), center = thumb)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bpmText, style = monoStyle(38.sp, FlText, FontWeight.Bold))
            Text("BPM", style = monoStyle(11.sp, FlMuted, letterSpacing = 2.sp))
            Text(timeSignature, style = monoStyle(11.sp, FlPurpleLight))
        }
    }
}

/** Radio (fracción del ancho) bajo el cual un toque se ignora por no tener ángulo fiable. */
private const val DEAD_ZONE_FRACTION = 0.08f
