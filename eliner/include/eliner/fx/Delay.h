#pragma once
#include <vector>
#include <cmath>

namespace eliner {

// ── Stereo Ping-Pong Delay ────────────────────────────────────────────────────
class Delay {
public:
    explicit Delay(int sampleRate);

    void setTime    (float seconds);      // 0.01 – 2.0
    void setFeedback(float fb);           // 0.0 – 0.95
    void setMix     (float mix);          // 0.0 – 1.0

    void process(float* inout, int numFrames);

private:
    int   mSR;
    float mMix      = 0.0f;
    float mFeedback = 0.4f;
    int   mDelayLen = 0;

    std::vector<float> mBufL, mBufR;
    int mWriteL = 0, mWriteR = 0;
};

} // namespace eliner
