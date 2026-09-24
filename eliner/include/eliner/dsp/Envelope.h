#pragma once

namespace eliner {

class Envelope {
public:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };

    explicit Envelope(int sampleRate) : mSR(sampleRate) {
        set(0.005f, 0.1f, 0.7f, 0.3f); // default ADSR
    }

    void set(float attackSec, float decaySec, float sustain, float releaseSec) {
        mAttackRate  = 1.0f / (attackSec  * mSR);
        mDecayRate   = 1.0f / (decaySec   * mSR);
        mSustain     = sustain;
        mReleaseRate = 1.0f / (releaseSec * mSR);
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
