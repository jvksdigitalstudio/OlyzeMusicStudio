#pragma once
#include <vector>
#include <array>
#include <cmath>
#include "DspModule.h"

namespace eliner {

// ── Freeverb — classic high-quality room reverb ───────────────────────────────
// 8 parallel comb filters + 4 series allpass filters per channel
//
// Fase 7: now also a DspModule, so it can be loaded into a DspChain slot
// dynamically. The original public API (setMix/setRoom/setDamp/process)
// is unchanged — setParameter()/type() are additive, not a replacement.
class Reverb : public DspModule {
public:
    explicit Reverb(int sampleRate);

    void setMix (float mix)  { mMix  = mix;  }     // 0.0 – 1.0 wet mix
    void setRoom(float room) { mRoom = room; updateCoeffs(); } // 0.0 – 1.0
    void setDamp(float damp) { mDamp = damp; updateCoeffs(); } // 0.0 – 1.0

    // In-place stereo processing
    void process(float* inout, int numFrames) override;

    // ── DspModule interface (Fase 7) ──
    // Maps generic paramId -> the setters above. Values map 1:1 to
    // DspParameterId::Reverb{Mix,Room,Damp} used by AudioEngine's legacy
    // (pre-Fase-7) setReverbMix/Room/Damp API, so both call paths stay
    // in sync regardless of which one a caller uses.
    enum Param : uint8_t { Mix = 0, Room = 1, Damp = 2 };
    void setParameter(uint8_t paramId, float value) override;
    DspModuleType type() const override { return DspModuleType::Reverb; }

private:
    void updateCoeffs();

    int   mSR;
    float mMix  = 0.0f;
    float mRoom = 0.5f;
    float mDamp = 0.4f;

    // Comb filter delays (stereo)
    static constexpr int kNumComb = 8;
    struct CombFilter {
        std::vector<float> buf;
        int    pos   = 0;
        float  filt  = 0.0f;
        float  fb    = 0.0f;
        float  damp1 = 0.0f;
        float  damp2 = 0.0f;
        inline float process(float in) {
            float out = buf[pos];
            filt = out * damp2 + filt * damp1;
            buf[pos++] = in + filt * fb;
            if (pos >= (int)buf.size()) pos = 0;
            return out;
        }
    };

    static constexpr int kNumAllpass = 4;
    struct AllpassFilter {
        std::vector<float> buf;
        int   pos = 0;
        float feedback = 0.5f;
        inline float process(float in) {
            float out = buf[pos];
            float tmp = in + out * feedback;
            buf[pos++] = tmp;
            if (pos >= (int)buf.size()) pos = 0;
            return out - in;
        }
    };

    std::array<CombFilter,    kNumComb>    mCombL, mCombR;
    std::array<AllpassFilter, kNumAllpass> mApL,   mApR;
};

} // namespace eliner
