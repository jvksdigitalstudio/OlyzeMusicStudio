#pragma once
#include <vector>
#include <cmath>
#include "DspModule.h"

namespace eliner {

// ── Stereo Ping-Pong Delay ────────────────────────────────────────────────────
// Fase 7: now also a DspModule, so it can be loaded into a DspChain slot
// dynamically. The original public API (setTime/setFeedback/setMix/process)
// is unchanged — setParameter()/type() are additive, not a replacement.
class Delay : public DspModule {
public:
    explicit Delay(int sampleRate);

    void setTime    (float seconds);      // 0.01 – 2.0
    void setFeedback(float fb);           // 0.0 – 0.95
    void setMix     (float mix);          // 0.0 – 1.0

    void process(float* inout, int numFrames) override;

    // ── DspModule interface (Fase 7) ──
    // Maps generic paramId -> the setters above. Values map 1:1 to
    // DspParameterId::Delay{Mix,Time,Feedback} used by AudioEngine's
    // legacy (pre-Fase-7) setDelayMix/Time/Feedback API, so both call
    // paths stay in sync regardless of which one a caller uses.
    enum Param : uint8_t { Mix = 0, Time = 1, Feedback = 2 };
    void setParameter(uint8_t paramId, float value) override;
    DspModuleType type() const override { return DspModuleType::Delay; }

private:
    int   mSR;
    float mMix      = 0.0f;
    float mFeedback = 0.4f;
    int   mDelayLen = 0;

    std::vector<float> mBufL, mBufR;
    int mWriteL = 0, mWriteR = 0;
};

} // namespace eliner
