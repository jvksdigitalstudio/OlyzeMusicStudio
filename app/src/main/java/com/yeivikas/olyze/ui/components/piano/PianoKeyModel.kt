package com.yeivikas.olyze.ui.components.piano

private val BLACK_SEMITONES = setOf(1, 3, 6, 8, 10)
internal val NOTE_NAMES = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

data class KeyInfo(
    val midiNote: Int,
    val isBlack: Boolean,
    val octave: Int,
    val semitone: Int,
    val noteName: String,
)

internal val ALL_KEYS: List<KeyInfo> = (0..119).map { note ->
    val semi = note % 12
    val oct  = (note / 12) - 1
    KeyInfo(
        midiNote = note,
        isBlack  = semi in BLACK_SEMITONES,
        octave   = oct,
        semitone = semi,
        noteName = "${NOTE_NAMES[semi]}$oct"
    )
}
