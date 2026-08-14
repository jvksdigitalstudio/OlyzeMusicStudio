#pragma once
#include <cmath>
#include <cstdint>

namespace eliner {

enum class WaveType { Sine, Saw, Square, Triangle, Noise };

class Oscillator {
public:
    explicit Oscillator(int sampleRate) : mSR(sampleRate) {}

    void  setFrequency(double hz) { mFreq = hz; mPhaseInc = mFreq / mSR; }
    void  setWaveType (WaveType w){ mWave = w; }
    void  setDetune   (float cents){ mDetuneFactor = std::pow(2.0, cents / 1200.0); }
    void  reset() { mPhase = 0.0; }

    // Returns next sample [-1.0, 1.0]
    inline float next() {
        double p = mPhase;
        float  s = 0.0f;

        switch (mWave) {
            case WaveType::Sine:
                s = static_cast<float>(std::sin(p * 6.283185307));
                break;
            case WaveType::Saw:
                s = static_cast<float>(2.0 * p - 1.0);
                break;
            case WaveType::Square:
                s = p < 0.5 ? 1.0f : -1.0f;
                break;
            case WaveType::Triangle:
                s = static_cast<float>(p < 0.5 ? 4.0 * p - 1.0 : 3.0 - 4.0 * p);
                break;
            case WaveType::Noise:
                s = 2.0f * (mLCG = mLCG * 6364136223846793005ULL + 1442695040888963407ULL,
                    (float)(mLCG >> 33) / (float)0x7FFFFFFF) - 1.0f;
                break;
        }

        mPhase += mPhaseInc * mDetuneFactor;
        if (mPhase >= 1.0) mPhase -= 1.0;
        return s;
    }

private:
    int      mSR;
    double   mFreq      = 440.0;
    double   mPhase     = 0.0;
    double   mPhaseInc  = 440.0 / 48000.0;
    double   mDetuneFactor = 1.0;
    WaveType mWave      = WaveType::Sine;
    uint64_t mLCG       = 12345678901234567ULL; // fast noise LCG
};

} // namespace eliner
