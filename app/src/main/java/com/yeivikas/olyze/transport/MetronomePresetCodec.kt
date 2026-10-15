package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.BeatLevel
import com.yeivikas.olyze.eliner.api.transport.BeatPattern
import com.yeivikas.olyze.eliner.api.transport.BeatUnit
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound

/**
 * Serialización de un [MetronomePreset] a una línea de texto y de vuelta.
 *
 * ## Formato (versión 1)
 * `v1;bpm;compás;unidad;patrón;sonido;subdivisión;volumen`, p. ej. `v1;120.0;4;4;3222222222222222;0;1;0.7`.
 *  - `unidad` = denominador, `patrón` = 16 dígitos 0–3 (un [BeatLevel] por tiempo), `sonido` = índice
 *    nativo, `subdivisión` = clicks por pulso. Sin espacios ni escapes: ningún campo puede contener `;`.
 *  - El prefijo de versión permite cambiar el formato sin romper lo ya guardado: una versión
 *    desconocida se rechaza en vez de interpretarse mal.
 *
 * ## Decodificación estricta
 * Lo guardado es ENTRADA NO FIABLE (se puede corromper, editar o venir de otra versión de la app), y se
 * envía al motor. [decode] valida CADA campo contra los rangos del motor y devuelve `null` ante
 * cualquier duda, nunca un preset "arreglado" a medias: un preset roto se descarta entero.
 */
object MetronomePresetCodec {

    private const val VERSION = "v1"
    private const val FIELDS = 8

    fun encode(p: MetronomePreset): String = listOf(
        VERSION,
        p.bpm.toString(),
        p.beatsPerBar.toString(),
        p.beatUnit.denominator.toString(),
        (0 until BeatPattern.MAX_BEATS).joinToString("") { p.pattern[it].nativeId.toString() },
        p.sound.nativeId.toString(),
        p.subdivision.perBeat.toString(),
        p.volume.toString(),
    ).joinToString(";")

    /** El preset de [line], o `null` si la línea no es un preset válido de la versión 1. */
    fun decode(line: String): MetronomePreset? {
        val f = line.trim().split(';')
        if (f.size != FIELDS || f[0] != VERSION) return null

        val bpm = f[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        if (bpm < EliNerTransportApi.MIN_TEMPO_BPM || bpm > EliNerTransportApi.MAX_TEMPO_BPM) return null

        val beats = f[2].toIntOrNull() ?: return null
        if (beats < EliNerTransportApi.MIN_BEATS_PER_BAR || beats > EliNerTransportApi.MAX_BEATS_PER_BAR) return null

        val unit = f[3].toIntOrNull()?.let { d -> BeatUnit.values().firstOrNull { it.denominator == d } } ?: return null

        val digits = f[4]
        if (digits.length != BeatPattern.MAX_BEATS) return null
        val levels = digits.map { c ->
            // Solo '0'..'3' ASCII: digitToIntOrNull aceptaría dígitos Unicode de otros alfabetos.
            if (c !in '0'..'3') return null
            BeatLevel.values().first { it.nativeId == c - '0' }
        }

        val sound = f[5].toIntOrNull()?.let { id -> MetronomeSound.values().firstOrNull { it.nativeId == id } } ?: return null
        val subdivision = f[6].toIntOrNull()?.let { n -> ClickSubdivision.values().firstOrNull { it.perBeat == n } } ?: return null

        val volume = f[7].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        if (volume < 0f || volume > 1f) return null

        return MetronomePreset(bpm, beats, unit, BeatPattern.of(levels), sound, subdivision, volume)
    }
}
