#pragma once

// ── Scoped denormal-flush guard for the real-time audio thread ─────────────
//
// Why this exists: several DSP stages in this engine have classic
// exponentially-decaying feedback state — Filter's 4-pole Moog ladder
// (eliner/include/eliner/dsp/Filter.h: mStage[0..3]) and Reverb's 8 comb
// filters (eliner/include/eliner/fx/Reverb.h: CombFilter::filt / buf) both
// settle toward — but mathematically never exactly reach — zero after a
// note or a reverb/delay tail decays.
//
// On essentially every CPU, arithmetic on subnormal ("denormal") floats —
// nonzero values smaller than FLT_MIN — falls off the fast path and runs
// through microcode that can be 10-100x slower than normal float ops. In a
// hard-real-time audio callback with a fixed per-block time budget (see
// AudioEngine::onAudioReady's mCpuLoad tracking), that slow path is exactly
// what turns "a reverb tail quietly decaying to silence" into an audible
// dropout/glitch — it's a famous enough gotcha in audio DSP to have its own
// name ("the denormal problem"), and this engine had zero protection
// against it before this guard existed.
//
// The fix is two CPU floating-point control-register bits, set once per
// audio callback and restored on scope exit (RAII, so it can never leak
// across callbacks regardless of which return path is taken):
//   - Flush-To-Zero (FTZ): an arithmetic result that would be a denormal is
//     rounded to zero instead.
//   - Denormals-Are-Zero (DAZ, x86 only): a denormal *input* is treated as
//     zero before the operation runs.
// This is the standard fix used by essentially every real-time audio
// engine (e.g. JUCE's ScopedNoDenormals) — it trades an amount of numerical
// precision that lives below the noise floor for eliminating a whole class
// of CPU spikes.
//
// Usage: construct exactly one of these as the first statement in the
// audio callback (see AudioEngine::onAudioReady) — NOT anywhere it could
// be constructed on a control thread, since this changes FP behavior for
// the entire calling thread until the guard goes out of scope.

#include <cstdint>

#if defined(__x86_64__) || defined(__i386__)
    #include <xmmintrin.h>
    #include <pmmintrin.h>
#endif

namespace eliner {

class ScopedDenormalGuard {
public:
    ScopedDenormalGuard() {
#if defined(__x86_64__) || defined(__i386__)
        mPrevState = _mm_getcsr();
        _mm_setcsr(mPrevState | _MM_FLUSH_ZERO_ON | _MM_DENORMALS_ZERO_ON);
#elif defined(__aarch64__)
        uint64_t fpcr;
        asm volatile("mrs %0, fpcr" : "=r"(fpcr));
        mPrevState = fpcr;
        asm volatile("msr fpcr, %0" : : "r"(fpcr | kArmFzBit64));
#elif defined(__arm__) && (defined(__ARM_FP) || defined(__VFP_FP__))
        uint32_t fpscr;
        asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
        mPrevState = fpscr;
        asm volatile("vmsr fpscr, %0" : : "r"(fpscr | kArmFzBit32));
#endif
        // No corresponding branch for a hypothetical soft-float ARM build
        // (no VFP): none of this project's ABIs (arm64-v8a, armeabi-v7a
        // with the NDK's mandatory VFPv3-D16, x86_64) are soft-float, so
        // that configuration is unreachable rather than silently unhandled.
    }

    ~ScopedDenormalGuard() {
#if defined(__x86_64__) || defined(__i386__)
        _mm_setcsr(mPrevState);
#elif defined(__aarch64__)
        asm volatile("msr fpcr, %0" : : "r"(static_cast<uint64_t>(mPrevState)));
#elif defined(__arm__) && (defined(__ARM_FP) || defined(__VFP_FP__))
        asm volatile("vmsr fpscr, %0" : : "r"(static_cast<uint32_t>(mPrevState)));
#endif
    }

    ScopedDenormalGuard(const ScopedDenormalGuard&)            = delete;
    ScopedDenormalGuard& operator=(const ScopedDenormalGuard&) = delete;
    ScopedDenormalGuard(ScopedDenormalGuard&&)                 = delete;
    ScopedDenormalGuard& operator=(ScopedDenormalGuard&&)      = delete;

private:
#if defined(__aarch64__)
    static constexpr uint64_t kArmFzBit64 = 1ULL << 24; // FPCR.FZ
    uint64_t mPrevState = 0;
#elif defined(__arm__) && (defined(__ARM_FP) || defined(__VFP_FP__))
    static constexpr uint32_t kArmFzBit32 = 1u << 24;   // FPSCR.FZ
    uint32_t mPrevState = 0;
#elif defined(__x86_64__) || defined(__i386__)
    unsigned int mPrevState = 0;
#endif
};

} // namespace eliner
