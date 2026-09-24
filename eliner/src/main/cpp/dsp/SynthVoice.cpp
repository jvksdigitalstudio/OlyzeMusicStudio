#include "SynthVoice.h"
#include <cmath>

namespace eliner {

std::atomic<uint64_t> SynthVoice::sVoiceCounter{0};

SynthVoice::SynthVoice(int sampleRate)
    : mSR(sampleRate),
      mOsc1(sampleRate),
      mOsc2(sampleRate),
      mAmpEnv(sampleRate),
      mFiltEnv(sampleRate),
      mFilter(sampleRate)
{
    mOsc1.setWaveType(WaveType::Saw);
    mOsc2.setWaveType(WaveType::Saw);
    mOsc2.setDetune(7.0f);   // +7 cents detune for fatness

    // Amp ADSR: fast attack, medium decay, full sustain, medium release
    mAmpEnv.set(0.003f, 0.15f, 1.0f, 0.25f);
    // Filter ADSR: fast attack, short decay, half sustain, medium release
    mFiltEnv.set(0.002f, 0.08f, 0.5f, 0.2f);

    mFilter.setCutoff(4000.0f);
    mFilter.setResonance(0.2f);
}

void SynthVoice::noteOn(int note, float velocity) {
    mNote     = note;
    mVelocity = velocity;
    mAge      = ++sVoiceCounter;

    double hz = noteToHz(note, mPitchBend);
    mOsc1.setFrequency(hz);
    mOsc2.setFrequency(hz);
    mOsc1.reset();
    mOsc2.reset();

    mAmpEnv.reset();
    mFiltEnv.reset();
    mFilter.reset();

    mAmpEnv.noteOn();
    mFiltEnv.noteOn();

    // Filter cutoff responds to velocity (brighter = harder)
    float cutoff = 500.0f + velocity * 8000.0f;
    mFilter.setCutoff(cutoff);
}

void SynthVoice::noteOff() {
    mAmpEnv.noteOff();
    mFiltEnv.noteOff();
}

void SynthVoice::kill() {
    mAmpEnv.reset();
    mFiltEnv.reset();
    mNote = -1;
}

void SynthVoice::setPitchBend(float semitones) {
    mPitchBend = semitones;
    if (mNote >= 0) {
        double hz = noteToHz(mNote, semitones);
        mOsc1.setFrequency(hz);
        mOsc2.setFrequency(hz);
    }
}

bool SynthVoice::isActive() const {
    return mNote >= 0 && !mAmpEnv.isIdle();
}

void SynthVoice::render(float* out, int numFrames) {
    for (int i = 0; i < numFrames; i++) {
        // Mix oscillators (OSC1 70% + OSC2 30%)
        float osc = mOsc1.next() * 0.7f + mOsc2.next() * 0.3f;

        // Filter with envelope modulation
        float filtMod = mFiltEnv.next() * 6000.0f;
        mFilter.setCutoff(400.0f + filtMod + mVelocity * 4000.0f);
        osc = mFilter.process(osc);

        // Amplitude envelope × velocity
        float sample = osc * mAmpEnv.next() * mVelocity * 0.4f;

        // Add to stereo output (center panned)
        out[i * 2]     += sample;
        out[i * 2 + 1] += sample;
    }

    // Kill voice if envelope finished
    if (mAmpEnv.isIdle()) mNote = -1;
}

} // namespace eliner
