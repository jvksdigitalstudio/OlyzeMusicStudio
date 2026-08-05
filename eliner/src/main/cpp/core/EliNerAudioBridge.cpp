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
Java_com_yeivikas_olyze_eliner_bridge_EliNerAudioBridge_nativeCreate(JNIEnv*, jobject) {
    gEngine = std::make_unique<eliner::AudioEngine>();
    bool ok  = gEngine->start();
    LOGI("Engine created: %s", ok ? "OK" : "FAILED");
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

} // extern "C"
