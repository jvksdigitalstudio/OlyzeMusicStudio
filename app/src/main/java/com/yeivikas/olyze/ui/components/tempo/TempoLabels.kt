package com.yeivikas.olyze.ui.components.tempo

import com.yeivikas.olyze.eliner.api.transport.BeatLevel
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.MetronomeSound

/**
 * Textos de interfaz de los enums del metrónomo, en UN solo sitio: los usan los selectores, el editor de
 * patrón y las filas de preset, y así un mismo valor nunca se llama de dos maneras distintas.
 */

internal fun MetronomeSound.label(): String = when (this) {
    MetronomeSound.CLASSIC -> "Clásico"
    MetronomeSound.WOOD -> "Madera"
    MetronomeSound.BEEP -> "Beep"
    MetronomeSound.COWBELL -> "Cowbell"
    MetronomeSound.HAT -> "Hi-hat"
}

internal fun ClickSubdivision.label(): String = when (this) {
    ClickSubdivision.NONE -> "OFF"
    ClickSubdivision.EIGHTHS -> "1/8"
    ClickSubdivision.TRIPLETS -> "1/8T"
    ClickSubdivision.SIXTEENTHS -> "1/16"
}

internal fun ClickSubdivision.description(): String = when (this) {
    ClickSubdivision.NONE -> "Sin subdivisión: solo el pulso"
    ClickSubdivision.EIGHTHS -> "Subdivisión en corcheas, 2 clicks por pulso"
    ClickSubdivision.TRIPLETS -> "Subdivisión en tresillos, 3 clicks por pulso"
    ClickSubdivision.SIXTEENTHS -> "Subdivisión en semicorcheas, 4 clicks por pulso"
}

internal fun BeatLevel.label(): String = when (this) {
    BeatLevel.MUTE -> "silencio"
    BeatLevel.SOFT -> "suave"
    BeatLevel.MEDIUM -> "normal"
    BeatLevel.ACCENT -> "acento"
}
