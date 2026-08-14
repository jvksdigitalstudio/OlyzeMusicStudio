#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <array>
#include "SynthVoice.h"
#include "Mixer.h"
#include "CommandQueue.h"
#include "DspChain.h"
#include "DspModuleFactory.h"

namespace eliner {

// ── Constants ──────────────────────────────────────────────
constexpr int    kMaxVoices      = 32;   // polyphony
constexpr int    kPreferredSampleRate = 48000; // requested from Oboe; the
                                                // stream's ACTUAL negotiated
                                                // rate (which may differ per
                                                // device) is what DSP objects
                                                // are built with — see start().
constexpr int    kChannels       = 2;    // stereo
constexpr double kTwoPi          = 6.28318530717958647692;

// ── Realtime Error Flag (Fase 6 §24) ────────────────────────────────────────
// The audio thread never throws — it can't afford C++ exception unwinding
// in a realtime callback, and Oboe's own callback contract doesn't expect
// one either. Instead, anything worth surfacing sets one of these codes
// into an atomic, and the control thread polls/clears it on its own
// schedule (see getLastError()/clearError()). Bitmask, not a single enum
// value, because more than one condition can be true at once (e.g. a
// command queue overflow doesn't stop rendering, so it can coexist with
// a later stream error).
enum EngineErrorFlag : uint32_t {
    kErrorNone               = 0,
    kErrorDspNotReady        = 1u << 0, // onAudioReady() fired before buildDspGraph() finished — output was silence for this callback
    kErrorCommandQueueFull   = 1u << 1, // a control-thread command was dropped (see getDroppedCommands() for the count)
    kErrorStream             = 1u << 2, // Oboe reported a stream error (onErrorAfterClose) — engine attempted reopenStream()
    kErrorStreamRecoveryFailed = 1u << 3, // reopenStream() itself failed after a stream error
    kErrorRetireQueueFull    = 1u << 4, // Fase 7: a removed/replaced DspModule couldn't be pushed onto
                                         // mRetireQueue (it was full) — the pointer leaked instead of
                                         // being freed by collectGarbage(). Audio-thread-safe to raise
                                         // (atomic-only, see raiseError()); unlike the earlier LOGE-based
                                         // version of this signal, this doesn't do I/O on the audio thread.
};

// ── Audio Engine ────────────────────────────────────────────
// Uses Oboe with AAudio backend (lowest latency on Android 8+)
// Falls back to OpenSL ES on older devices automatically.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine();

    // Lifecycle
    // performanceProfile: 0=Automatic, 1=Compatibility, 2=Ultra, 3=Manual.
    // Selects the buffer-size multiplier applied over the device's native
    // burst size (see start() in AudioEngine.cpp) — Compatibility trades
    // latency for headroom on weaker devices, Ultra minimizes latency on
    // capable ones. Chosen by the CALLER (Kotlin, via
    // DeviceCapabilityManager + PerformanceProfileManager — see Fase 6
    // §14-15) — AudioEngine itself does no device detection; it only
    // applies the decision it's given. Manual currently behaves like
    // Automatic (no per-field override plumbing exists yet — see
    // README/ARCHITECTURE for the follow-up).
    bool  start(int performanceProfile = 0);
    void  stop();
    bool  isRunning() const { return mIsRunning.load(); }

    // MIDI events (called from Kotlin/JVM via JNI bridge)
    void  noteOn (int channel, int note, int velocity);
    void  noteOff(int channel, int note);
    void  allNotesOff();
    void  sendCC (int channel, int cc, int value);
    void  setPitchBend(int channel, float semitones);

    // Master controls
    void  setMasterVolume(float vol);   // 0.0 – 1.0
    void  setMasterPan(float pan);      // -1.0 – 1.0
    void  setTempo(float bpm);

    // FX chain — legacy fixed-target API (pre-Fase-7). Kept unchanged as a
    // stable entry point (same principle as Fase 6 §16 / setTempo()):
    // these still work exactly as before, now internally routed through
    // mFxChain by module type (see applyParameter() in the .cpp) rather
    // than directly against dedicated mReverb/mDelay fields. If the
    // targeted module has been removed from the chain via removeModule()
    // (see below), the call is a safe no-op — there is nothing to apply
    // the parameter to.
    void  setReverbMix(float mix);      // 0.0 – 1.0
    void  setDelayMix(float mix);
    void  setDelayTime(float seconds);
    void  setDelayFeedback(float fb);

