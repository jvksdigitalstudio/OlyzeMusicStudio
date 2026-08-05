#include "Reverb.h"

namespace eliner {

// Freeverb tuned delay lengths (44100 Hz base, scaled to actual SR)
static const int kCombDelays[8]    = {1116,1188,1277,1356,1422,1491,1557,1617};
static const int kAllpassDelays[4] = {556, 441, 341, 225};

Reverb::Reverb(int sampleRate) : mSR(sampleRate) {
    float scale = sampleRate / 44100.0f;
    for (int i = 0; i < kNumComb; i++) {
        int szL = (int)(kCombDelays[i] * scale);
        int szR = szL + 23; // stereo spread
        mCombL[i].buf.assign(szL, 0.0f);
        mCombR[i].buf.assign(szR, 0.0f);
    }
    for (int i = 0; i < kNumAllpass; i++) {
        int sz = (int)(kAllpassDelays[i] * scale);
        mApL[i].buf.assign(sz, 0.0f);
        mApR[i].buf.assign(sz + 23, 0.0f);
        mApL[i].feedback = mApR[i].feedback = 0.5f;
    }
    updateCoeffs();
}

void Reverb::updateCoeffs() {
    float fb   = mRoom * 0.28f + 0.7f;
    float d1   = mDamp;
    float d2   = 1.0f - d1;
    for (int i = 0; i < kNumComb; i++) {
        mCombL[i].fb = mCombR[i].fb = fb;
        mCombL[i].damp1 = mCombR[i].damp1 = d1;
        mCombL[i].damp2 = mCombR[i].damp2 = d2;
    }
}

void Reverb::process(float* inout, int numFrames) {
    if (mMix < 0.001f) return;
    float wet = mMix * 0.015f; // scale down to avoid clipping
    float dry = 1.0f - mMix * 0.5f;

    for (int n = 0; n < numFrames; n++) {
        float inL = inout[n * 2];
        float inR = inout[n * 2 + 1];
        float mono = (inL + inR) * 0.5f;

        float outL = 0.0f, outR = 0.0f;
        for (int i = 0; i < kNumComb; i++) {
            outL += mCombL[i].process(mono);
            outR += mCombR[i].process(mono);
        }
        for (int i = 0; i < kNumAllpass; i++) {
            outL = mApL[i].process(outL);
            outR = mApR[i].process(outR);
        }
        inout[n * 2]     = inL * dry + outL * wet;
        inout[n * 2 + 1] = inR * dry + outR * wet;
    }
}

} // namespace eliner
