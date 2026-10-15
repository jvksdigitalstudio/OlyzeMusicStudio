package com.yeivikas.olyze.ui.components.tempo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.eliner.api.transport.BeatLevel
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatPulse
import com.yeivikas.olyze.ui.theme.FlBorder
import com.yeivikas.olyze.ui.theme.FlGreen
import com.yeivikas.olyze.ui.theme.FlMuted
import com.yeivikas.olyze.ui.theme.FlPurple

/**
 * Editor del patrón de intensidad por tiempo: una columna por tiempo del compás, con tres segmentos
 * apilados (acento arriba, normal en medio, suave abajo). El nivel de un tiempo es cuántos segmentos
 * están llenos, contados desde abajo; ninguno lleno = silencio.
 *
 * ## Gesto
 * Tocar el segmento de un nivel FIJA ese nivel; tocar el segmento que ya es el nivel actual lo BAJA un
 * escalón (acento → normal → suave → silencio). Así un solo gesto cubre los cuatro niveles.
 *
 * ## Indicador de pulso
 * La columna del tiempo que suena ahora se ilumina en verde mientras el transporte corre, de modo que
 * este mismo control es el indicador de compás (sustituye a los antiguos puntos de pulso).
 *
 * Lee [pulse] en composición a propósito: solo esta fila se recompone en cada tiempo.
 */
@Composable
internal fun BeatPatternEditor(
    beatsPerBar: Int,
    pattern: BeatPattern,
    pulse: State<BeatPulse>,
    onPatternChange: (BeatPattern) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = pulse.value
    val playing = if (now.isRunning) now.beatInBar else NOT_PLAYING

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (beat in 0 until beatsPerBar.coerceIn(1, BeatPattern.MAX_BEATS)) {
                BeatColumn(
                    beat = beat,
                    level = pattern[beat],
                    isPlaying = beat == playing,
                    onLevelChange = { level -> onPatternChange(pattern.with(beat, level)) },
                )
            }
        }
    }
}

/** Segmentos de arriba a abajo. */
private val Segments = listOf(BeatLevel.ACCENT, BeatLevel.MEDIUM, BeatLevel.SOFT)

private const val NOT_PLAYING = -1

@Composable
private fun BeatColumn(
    beat: Int,
    level: BeatLevel,
    isPlaying: Boolean,
    onLevelChange: (BeatLevel) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (segment in Segments) {
            val filled = level.nativeId >= segment.nativeId
            val shape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(24.dp)
                    .clip(shape)
                    .background(
                        when {
                            filled && isPlaying -> FlGreen
                            filled -> FlPurple
                            else -> Color.White.copy(alpha = 0.05f)
                        },
                    )
                    .border(1.dp, if (isPlaying) FlGreen.copy(alpha = 0.8f) else FlBorder, shape)
                    .semantics { contentDescription = "Tiempo ${beat + 1}, ${segment.label()}. Ahora: ${level.label()}" }
                    .clickable(role = Role.Button) {
                        // Tocar el nivel actual lo baja un escalón; tocar otro lo fija.
                        onLevelChange(if (level == segment) lower(segment) else segment)
                    },
            )
        }
        Text(
            text = "${beat + 1}",
            style = monoStyle(10.sp, if (isPlaying) FlGreen else FlMuted, if (isPlaying) FontWeight.Bold else FontWeight.Normal),
        )
    }
}

/** El nivel inmediatamente inferior (suave baja a silencio). */
private fun lower(level: BeatLevel): BeatLevel =
    BeatLevel.values().first { it.nativeId == level.nativeId - 1 }
