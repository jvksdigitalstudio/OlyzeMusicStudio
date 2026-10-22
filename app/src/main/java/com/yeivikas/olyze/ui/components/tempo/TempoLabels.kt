package com.yeivikas.olyze.ui.components.tempo

import com.yeivikas.olyze.eliner.api.transport.BeatLevel
import com.yeivikas.olyze.eliner.api.transport.ClickMode
import com.yeivikas.olyze.eliner.api.transport.ClickSubdivision
import com.yeivikas.olyze.eliner.api.transport.ClickSwing
import com.yeivikas.olyze.eliner.api.transport.CountInLength
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

internal fun CountInLength.label(): String = when (this) {
    CountInLength.OFF -> "OFF"
    CountInLength.ONE_BAR -> "1 compás"
    CountInLength.TWO_BARS -> "2 compases"
    CountInLength.FOUR_BARS -> "4 compases"
}

internal fun CountInLength.description(): String = when (this) {
    CountInLength.OFF -> "Sin conteo previo: la grabación empieza al instante"
    CountInLength.ONE_BAR -> "Conteo previo de 1 compás antes de grabar"
    CountInLength.TWO_BARS -> "Conteo previo de 2 compases antes de grabar"
    CountInLength.FOUR_BARS -> "Conteo previo de 4 compases antes de grabar"
}

internal fun ClickMode.label(): String = when (this) {
    ClickMode.ALWAYS -> "Siempre"
    ClickMode.RECORD_ONLY -> "Al grabar"
    ClickMode.COUNT_IN_ONLY -> "Solo conteo"
}

internal fun ClickMode.description(): String = when (this) {
    ClickMode.ALWAYS -> "El click suena al reproducir y al grabar"
    ClickMode.RECORD_ONLY -> "El click suena solo mientras grabas; al reproducir, silencio"
    ClickMode.COUNT_IN_ONLY -> "El click suena solo en el conteo previo; después, silencio"
}

internal fun ClickSwing.label(): String = when (this) {
    ClickSwing.STRAIGHT -> "Recto"
    ClickSwing.LIGHT -> "58%"
    ClickSwing.MEDIUM -> "62%"
    ClickSwing.TRIPLET -> "Tresillo"
    ClickSwing.HARD -> "75%"
}

internal fun ClickSwing.description(): String = when (this) {
    ClickSwing.STRAIGHT -> "Subdivisión recta, sin swing"
    ClickSwing.LIGHT -> "Swing ligero, 58%"
    ClickSwing.MEDIUM -> "Swing medio, 62%"
    ClickSwing.TRIPLET -> "Swing de tresillo, 67%: el shuffle clásico"
    ClickSwing.HARD -> "Swing fuerte, 75%: punto y semi"
}
