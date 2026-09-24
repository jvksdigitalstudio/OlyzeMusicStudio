package com.yeivikas.olyze.eliner.audiofoundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Channel layouts. Only two today, per the spec ("Mono, Stereo" —
 * "arquitectura preparada para futuras expansiones multicanal" means the
 * *type being an enum with a channel count* is what's future-proof, not
 * that more variants should be added speculatively now — e.g. 5.1/7.1
 * would be added here the day something actually consumes them).
 */
enum class AudioChannelLayout(val channelCount: Int) {
    MONO(1),
    STEREO(2),
}

/** Tracks the engine's current channel layout. No processing — a layout selection only. */
class AudioChannelConfiguration {
    private val _layout = MutableStateFlow(AudioChannelLayout.STEREO)
    val layout: StateFlow<AudioChannelLayout> = _layout.asStateFlow()

    fun setLayout(layout: AudioChannelLayout) {
        _layout.value = layout
    }
}
