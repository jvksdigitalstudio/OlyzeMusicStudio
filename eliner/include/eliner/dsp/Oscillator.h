#pragma once
#include <cmath>
#include <cstdint>

namespace eliner {

enum class WaveType { Sine, Saw, Square, Triangle, Noise };

// ── Band-limited oscillator (PolyBLEP) ──────────────────────────────────────
// Saw/Square/Triangle are naively discontinuous waveforms: a raw "2p - 1"
// sawtooth or a hard p<0.5 square has an infinite-bandwidth jump at each
// cycle boundary. Sampled digitally, everything above Nyquist that jump
// contains folds back down into the audible range as inharmonic aliasing —
// audible as a harsh, metallic edge that gets worse the higher the note,
// completely unrelated to the pitch being played. A hardware analog synth
// never has this problem (there's no sample rate to alias against); a naive
// digital oscillator always does.
//
// PolyBLEP ("Polynomial Bandlimited Step") fixes this cheaply: right at each
// discontinuity, subtract a small 2-sample-wide polynomial correction shaped
// to cancel most of the energy that would otherwise alias, instead of doing
// full bandlimited step-table synthesis (accurate but far more memory/CPU
// per voice than this project's realtime budget — kMaxVoices=32 voices ×
// 2 oscillators each, recomputed every sample — can afford). This is the
// standard, well-established technique (Valimaki/Brandt) used by essentially
// every professional software synth for exactly this tradeoff.
class Oscillator {
public:
    explicit Oscillator(int sampleRate) : mSR(sampleRate) {}

    void  setFrequency(double hz) { mFreq = hz; mPhaseInc = mFreq / mSR; }
    void  setWaveType (WaveType w){ mWave = w; }
    void  setDetune   (float cents){ mDetuneFactor = std::pow(2.0, cents / 1200.0); }

    // Resets phase AND the triangle integrator (see kTriangleLeak below) —
    // without resetting the latter, a new note reusing this voice would
    // start its triangle wave from whatever residual DC level the PREVIOUS
    // note's integrator happened to be sitting at, producing an audible
    // click/offset at note-on instead of starting cleanly at zero.
    void  reset() { mPhase = 0.0; mTriangleIntegrator = 0.0f; }

    // Returns next sample [-1.0, 1.0]
    inline float next() {
        const double dt = mPhaseInc * mDetuneFactor; // normalized phase increment for this sample
        const double p  = mPhase;
        float  s = 0.0f;

        switch (mWave) {
            case WaveType::Sine:
                s = static_cast<float>(std::sin(p * 6.283185307));
                break;

            case WaveType::Saw: {
                s = static_cast<float>(2.0 * p - 1.0);
                s -= static_cast<float>(polyBlep(p, dt)); // correct the single discontinuity at p=0/1
                break;
            }

            case WaveType::Square: {
                s = p < 0.5 ? 1.0f : -1.0f;
                s += static_cast<float>(polyBlep(p, dt));                              // discontinuity at p=0
                s -= static_cast<float>(polyBlep(std::fmod(p + 0.5, 1.0), dt));        // discontinuity at p=0.5
                break;
            }

            case WaveType::Triangle: {
                // Band-limited triangle = leaky-integrated band-limited square.
                // Integrating a clean (already PolyBLEP-corrected) square wave
                // gives a triangle with the same alias suppression "for free",
                // rather than needing a second, differently-shaped correction.
                float sq = p < 0.5 ? 1.0f : -1.0f;
                sq += static_cast<float>(polyBlep(p, dt));
                sq -= static_cast<float>(polyBlep(std::fmod(p + 0.5, 1.0), dt));

                // Scale by 4*dt so peak amplitude stays ~[-1, 1] regardless of
                // frequency (a plain running sum would grow/shrink with pitch),
                // and leak a small fraction toward zero every sample — a pure
                // integrator has no way to "forget" the tiny rounding error it
                // accumulates every sample, which over a long held note would
                // otherwise drift the whole waveform off-center (audible as
                // asymmetric clipping once it drifts far enough). The leak is
                // small enough to be inaudible as amplitude loss but prevents
                // that drift from ever accumulating.
                mTriangleIntegrator = (1.0f - kTriangleLeak) * mTriangleIntegrator
                                     + static_cast<float>(4.0 * dt) * sq;
                s = mTriangleIntegrator;
                break;
            }

            case WaveType::Noise:
                // White noise has no periodic discontinuity to alias against —
                // full-bandwidth by design, nothing for PolyBLEP to correct.
                s = 2.0f * (mLCG = mLCG * 6364136223846793005ULL + 1442695040888963407ULL,
                    (float)(mLCG >> 33) / (float)0x7FFFFFFF) - 1.0f;
                break;
        }

        mPhase += dt;
        if (mPhase >= 1.0) mPhase -= 1.0;
        return s;
    }

private:
    // Standard 2-sample-wide PolyBLEP correction (Valimaki/Brandt), evaluated
    // at normalized phase `t` for a discontinuity located at phase 0 (call it
    // with a phase-shifted `t` — see fmod(p + 0.5, 1.0) above — to place the
    // correction at any other phase, e.g. 0.5 for a square wave's second edge).
    // `dt` is the normalized phase increment for the current sample (i.e. how
    // "wide" one sample is in phase-space) — the correction window scales with
    // it so it stays exactly 2 samples wide at any pitch/sample rate.
    static inline double polyBlep(double t, double dt) {
        if (dt <= 0.0) return 0.0; // guards the divide below for a stopped/zero-frequency oscillator
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1.0;
        }
        if (t > 1.0 - dt) {
            t = (t - 1.0) / dt;
            return t * t + t + t + 1.0;
        }
        return 0.0;
    }

    int      mSR;
    double   mFreq      = 440.0;
    double   mPhase     = 0.0;
    double   mPhaseInc  = 440.0 / 48000.0;
    double   mDetuneFactor = 1.0;
    WaveType mWave      = WaveType::Sine;
    uint64_t mLCG       = 12345678901234567ULL; // fast noise LCG
    float    mTriangleIntegrator = 0.0f;        // leaky-integrator state — Triangle only, see reset()/next()
    static constexpr float kTriangleLeak = 0.0005f; // per-sample leak fraction; negligible amplitude loss, prevents DC drift
};

} // namespace eliner
