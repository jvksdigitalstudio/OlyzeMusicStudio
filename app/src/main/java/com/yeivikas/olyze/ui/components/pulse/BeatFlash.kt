package com.yeivikas.olyze.ui.components.pulse

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter

/**
 * Destello de pulso compartido por todo indicador visual del compás (botón ♪ del header, anillo
 * del dial de tempo…): un valor 0..1 que salta al llegar un pulso y decae solo.
 *
 *  - 1,0 en el primer tiempo del compás (el acentuado), [OFF_BEAT_LEVEL] en los demás.
 *  - Vuelve a 0 en [DECAY_MS] ms y se apaga en el acto cuando el transporte para.
 *
 * ## Por qué devuelve un [Animatable] y no un Float
 * Quien lo usa lo lee en la FASE DE DIBUJO (`drawBehind { flash.value }`): cada pulso invalida solo
 * el dibujo, no recompone. Un `Float` en composición recompondría 60 veces por segundo mientras
 * decae. [pulse] llega como [State] por la misma razón (ver MainScreen).
 */
@Composable
fun rememberBeatFlash(pulse: State<BeatPulse>): Animatable<Float, *> {
    val flash = remember { Animatable(0f) }

    LaunchedEffect(pulse) {
        snapshotFlow { pulse.value }
            .filter { it.isRunning }
            .distinctUntilChangedBy { it.sequence }
            .collectLatest { p ->
                flash.snapTo(if (p.beatInBar == 0) 1f else OFF_BEAT_LEVEL)
                flash.animateTo(0f, tween(durationMillis = DECAY_MS))
            }
    }
    // Al parar no llega ningún pulso más: se apaga el destello que quedara a medias.
    LaunchedEffect(pulse) {
        snapshotFlow { pulse.value.isRunning }
            .filter { !it }
            .collect { flash.snapTo(0f) }
    }
    return flash
}

private const val OFF_BEAT_LEVEL = 0.55f
private const val DECAY_MS = 170