    // ── Dynamic FX chain (Fase 7 — DSP Graph real) ──────────────────────────
    // See dsp/DspChain.h for the full ownership/threading contract. In
    // short: these are control-thread-facing, asynchronous (applied on the
    // next audio callback, same as every other command), and realtime-safe
    // (no allocation/deallocation ever happens on the audio thread).
    static constexpr int kMaxChainSlots = DspChain::kMaxSlots;

    // Allocates a new module of `type` (control thread) and queues it for
    // insertion at `slot`, replacing whatever is there. Returns false
    // without changing any state if `slot` is out of range or `type` has
    // no factory implementation yet (see DspModuleFactory.cpp) — the
    // allocation, if any, is freed immediately in that case, never leaked.
    bool  insertModule(int slot, DspModuleType type);
    // Queues removal of whatever module occupies `slot`. Returns false
    // (no-op) only if `slot` is out of range.
    bool  removeModule(int slot);
    // Queues a parameter change against whatever module occupies `slot`
    // at the time the command is applied — see e.g. Reverb::Param /
    // Delay::Param for the paramId values a given module type accepts.
    void  setModuleParameter(int slot, uint8_t paramId, float value);
    // Queues relocating the module at `fromSlot` to `toSlot`. Returns
    // false (no-op) only if either slot is out of range.
    bool  moveModule(int fromSlot, int toSlot);
    // Control-thread introspection: what module type currently occupies
    // `slot`? Backed by a control-thread-only shadow of the chain (see
    // mSlotTypesShadow in the .cpp) — NEVER reads mFxChain directly,
    // which is audio-thread-owned. Because insert/remove/move are
    // asynchronous, this reflects the caller's own most recently ISSUED
    // state, which may be one command ahead of what the audio thread has
    // actually applied — same eventual-consistency window every other
    // async command in this engine already has (e.g. setMasterVolume()).
    // Returns DspModuleType::None for an out-of-range slot.
    DspModuleType getModuleType(int slot) const;
    // Frees any modules the audio thread has retired (via removeModule()
    // or insertModule() replacing an occupied slot) since the last call.
    // Never blocks, never allocates, bounded by the retire queue's fixed
    // capacity — safe to call as often as convenient. Called
    // opportunistically from insertModule()/removeModule() already, so
    // most callers never need to call this directly; exposed publicly in
    // case a caller wants a tighter/looser collection cadence.
    void  collectGarbage();

    // Info
    int   getSampleRate()  const { return mSampleRate; }
    int   getBufferSize()  const;
    float getCpuLoad()     const { return mCpuLoad.load(); }
    int   getActiveVoices()const;
    uint64_t getDroppedCommands() const { return mCommandQueue.droppedCount(); }
    // Xrun count as reported by Oboe (control-thread safe query on the stream).
    int32_t  getXrunCount() const;
    // Duration of the most recently completed audio callback, in ms —
    // published by the audio thread alongside CPU load (Fase 6 §23).
    float    getLastCallbackDurationMs() const { return mLastCallbackMs.load(std::memory_order_relaxed); }
    // Total commands successfully applied since start() — a coarse
    // "is anything actually happening" signal distinct from droppedCommands.
    uint64_t getProcessedCommands() const { return mProcessedCommands.load(std::memory_order_relaxed); }

    // ── Realtime Error Flag (§24) — control-thread API ─────────────────────
    // Bitmask of EngineErrorFlag values accumulated since the last
    // clearError(). Never throws, never blocks; the audio thread only
    // ever ORs bits into this atomic, it never clears them — clearing is
    // exclusively a control-thread operation, so there's no cross-thread
    // read-modify-write race on the clear path.
    uint32_t getLastError() const { return mErrorFlags.load(std::memory_order_acquire); }
    void     clearError()         { mErrorFlags.store(kErrorNone, std::memory_order_release); }

    // Oboe callbacks
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream,
        oboe::Result error) override;

