#include <jni.h>
#include <memory>
#include <mutex>
#include <android/log.h>
#include "AudioEngine.h"

#define LOG_TAG "EliNerBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── gEngine ownership (Fase 1 de estabilización — Objetivo G) ──────────────
// Single global engine instance, ahora protegido por gEngineMutex.
//
// Antes de este fix, cada función JNI hacía `if (gEngine) gEngine->...` sin
// ninguna sincronización — `std::unique_ptr::reset()` no es atómico, así
// que una lectura racy de `gEngine` mientras otro hilo lo resetea es
// undefined behavior (puntero parcialmente escrito, o dereferenciar un
// objeto justo liberado). Esto no era teórico: el proyecto tiene un
// segundo hilo real (`eliner-dsp`, drenando MidiRouter) que puede llamar
// `nativeNoteOn`/etc. en cualquier momento, incluyendo durante una ventana
// de `nativeDestroy()` disparada desde el hilo principal — ver la
// demostración determinista en
// eliner/src/test/cpp/core/test_command_queue_interleaving_proof.cpp
// (para el problema análogo del command queue) y el ADR de esta fase para
// el detalle completo.
//
// Fix: un único std::mutex serializa TODO acceso (lectura Y escritura) al
// puntero global — create, destroy, y cada llamada MIDI/parámetro/FX
// chain. Esto es seguro y NO introduce bloqueo en el hilo de audio
// realtime: el callback de Oboe (AudioEngine::onAudioReady) nunca toca
// `gEngine` — Oboe invoca el callback directamente sobre el objeto
// AudioEngine ya construido (pasado como `this` a setDataCallback en
// AudioEngine::start()), no a través de este puntero global. Todo lo que
// SÍ toca `gEngine` (estas funciones JNI) corre exclusivamente en hilos de
// control (UI, eliner-dsp) — nunca en el hilo de audio — así que un mutex
// aquí es completamente realtime-safe por construcción, no por suerte.
//
// Patrón validado con ThreadSanitizer antes de aplicarlo aquí — ver
// test_gengine_mutex_fix.cpp (no incluido en el árbol del proyecto por
// depender solo de tipos que no son JNI; referenciado en el ADR de esta
// fase con su resultado de ejecución real).
static std::mutex gEngineMutex;
static std::unique_ptr<eliner::AudioEngine> gEngine;

extern "C" {

// ── Lifecycle ─────────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeCreate(JNIEnv*, jobject, jint performanceProfile) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gEngine = std::make_unique<eliner::AudioEngine>();
    bool ok  = gEngine->start(performanceProfile);
    LOGI("Engine created (profile=%d): %s", performanceProfile, ok ? "OK" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeDestroy(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->stop();
        gEngine.reset();
    }
    LOGI("Engine destroyed");
}

// ── MIDI ──────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeNoteOn(
    JNIEnv*, jobject, jint channel, jint note, jint velocity)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->noteOn(channel, note, velocity);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeNoteOff(
    JNIEnv*, jobject, jint channel, jint note)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->noteOff(channel, note);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeAllNotesOff(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->allNotesOff();
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSendCC(
    JNIEnv*, jobject, jint channel, jint cc, jint value)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->sendCC(channel, cc, value);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetPitchBend(
    JNIEnv*, jobject, jint channel, jfloat semitones)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setPitchBend(channel, semitones);
}

// ── Master controls ───────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetMasterVolume(
    JNIEnv*, jobject, jfloat volume)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setMasterVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetReverbMix(
    JNIEnv*, jobject, jfloat mix)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setReverbMix(mix);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetDelayMix(
    JNIEnv*, jobject, jfloat mix)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setDelayMix(mix);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetDelayTime(
    JNIEnv*, jobject, jfloat seconds)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setDelayTime(seconds);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetDelayFeedback(
    JNIEnv*, jobject, jfloat feedback)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setDelayFeedback(feedback);
}

// ── Info ──────────────────────────────────────────────────────────────────────
// Lecturas frecuentes (polling de UI/diagnóstico) — el mutex aquí es breve
// (solo protege el check de gEngine + una llamada que en el propio motor
// ya es un atomic load), no compite con el hilo de audio (que nunca toma
// este mutex) — ver nota de diseño arriba.

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetSampleRate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->getSampleRate() : 48000;
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetBufferSize(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->getBufferSize() : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetCpuLoad(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->getCpuLoad() : 0.0f;
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetActiveVoices(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->getActiveVoices() : 0;
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetXrunCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->getXrunCount() : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetLastCallbackDurationMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? gEngine->getLastCallbackDurationMs() : 0.0f;
}

JNIEXPORT jlong JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetDroppedCommands(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? (jlong)gEngine->getDroppedCommands() : 0L;
}

// ── Realtime Error Flag (Fase 6 §24) ────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetLastError(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine ? (jint)gEngine->getLastError() : 0;
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeClearError(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->clearError();
}

// ── Dynamic FX chain (Fase 7 — DSP Graph real) ──────────────────────────────
// See AudioEngine::insertModule/removeModule/setModuleParameter/moveModule
// for the full contract. gEngine may be null if called before nativeCreate()
// or after nativeDestroy() — every entry point here degrades to a safe
// no-op/false/sentinel in that case, same convention as every other
// function in this file. Now also mutex-protected, same reasoning as above.

JNIEXPORT jboolean JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeInsertModule(
    JNIEnv*, jobject, jint slot, jint moduleType)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) return JNI_FALSE;
    return gEngine->insertModule((int)slot, static_cast<eliner::DspModuleType>(moduleType))
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeRemoveModule(
    JNIEnv*, jobject, jint slot)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) return JNI_FALSE;
    return gEngine->removeModule((int)slot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetModuleParameter(
    JNIEnv*, jobject, jint slot, jint paramId, jfloat value)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setModuleParameter((int)slot, (uint8_t)paramId, value);
}

JNIEXPORT jboolean JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeMoveModule(
    JNIEnv*, jobject, jint fromSlot, jint toSlot)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) return JNI_FALSE;
    return gEngine->moveModule((int)fromSlot, (int)toSlot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetModuleType(
    JNIEnv*, jobject, jint slot)
{
    std::lock_guard<std::mutex> lock(gEngineMutex);
    // Static cast to jint of DspModuleType::None (0xFF) if there's no
    // engine yet — matches what Kotlin's DspModuleType.fromNativeId()
    // treats as "empty" (see EliNerAudioBridge.kt).
    if (!gEngine) return (jint)eliner::DspModuleType::None;
    return (jint)gEngine->getModuleType((int)slot);
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetMaxChainSlots(JNIEnv*, jobject) {
    return eliner::AudioEngine::kMaxChainSlots;
}

} // extern "C"
