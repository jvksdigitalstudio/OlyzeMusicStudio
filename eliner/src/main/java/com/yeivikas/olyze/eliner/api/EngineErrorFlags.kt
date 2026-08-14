package com.yeivikas.olyze.eliner.api

/**
 * Kotlin-side mirror of the native `EngineErrorFlag` bitmask
 * (`AudioEngine.h`, Fase 6 §24). The audio thread never throws — it ORs
 * bits into an atomic instead, and this is how the control thread reads
 * them back after [EliNerAudioApi.refreshStats].
 *
 * A value class over the raw [Int] bitmask, not a sealed class or enum
 * set, because more than one bit can be set simultaneously (see the
 * native header for why) and this needs to stay a single cheap JNI
 * round-trip — no allocation per query.
 */
@JvmInline
value class EngineErrorFlags(val bits: Int) {
    val isClean: Boolean get() = bits == 0
    val dspNotReady: Boolean get() = bits and (1 shl 0) != 0
    val commandQueueFull: Boolean get() = bits and (1 shl 1) != 0
    val streamError: Boolean get() = bits and (1 shl 2) != 0
    val streamRecoveryFailed: Boolean get() = bits and (1 shl 3) != 0

    companion object {
        val NONE = EngineErrorFlags(0)
    }
}