private:
    // Audio-thread-only. Drains mCommandQueue (bounded: at most Capacity
    // items, so this loop always terminates deterministically) and applies
    // each command directly to voice/FX state. This is the ONLY place
    // that mutates mVoices / mMasterVolume / mMasterPan / FX parameters —
    // control thread never touches them directly, so no lock is needed.
    void  processCommands();
    void  applyCommand(const EngineCommand& cmd);
    void  renderAudio(float* outputBuffer, int numFrames);
    bool  reopenStream();

    // Control-thread-only. Builds mVoices/mMixer/mFxChain against the
    // stream's ACTUAL negotiated sample rate. Must run after openStream()
    // succeeds and before requestStart(), so the audio thread never sees a
    // partially-constructed engine.
    void  buildDspGraph(int actualSampleRate);

    // Control thread → Audio thread. Never blocks (see CommandQueue.h).
    bool  pushCommand(const EngineCommand& cmd);
    // Convenience wrapper: builds and pushes a SetParameter command.
    void  pushParameter(DspParameterId id, float value);
    // Audio-thread-only: applies a single parameter (called from applyCommand()).
    void  applyParameter(DspParameterId id, float value);

    // ORs a flag into mErrorFlags. Never blocks, never throws, never
    // allocates — safe to call from the audio thread (onAudioReady/
    // renderAudio/processCommands) or from Oboe's error-callback thread
    // (onErrorAfterClose), which is a separate thread, not the audio
    // thread itself, but still not the control thread we can't assume
    // ordering with.
    void  raiseError(uint32_t flag) { mErrorFlags.fetch_or(flag, std::memory_order_relaxed); }

    std::shared_ptr<oboe::AudioStream> mStream;
    std::atomic<bool>                  mIsRunning{false};
    std::atomic<bool>                  mDspReady{false}; // guards onAudioReady
                                                           // against firing
                                                           // before buildDspGraph()
    std::atomic<float>                 mCpuLoad{0.0f};
    std::atomic<float>                 mLastCallbackMs{0.0f};
    std::atomic<uint64_t>              mProcessedCommands{0};
    std::atomic<uint32_t>              mErrorFlags{kErrorNone};
    std::atomic<int>                   mActiveVoiceCount{0}; // published by
                                                              // audio thread once
                                                              // per callback; the
                                                              // only control-thread
                                                              // -safe way to read
                                                              // voice activity.

    int   mSampleRate = kPreferredSampleRate; // updated to actual rate in buildDspGraph()
    int   mPerformanceProfile = 0; // last profile passed to start(); reused by reopenStream()
                                     // after a stream error, so a recovery doesn't silently
                                     // revert to Automatic.

    // ── Audio-thread-owned state (see processCommands()) ──────────────────
    float mMasterVolume = 0.85f;
    float mMasterPan    = 0.0f;

    // Synth voices (max polyphony) — constructed in buildDspGraph() once the
    // real sample rate is known.
    std::array<std::unique_ptr<SynthVoice>, kMaxVoices> mVoices;

    // FX — same lifecycle as mVoices.
    std::unique_ptr<Mixer>  mMixer;

    // ── Dynamic FX chain (Fase 7) ────────────────────────────────────────
    // Audio-thread-owned (see DspChain.h). Default-populated in
    // buildDspGraph() with Reverb@slot0 + Delay@slot1 — same signal path
    // as before this phase — every other slot starts empty.
    DspChain mFxChain;
    // Audio thread → control thread. Modules retired by removeModule()/
    // insertModule() replacement land here instead of being `delete`d on
    // the audio thread; collectGarbage() (control-thread-only) drains and
    // frees them. Capacity 32 mirrors EngineCommandQueue's generosity —
    // module churn per callback is expected to be far below that; if the
    // queue is ever full, push() drops the pointer per SpscCommandQueue's
    // normal overflow policy, which for a retire queue means a leak, not
    // corruption — worth revisiting if module churn ever gets that heavy.
    SpscCommandQueue<DspModule*, 32> mRetireQueue;
    // Control-thread-only mirror of mFxChain's slot contents — see
    // getModuleType()'s doc comment for why this shadow exists instead of
    // reading mFxChain directly.
    std::array<DspModuleType, DspChain::kMaxSlots> mSlotTypesShadow{};

    // Control → Audio command transfer (lock-free SPSC).
    EngineCommandQueue mCommandQueue;
    uint64_t mLastSeenDroppedCount = 0; // audio-thread-only, see processCommands()
};

} // namespace eliner
