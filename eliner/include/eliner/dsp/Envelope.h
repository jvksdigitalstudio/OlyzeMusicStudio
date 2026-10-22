#pragma once
#include <algorithm>

namespace eliner {

class Envelope {
public:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };

    explicit Envelope(int sampleRate) : mSR(sampleRate) {
        set(0.005f, 0.1f, 0.7f, 0.3f); // default ADSR
    }

    void set(float attackSec, float decaySec, float sustain, float releaseSec) {
        // Defensive clamp, matching Filter::setCutoff's own precedent
        // (eliner/include/eliner/dsp/Filter.h): a zero or negative time
        // here isn't reachable today (SynthVoice's constructor is the
        // only caller, with hardcoded positive constants), but this is
        // the exact kind of function that ends up wired to a live
        // parameter later (see setModuleParameter's FX-chain path for
        // Reverb/Delay/Filter — envelopes are the natural next one), and
        // an untrusted 0 here would divide-by-zero into `+inf`
        // (self-corrects harmlessly for Attack/Decay thanks to their
        // `>=`/`<=` clamp in next(), but Release's `mLevel -= mReleaseRate`
        // with an infinite rate would jump straight past 0 in one step —
        // not a crash, just an unintended instant-release instead of the
        // requested one, or "stuck forever" for a negative time, which
        // WOULD hang a voice open). Clamping the input here is simpler
        // and cheaper than guarding every read site of these rates.
        constexpr float kMinTimeSec = 0.0001f; // 0.1 ms — inaudible as a floor, never zero/negative
        mAttackRate  = 1.0f / (std::max(attackSec,  kMinTimeSec) * mSR);
        mDecayRate   = 1.0f / (std::max(decaySec,   kMinTimeSec) * mSR);
        mSustain     = sustain;
        mReleaseRate = 1.0f / (std::max(releaseSec, kMinTimeSec) * mSR);
    }

    void noteOn()  { mStage = Stage::Attack; }
    void noteOff() { if (mStage != Stage::Idle) mStage = Stage::Release; }
    void reset()   { mLevel = 0.0f; mStage = Stage::Idle; }
    bool isIdle()  const { return mStage == Stage::Idle; }
    float level()  const { return mLevel; }

    inline float next() {
        switch (mStage) {
            case Stage::Idle:    return 0.0f;
            case Stage::Attack:
                mLevel += mAttackRate;
                if (mLevel >= 1.0f) { mLevel = 1.0f; mStage = Stage::Decay; }
                break;
            case Stage::Decay:
                mLevel -= mDecayRate;
                if (mLevel <= mSustain) { mLevel = mSustain; mStage = Stage::Sustain; }
                break;
            case Stage::Sustain:
                break; // hold at sustain level
            case Stage::Release:
                mLevel -= mReleaseRate;
                if (mLevel <= 0.0f) { mLevel = 0.0f; mStage = Stage::Idle; }
                break;
        }
        return mLevel;
    }

private:
    int   mSR;
    Stage mStage     = Stage::Idle;
    float mLevel     = 0.0f;
    float mAttackRate  = 0.0f;
    float mDecayRate   = 0.0f;
    float mSustain     = 0.7f;
    float mReleaseRate = 0.0f;
};

} // namespace eliner
