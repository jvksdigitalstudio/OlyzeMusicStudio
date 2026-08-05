#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <array>
#include <mutex>
#include "SynthVoice.h"
#include "Mixer.h"
#include "Reverb.h"
#include "Delay.h"

namespace eliner {

// ── Constants ──────────────────────────────────────────────
constexpr int    kMaxVoices      = 32;   // polyphony
constexpr int    kSampleRate     = 48000; // 48kHz — pro standard
constexpr int    kChannels       = 2;    // stereo
constexpr double kTwoPi          = 6.28318530717958647692;

// ── Audio Engine ────────────────────────────────────────────
// Uses Oboe with AAudio backend (lowest latency on Android 8+)
// Falls back to OpenSL ES on older devices automatically.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine();

    // Lifecycle
    bool  start();
    void  stop();
    bool  isRunning() const { return mIsRunning.load(); }

    // MIDI events (called from Kotlin/JVM via JNI bridge)
    void  noteOn (int channel, int note, int velocity);
    void  noteOff(int channel, int note);
    void  allNotesOff();
    void  sendCC (int channel, int cc, int value);
    void  setPitchBend(int channel, float semitones);

    // Master controls
    void  setMasterVolume(float vol);   // 0.0 – 1.0
    void  setMasterPan(float pan);      // -1.0 – 1.0
    void  setTempo(float bpm);

    // FX chain
    void  setReverbMix(float mix);      // 0.0 – 1.0
    void  setDelayMix(float mix);
    void  setDelayTime(float seconds);
    void  setDelayFeedback(float fb);

    // Info
    int   getSampleRate()  const { return mSampleRate; }
    int   getBufferSize()  const;
    float getCpuLoad()     const { return mCpuLoad.load(); }
    int   getActiveVoices()const;

    // Oboe callbacks
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream,
        oboe::Result error) override;

private:
    void  renderAudio(float* outputBuffer, int numFrames);
    bool  reopenStream();

    std::shared_ptr<oboe::AudioStream> mStream;
    std::atomic<bool>                  mIsRunning{false};
    std::atomic<float>                 mCpuLoad{0.0f};

    int   mSampleRate = kSampleRate;
    float mMasterVolume = 0.85f;
    float mMasterPan    = 0.0f;

    // Synth voices (max polyphony)
    std::array<std::unique_ptr<SynthVoice>, kMaxVoices> mVoices;
    std::mutex mVoiceMutex;

    // FX
    std::unique_ptr<Mixer>  mMixer;
    std::unique_ptr<Reverb> mReverb;
    std::unique_ptr<Delay>  mDelay;

    // Temp render buffer
    std::vector<float> mRenderBuffer;
};

} // namespace eliner
