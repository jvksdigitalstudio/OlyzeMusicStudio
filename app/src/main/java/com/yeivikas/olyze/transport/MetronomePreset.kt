package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound
import java.util.Locale

/**
 * Una configuración completa del metrónomo que se puede guardar y recuperar ("Crear / Cargar").
 *
 * Es un valor INMUTABLE y comparable: dos presets con los mismos campos son el mismo preset (así la
 * biblioteca no guarda duplicados y la UI sabe cuál es el "activo"). No incluye si el metrónomo está
 * encendido ni si el transporte corre: un preset describe CÓMO suena, no el estado de reproducción.
 */
data class MetronomePreset(
    /** Tempo en negras por minuto. */
    val bpm: Float,
    val beatsPerBar: Int,
    val beatUnit: BeatUnit,
    val pattern: BeatPattern,
    val sound: MetronomeSound,
    val subdivision: ClickSubdivision,
    /** 0.0–1.0. */
    val volume: Float,
) {
    /** Compás en texto, p. ej. "6/8". */
    val timeSignature: String get() = "$beatsPerBar/${beatUnit.denominator}"

    /** Título corto para listas: nombre del tempo, BPM y compás, p. ej. "Allegro 120.0 · 4/4". */
    val title: String
        get() = "${TempoMarking.forBpm(bpm)} ${String.format(Locale.US, "%.1f", bpm)} · $timeSignature"
}
