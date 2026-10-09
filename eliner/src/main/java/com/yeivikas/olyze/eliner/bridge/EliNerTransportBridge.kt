package com.yeivikas.olyze.eliner.bridge

/**
 * Adaptador JNI del dominio de transporte (ADR 0028).
 *
 * Solo traduce llamadas Kotlin → funciones `Java_…_EliNerTransportBridge_*`
 * de `EliNerTransportBridge.cpp`. No guarda estado ni valida nada: la
 * validación vive en el motor nativo y el estado deseado en
 * [TransportController]. Las funciones nativas descartan la orden si el motor
 * todavía no existe (mismo contrato que `EliNerAudioBridge`, ver
 * `EngineHandle.h`).
 *
 * No es un singleton: el estado vive en el nativo (global) y este adaptador
 * es sin estado, así que cada motor puede tener el suyo sin riesgo.
 */
internal class EliNerTransportBridge : TransportNative {

    override fun setTempo(bpm: Float) = nativeSetTempo(bpm)
    override fun setTransportRunning(running: Boolean) = nativeSetTransportRunning(running)
    override fun setBeatsPerBar(beats: Int) = nativeSetBeatsPerBar(beats)
    override fun setMetronomeEnabled(enabled: Boolean) = nativeSetMetronomeEnabled(enabled)
    override fun setMetronomeVolume(volume: Float) = nativeSetMetronomeVolume(volume)
    override fun setDelayTempoSync(enabled: Boolean, beats: Float) = nativeSetDelayTempoSync(enabled, beats)
    override fun setMetronomeSound(sound: Int) = nativeSetMetronomeSound(sound)
    override fun setMetronomeAccent(enabled: Boolean) = nativeSetMetronomeAccent(enabled)
    override fun setMetronomeSubdivision(perBeat: Int) = nativeSetMetronomeSubdivision(perBeat)
    override fun readPulse(): Long = nativeReadPulse()

    private external fun nativeSetTempo(bpm: Float)
    private external fun nativeSetTransportRunning(running: Boolean)
    private external fun nativeSetBeatsPerBar(beats: Int)
    private external fun nativeSetMetronomeEnabled(enabled: Boolean)
    private external fun nativeSetMetronomeVolume(volume: Float)
    private external fun nativeSetDelayTempoSync(enabled: Boolean, beats: Float)
    private external fun nativeSetMetronomeSound(sound: Int)
    private external fun nativeSetMetronomeAccent(enabled: Boolean)
    private external fun nativeSetMetronomeSubdivision(perBeat: Int)
    private external fun nativeReadPulse(): Long

    companion object {
        init {
            // Misma librería que EliNerAudioBridge: cargarla dos veces es inocuo
            // (System.loadLibrary es idempotente por nombre y por classloader).
            System.loadLibrary("eliner_audio_core")
        }
    }
}
