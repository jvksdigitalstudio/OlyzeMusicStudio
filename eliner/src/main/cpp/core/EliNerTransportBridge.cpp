// JNI del dominio TRANSPORTE (tempo, transporte, metrónomo, sync del delay).
//
// Adaptador fino y sin lógica: traduce las llamadas de
// com.yeivikas.olyze.eliner.bridge.EliNerTransportBridge a la API de control
// de AudioEngine. Toda validación y toda la semántica viven en AudioEngine
// (hilo de control: validar/limitar/encolar; hilo de audio: aplicar).
//
// Mismo contrato de concurrencia que EliNerAudioBridge.cpp (ver
// EngineHandle.h): cada función toma gEngineMutex y no hace nada si el motor
// todavía no existe o ya fue destruido. La capa Kotlin conserva el estado
// deseado y lo reaplica cuando el motor arranca (nativeCreate construye un
// AudioEngine NUEVO cada vez).
#include <jni.h>
#include <mutex>
#include "EngineHandle.h"

using eliner::jni::gEngine;
using eliner::jni::gEngineMutex;

extern "C" {

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetTempo(JNIEnv*, jobject, jfloat bpm) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setTempo(bpm);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetTransportRunning(JNIEnv*, jobject, jboolean running) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setTransportRunning(running == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetBeatsPerBar(JNIEnv*, jobject, jint beats) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setBeatsPerBar(beats);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetMetronomeEnabled(JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setMetronomeEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetMetronomeVolume(JNIEnv*, jobject, jfloat volume) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setMetronomeVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetMetronomeSound(JNIEnv*, jobject, jint sound) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setMetronomeSound(static_cast<int>(sound));
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetMetronomeAccent(JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setMetronomeAccent(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetMetronomeSubdivision(JNIEnv*, jobject, jint perBeat) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setMetronomeSubdivision(static_cast<int>(perBeat));
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeSetDelayTempoSync(JNIEnv*, jobject, jboolean enabled, jfloat beats) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setDelayTempoSync(enabled == JNI_TRUE, beats);
}

// ADR 0029: instantánea de pulso para el indicador de la UI. Se sondea a ~60 Hz,
// así que NUNCA debe esperar: nativeCreate mantiene gEngineMutex mientras Oboe
// abre el stream (cientos de ms) y un lock_guard aquí congelaría al sondeador.
// Con try_lock, si el candado está ocupado devuelve -1 ("sin dato", la capa
// Kotlin conserva el último valor); sin motor devuelve 0 (reposo).
JNIEXPORT jlong JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerTransportBridge_nativeReadPulse(JNIEnv*, jobject) {
    std::unique_lock<std::mutex> lock(gEngineMutex, std::try_to_lock);
    if (!lock.owns_lock()) return -1;
    if (!gEngine) return 0;
    return static_cast<jlong>(gEngine->pulseSnapshot());
}

} // extern "C"
