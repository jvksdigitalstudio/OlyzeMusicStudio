#pragma once
#include <cstdint>

namespace eliner {

// ── DSP Module — common interface for chain-loadable effects ───────────────
// Fase 7 (DSP Graph real). Any effect that can be inserted into a
// DspChain (see DspChain.h) implements this. Two concrete modules exist
// today — Reverb and Delay (see fx/Reverb.h, fx/Delay.h) — retrofitted
// onto this interface without changing their existing public API (setMix/
// setRoom/setDamp, setTime/setFeedback/setMix stay exactly as they were;
// this only adds the polymorphic surface DspChain needs).
//
// Lifecycle contract (see DspChain.h / AudioEngine.h for the full picture):
//   - Constructed on the CONTROL thread only (allocation is not
//     realtime-safe — see AudioEngine::insertModule()).
//   - process()/setParameter() are called from the AUDIO thread only,
//     once the module has been handed to a DspChain slot.
//   - Destroyed on the CONTROL thread only, after the audio thread has
//     retired the pointer (see AudioEngine::collectGarbage()) — never
//     `delete`d directly from audio-thread code.
enum class DspModuleType : uint8_t {
    Reverb = 0,
    Delay  = 1,
    // Future (fase de efectos comerciales — EQ, Compressor, Sampler, ...):
    // append new values here. Never reuse/renumber an existing one — the
    // native id crosses the JNI boundary and is what Kotlin persists.
    None   = 0xFF, // sentinel: "no module in this slot" — never a real module.
};

class DspModule {
public:
    virtual ~DspModule() = default;

    // In-place stereo processing, same convention as the pre-existing
    // Reverb/Delay::process(): interleaved stereo float buffer, mutated
    // in place. Audio-thread-only.
    virtual void process(float* inout, int numFrames) = 0;

    // paramId is module-type-specific (see e.g. Reverb::Param, Delay::Param
    // in their own headers) — DspChain/AudioEngine pass it through opaquely
    // without interpreting it. Audio-thread-only.
    virtual void setParameter(uint8_t paramId, float value) = 0;

    virtual DspModuleType type() const = 0;
};

} // namespace eliner
