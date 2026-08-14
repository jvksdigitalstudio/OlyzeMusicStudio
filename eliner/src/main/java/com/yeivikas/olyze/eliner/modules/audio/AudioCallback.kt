package com.yeivikas.olyze.eliner.modules.audio

/**
 * Describes a buffer a real audio callback would hand off, without
 * carrying actual sample data — this phase builds the shape, not the flow.
 * `frameCount`/`channelCount` are enough for a future implementation to
 * know how much data to expect.
 */
data class AudioBufferHandle(val frameCount: Int, val channelCount: Int)

/**
 * What a future consumer implements to receive audio buffers. Deliberately
 * backend-agnostic — nothing here mentions Oboe, AAudio, or OpenSL ES, so
 * "la arquitectura debe permitir intercambiar el backend sin modificar el
 * resto del motor" holds by construction: whichever backend eventually
 * drives real callbacks only needs to call [AudioCallbackRegistry.dispatch]
 * with an [AudioBufferHandle], and every registered [AudioCallback] reacts
 * identically regardless of which backend produced it.
 */
interface AudioCallback {
    fun onAudioReady(handle: AudioBufferHandle)
}

/**
 * Registry of [AudioCallback]s. **Nothing calls [dispatch] yet** — no
 * native backend is wired to this in this phase ("no implementar DSP
 * todavía"). This is the infrastructure a future backend-integration phase
 * will call into, not a running callback loop.
 */
class AudioCallbackRegistry {
    private val lock = Any()
    private val callbacks = mutableListOf<AudioCallback>()

    fun register(callback: AudioCallback) = synchronized(lock) { callbacks.add(callback) }

    fun unregister(callback: AudioCallback): Boolean = synchronized(lock) { callbacks.remove(callback) }

    /**
     * Notifies every registered [AudioCallback] that [handle] is ready.
     * Not called by anything in this phase — reserved for the future
     * native backend integration.
     */
    fun dispatch(handle: AudioBufferHandle) {
        val snapshot = synchronized(lock) { callbacks.toList() }
        snapshot.forEach { it.onAudioReady(handle) }
    }

    fun registeredCount(): Int = synchronized(lock) { callbacks.size }
}
