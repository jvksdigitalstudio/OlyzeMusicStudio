#include "AudioEngine.h"
#include <android/log.h>
#include <chrono>
#include <cstring>

#define LOG_TAG "EliNerAudioCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace eliner {

AudioEngine::AudioEngine() {
    // Init voices
    for (auto& v : mVoices) {
        v = std::make_unique<SynthVoice>(kSampleRate);
    }
    mMixer  = std::make_unique<Mixer>(kSampleRate);
    mReverb = std::make_unique<Reverb>(kSampleRate);
    mDelay  = std::make_unique<Delay>(kSampleRate);
    mRenderBuffer.resize(kMaxVoices * 2 * 512, 0.0f); // stereo * max frames
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    oboe::AudioStreamBuilder builder;
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(kSampleRate)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Best)
           ->setDataCallback(this)
           ->setErrorCallback(this);
    // Buffer size tuned after stream opens (see below)

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return false;
    }

    // Set to optimal buffer size for this device
    mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
    mSampleRate = mStream->getSampleRate();
    LOGI("Stream opened: SR=%d BufferSize=%d API=%s",
         mSampleRate,
         mStream->getBufferSizeInFrames(),
         oboe::convertToText(mStream->getAudioApi()));

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        return false;
    }

    mIsRunning.store(true);
    LOGI("AudioEngine started — LowLatency Oboe");
    return true;
}

void AudioEngine::stop() {
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    mIsRunning.store(false);
    LOGI("AudioEngine stopped");
}

// ── Hot path: called from audio thread ────────────────────────────────────────
oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* /*stream*/,
    void* audioData,
    int32_t numFrames)
{
    auto t0 = std::chrono::high_resolution_clock::now();

    auto* out = static_cast<float*>(audioData);
    // Zero output
    std::memset(out, 0, numFrames * kChannels * sizeof(float));

    renderAudio(out, numFrames);

    // CPU load estimate
    auto t1 = std::chrono::high_resolution_clock::now();
    double renderMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
    double budgetMs = (double)numFrames / mSampleRate * 1000.0;
    mCpuLoad.store((float)(renderMs / budgetMs));

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::renderAudio(float* out, int numFrames) {
    std::lock_guard<std::mutex> lock(mVoiceMutex);

    // Accumulate all active voices into output
    for (auto& voice : mVoices) {
        if (!voice->isActive()) continue;
        voice->render(out, numFrames);
    }

    // Apply FX chain: Reverb → Delay
    mReverb->process(out, numFrames);
    mDelay->process(out, numFrames);

    // Master volume + pan
    float volL = mMasterVolume * (mMasterPan <= 0 ? 1.0f : 1.0f - mMasterPan);
    float volR = mMasterVolume * (mMasterPan >= 0 ? 1.0f : 1.0f + mMasterPan);
    for (int i = 0; i < numFrames; i++) {
        out[i * 2]     *= volL;
        out[i * 2 + 1] *= volR;
    }
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGE("Stream error: %s — reopening", oboe::convertToText(error));
    reopenStream();
}

bool AudioEngine::reopenStream() {
    stop();
    return start();
}

// ── MIDI ──────────────────────────────────────────────────────────────────────
void AudioEngine::noteOn(int channel, int note, int velocity) {
    std::lock_guard<std::mutex> lock(mVoiceMutex);
    // Find a free voice (oldest-steal if all busy)
    SynthVoice* target = nullptr;
    SynthVoice* oldest = nullptr;
    uint64_t    minAge = UINT64_MAX;

    for (auto& v : mVoices) {
        if (!v->isActive()) { target = v.get(); break; }
        if (v->age() < minAge) { minAge = v->age(); oldest = v.get(); }
    }
    if (!target) target = oldest; // voice steal
    if (target)  target->noteOn(note, velocity / 127.0f);
}

void AudioEngine::noteOff(int channel, int note) {
    std::lock_guard<std::mutex> lock(mVoiceMutex);
    for (auto& v : mVoices) {
        if (v->isActive() && v->note() == note) {
            v->noteOff();
        }
    }
}

void AudioEngine::allNotesOff() {
    std::lock_guard<std::mutex> lock(mVoiceMutex);
    for (auto& v : mVoices) { v->kill(); }
}

void AudioEngine::sendCC(int channel, int cc, int value) {
    // CC handling — expand per instrument as modules are added
    switch (cc) {
        case 7:  setMasterVolume(value / 127.0f); break;  // Main Volume
        case 10: setMasterPan((value - 64) / 64.0f); break; // Pan
        case 91: setReverbMix(value / 127.0f); break;       // Reverb Send
        case 93: setDelayMix(value / 127.0f); break;        // Chorus/Delay Send
        case 123: allNotesOff(); break;                      // All Notes Off
        default: break;
    }
}

void AudioEngine::setPitchBend(int channel, float semitones) {
    std::lock_guard<std::mutex> lock(mVoiceMutex);
    for (auto& v : mVoices) {
        if (v->isActive()) v->setPitchBend(semitones);
    }
}

void AudioEngine::setMasterVolume(float vol) { mMasterVolume = vol; }
void AudioEngine::setMasterPan   (float pan) { mMasterPan    = pan; }
void AudioEngine::setReverbMix   (float mix) { mReverb->setMix(mix); }
void AudioEngine::setDelayMix    (float mix) { mDelay->setMix(mix); }
void AudioEngine::setDelayTime   (float s)   { mDelay->setTime(s); }
void AudioEngine::setDelayFeedback(float fb) { mDelay->setFeedback(fb); }

int AudioEngine::getBufferSize() const {
    return mStream ? mStream->getBufferSizeInFrames() : 0;
}

int AudioEngine::getActiveVoices() const {
    int count = 0;
    for (auto& v : mVoices) if (v->isActive()) count++;
    return count;
}

} // namespace eliner
