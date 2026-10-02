#include "Delay.h"
#include <algorithm>

namespace eliner {

void Delay::setParameter(uint8_t paramId, float value) {
    switch (static_cast<Param>(paramId)) {
        case Param::Mix:      setMix(value);      break;
        case Param::Time:     setTime(value);     break;
        case Param::Feedback: setFeedback(value); break;
    }
}

Delay::Delay(int sampleRate) : mSR(sampleRate) {
    int maxLen = sampleRate * 2; // 2 sec max
    mBufL.assign(maxLen, 0.0f);
    mBufR.assign(maxLen, 0.0f);
    setTime(0.375f); // 3/8 sec default (synced to 120 BPM at 1/4 note)
}

void Delay::setTime(float seconds) {
    mDelayLen = (int)(seconds * mSR);
    mDelayLen = std::max(1, std::min(mDelayLen, (int)mBufL.size() - 1));
}

void Delay::setFeedback(float fb) {
    mFeedback = std::max(0.0f, std::min(fb, 0.95f));
}

void Delay::setMix(float mix) {
    mMix = std::max(0.0f, std::min(mix, 1.0f));
}

void Delay::process(float* inout, int numFrames) {
    if (mMix < 0.001f) return;
    float wet = mMix * 0.7f;
    float dry = 1.0f;

    for (int n = 0; n < numFrames; n++) {
        float inL = inout[n * 2];
        float inR = inout[n * 2 + 1];

        // Read delay (ping-pong: L reads R buffer, R reads L buffer)
        int readL = (mWriteL - mDelayLen + (int)mBufL.size()) % (int)mBufL.size();
        int readR = (mWriteR - mDelayLen + (int)mBufR.size()) % (int)mBufR.size();

        float delayL = mBufR[readR]; // ping-pong cross
        float delayR = mBufL[readL];

        mBufL[mWriteL] = inL + delayL * mFeedback;
        mBufR[mWriteR] = inR + delayR * mFeedback;

        mWriteL = (mWriteL + 1) % (int)mBufL.size();
        mWriteR = (mWriteR + 1) % (int)mBufR.size();

        inout[n * 2]     = inL * dry + delayL * wet;
        inout[n * 2 + 1] = inR * dry + delayR * wet;
    }
}

} // namespace eliner
