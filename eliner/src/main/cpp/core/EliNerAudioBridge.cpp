#include <jni.h>
#include <memory>
#include <android/log.h>
#include "AudioEngine.h"

#define LOG_TAG "EliNerBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Single global engine instance
static std::unique_ptr<eliner::AudioEngine> gEngine;

extern "C" {

// ── Lifecycle ─────────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeCreate(JNIEnv*, jobject, jint performanceProfile) {
    gEngine = std::make_unique<eliner::AudioEngine>();
    bool ok  = gEngine->start(performanceProfile);
    LOGI("Engine created (profile=%d): %s", performanceProfile, ok ? "OK" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeDestroy(JNIEnv*, jobject) {
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
    if (gEngine) gEngine->noteOn(channel, note, velocity);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeNoteOff(
    JNIEnv*, jobject, jint channel, jint note)
{
    if (gEngine) gEngine->noteOff(channel, note);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeAllNotesOff(JNIEnv*, jobject) {
    if (gEngine) gEngine->allNotesOff();
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSendCC(
    JNIEnv*, jobject, jint channel, jint cc, jint value)
{
    if (gEngine) gEngine->sendCC(channel, cc, value);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetPitchBend(
    JNIEnv*, jobject, jint channel, jfloat semitones)
{
    if (gEngine) gEngine->setPitchBend(channel, semitones);
}

// ── Master controls ───────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetMasterVolume(
    JNIEnv*, jobject, jfloat volume)
{
    if (gEngine) gEngine->setMasterVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetReverbMix(
    JNIEnv*, jobject, jfloat mix)
{
    if (gEngine) gEngine->setReverbMix(mix);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetDelayMix(
    JNIEnv*, jobject, jfloat mix)
{
    if (gEngine) gEngine->setDelayMix(mix);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetDelayTime(
    JNIEnv*, jobject, jfloat seconds)
{
    if (gEngine) gEngine->setDelayTime(seconds);
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetDelayFeedback(
    JNIEnv*, jobject, jfloat feedback)
{
    if (gEngine) gEngine->setDelayFeedback(feedback);
}

// ── Info ──────────────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetSampleRate(JNIEnv*, jobject) {
    return gEngine ? gEngine->getSampleRate() : 48000;
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetBufferSize(JNIEnv*, jobject) {
    return gEngine ? gEngine->getBufferSize() : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetCpuLoad(JNIEnv*, jobject) {
    return gEngine ? gEngine->getCpuLoad() : 0.0f;
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetActiveVoices(JNIEnv*, jobject) {
    return gEngine ? gEngine->getActiveVoices() : 0;
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetXrunCount(JNIEnv*, jobject) {
    return gEngine ? gEngine->getXrunCount() : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetLastCallbackDurationMs(JNIEnv*, jobject) {
    return gEngine ? gEngine->getLastCallbackDurationMs() : 0.0f;
}

JNIEXPORT jlong JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetDroppedCommands(JNIEnv*, jobject) {
    return gEngine ? (jlong)gEngine->getDroppedCommands() : 0L;
}

// ── Realtime Error Flag (Fase 6 §24) ────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetLastError(JNIEnv*, jobject) {
    return gEngine ? (jint)gEngine->getLastError() : 0;
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeClearError(JNIEnv*, jobject) {
    if (gEngine) gEngine->clearError();
}

// ── Dynamic FX chain (Fase 7 — DSP Graph real) ──────────────────────────────
// See AudioEngine::insertModule/removeModule/setModuleParameter/moveModule
// for the full contract. gEngine may be null if called before nativeCreate()
// or after nativeDestroy() — every entry point here degrades to a safe
// no-op/false/sentinel in that case, same convention as every other
// function in this file.

JNIEXPORT jboolean JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeInsertModule(
    JNIEnv*, jobject, jint slot, jint moduleType)
{
    if (!gEngine) return JNI_FALSE;
    return gEngine->insertModule((int)slot, static_cast<eliner::DspModuleType>(moduleType))
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeRemoveModule(
    JNIEnv*, jobject, jint slot)
{
    if (!gEngine) return JNI_FALSE;
    return gEngine->removeModule((int)slot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeSetModuleParameter(
    JNIEnv*, jobject, jint slot, jint paramId, jfloat value)
{
    if (gEngine) gEngine->setModuleParameter((int)slot, (uint8_t)paramId, value);
}

JNIEXPORT jboolean JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeMoveModule(
    JNIEnv*, jobject, jint fromSlot, jint toSlot)
{
    if (!gEngine) return JNI_FALSE;
    return gEngine->moveModule((int)fromSlot, (int)toSlot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeGetModuleType(
    JNIEnv*, jobject, jint slot)
{
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
