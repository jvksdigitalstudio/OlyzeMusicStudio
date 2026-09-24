#pragma once
#include <cstdint>
#include <atomic>
#include "Oscillator.h"
#include "Envelope.h"
#include "Filter.h"

namespace eliner {

// MIDI note → frequency table
inline double noteToHz(int note, float pitchBendSemitones = 0.0f) {
    return 440.0 * std::pow(2.0, (note - 69 + pitchBendSemitones) / 12.0);
}

class SynthVoice {
public:
    explicit SynthVoice(int sampleRate);

    void noteOn (int note, float velocity);
    void noteOff();
    void kill();  // immediate silence (voice steal)

    void setPitchBend(float semitones);

    // Returns true while producing sound
    bool isActive()  const;
    int  note()      const { return mNote; }
    uint64_t age()   const { return mAge; }

    // Renders numFrames of stereo audio into out (interleaved L/R), ADDS to existing
    void render(float* out, int numFrames);

private:
    int        mSR;
    int        mNote     = -1;
    float      mVelocity = 1.0f;
    float      mPitchBend = 0.0f;
    uint64_t   mAge      = 0;

    // 2 oscillators per voice for richness
    Oscillator mOsc1;
    Oscillator mOsc2;   // detuned
    Envelope   mAmpEnv;
    Envelope   mFiltEnv;
    Filter     mFilter;

    static std::atomic<uint64_t> sVoiceCounter;
};

} // namespace eliner
