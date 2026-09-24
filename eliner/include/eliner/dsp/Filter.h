#pragma once
#include <cmath>
#include <algorithm>

namespace eliner {

// ── 4-pole Moog ladder filter (transposed form) ──────────────────────────────
// Classic warm analog sound used by all professional synths
class Filter {
public:
    explicit Filter(int sampleRate) : mSR(sampleRate) {
        setCutoff(8000.0f);
        setResonance(0.0f);
    }

    // cutoff in Hz (20 – 20000)
    void setCutoff(float hz) {
        hz = std::max(20.0f, std::min(hz, (float)mSR * 0.45f));
        float f  = 2.0f * hz / mSR;
        mG       = 0.9892f * f - 0.4342f * f*f + 0.1381f * f*f*f - 0.0202f * f*f*f*f;
        updateCoeffs();
    }

    // resonance: 0.0 (none) – 1.0 (self-oscillation)
    void setResonance(float res) {
        mRes = res * 3.98f; // scale to Moog range
        updateCoeffs();
    }

    inline float process(float in) {
        // Moog ladder — 4 cascaded 1-pole sections with feedback
        float fb   = mRes * mStage[3];
        float inp  = std::tanh(in - fb);           // soft clip input
        mStage[0]  = mG * (inp        - mStage[0]) + mStage[0];
        mStage[1]  = mG * (mStage[0]  - mStage[1]) + mStage[1];
        mStage[2]  = mG * (mStage[1]  - mStage[2]) + mStage[2];
        mStage[3]  = mG * (mStage[2]  - mStage[3]) + mStage[3];
        return mStage[3];
    }

    void reset() { mStage[0] = mStage[1] = mStage[2] = mStage[3] = 0.0f; }

private:
    void updateCoeffs() {} // coeffs computed inline for performance

    int   mSR;
    float mG      = 0.5f;
    float mRes    = 0.0f;
    float mStage[4] = {0.0f, 0.0f, 0.0f, 0.0f};
};

} // namespace eliner
