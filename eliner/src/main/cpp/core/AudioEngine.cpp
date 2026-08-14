#include "AudioEngine.h"
#include "Reverb.h" // legacy setReverbMix/Room/Damp API needs Reverb::Param — see applyParameter()
#include "Delay.h"  // legacy setDelayMix/Time/Feedback API needs Delay::Param — see applyParameter()
#include <android/log.h>
#include <chrono>
#include <cstring>

#define LOG_TAG "EliNerAudioCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace eliner {

// ── Buffer ownership (Fase 6 §9) ────────────────────────────────────────────
// AudioEngine owns NO render buffer of its own. Oboe hands `renderAudio()`
// an interleaved stereo float buffer sized exactly `numFrames` for this
// callback (frame count is not fixed — it can legitimately vary between
// callbacks depending on device/driver), and every voice/FX stage writes
// or accumulates directly into it. This is the single source of truth for
// buffer lifetime: allocated by Oboe outside our control, valid only for
// the duration of one onAudioReady() call, never retained.
//
// (An unused `mRenderBuffer` member previously lived here, preallocated at
// a fixed 512-frame assumption that Oboe does not guarantee, and never
// read or written by any code path. Removed rather than left ambiguous —
// see Fase 6 audit report.)
AudioEngine::AudioEngine() {
    // Voices/mixer/FX chain are intentionally NOT constructed here — see
    // buildDspGraph(). mSlotTypesShadow is set here, not there, because
    // it must be a defined, all-None state even before the engine is ever
    // started (a control-thread caller can legally call getModuleType()
    // at any time — see its doc comment).
    mSlotTypesShadow.fill(DspModuleType::None);
}

AudioEngine::~AudioEngine() {
    stop();
}

void AudioEngine::buildDspGraph(int actualSampleRate) {
    // Control-thread-only, called once between openStream() and
    // requestStart(). mDspReady is not yet true, so onAudioReady() cannot
    // observe a partially-built graph.
    for (auto& v : mVoices) {
        v = std::make_unique<SynthVoice>(actualSampleRate);
    }
    mMixer  = std::make_unique<Mixer>(actualSampleRate);
    mSampleRate = actualSampleRate;

    // ── Default FX chain (Fase 7) ──────────────────────────────────────
    // If start() is being called again after stop() (e.g. reopenStream()
    // recovering from a stream error), mFxChain may already hold modules
    // from the previous run — free them first so this doesn't leak.
    // Direct chain.clear()/insert() calls (not through the command queue)
    // are safe here specifically because this runs before mDspReady is
    // set true below, so the audio thread cannot be observing mFxChain
    // concurrently — same guarantee mVoices construction above relies on.
    mFxChain.clear();
    mFxChain.insert(0, new Reverb(actualSampleRate));
    mFxChain.insert(1, new Delay(actualSampleRate));
    mSlotTypesShadow.fill(DspModuleType::None);
    mSlotTypesShadow[0] = DspModuleType::Reverb;
    mSlotTypesShadow[1] = DspModuleType::Delay;

    mDspReady.store(true, std::memory_order_release);
}

bool AudioEngine::start(int performanceProfile) {
    mPerformanceProfile = performanceProfile;
    mDspReady.store(false, std::memory_order_release);

    oboe::AudioStreamBuilder builder;
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(kPreferredSampleRate)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Best)
           ->setDataCallback(this)
           ->setErrorCallback(this);
    // Buffer size tuned after stream opens (see below)

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        // Exclusive mode is not guaranteed to be available (varies by
        // device/driver). Fall back to Shared rather than failing outright —
        // Shared still runs through AAudio/OpenSL with acceptable latency.
        LOGE("Exclusive stream open failed (%s) — retrying with SharingMode::Shared",
             oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(mStream);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open stream: %s", oboe::convertToText(result));
            return false;
        }
    }

    // Buffer size tuned per performance profile (Fase 6 §14-15): the
    // multiplier over the device's native burst size, not the sample rate
    // or format, is what we adapt — that's the axis that actually trades
    // latency for stability on Oboe/AAudio.
    //   Automatic (0)     : 2x burst — Oboe's own general-purpose default.
    //   Compatibility (1) : 4x burst — more headroom for weaker/older devices.
    //   Ultra (2)         : 1x burst — minimum latency, for capable devices.
    //   Manual (3)        : behaves like Automatic for now — no per-field
    //                       override plumbing exists yet (would need a
    //                       settings UI to originate a concrete frame
    //                       count, which is out of scope for this phase).
    int32_t burst = mStream->getFramesPerBurst();
    int32_t bufferMultiplier;
    switch (performanceProfile) {
        case 1:  bufferMultiplier = 4; break; // Compatibility
        case 2:  bufferMultiplier = 1; break; // Ultra
        default: bufferMultiplier = 2; break; // Automatic / Manual
    }
    mStream->setBufferSizeInFrames(burst * bufferMultiplier);

    // Build the DSP graph against the ACTUAL negotiated sample rate, not the
    // preferred/requested one — devices are free to grant a different rate.
    buildDspGraph(mStream->getSampleRate());

    LOGI("Stream opened: SR=%d BufferSize=%d Sharing=%s API=%s",
         mSampleRate,
         mStream->getBufferSizeInFrames(),
         oboe::convertToText(mStream->getSharingMode()),
         oboe::convertToText(mStream->getAudioApi()));

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        mDspReady.store(false, std::memory_order_release);
        return false;
    }

    mIsRunning.store(true);
    LOGI("AudioEngine started — LowLatency Oboe");
    return true;
}

void AudioEngine::stop() {
    mDspReady.store(false, std::memory_order_release);
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    mIsRunning.store(false);

    // ── FX chain teardown (Fase 7) ─────────────────────────────────────
    // The stream is fully stopped and closed above, so the audio thread
    // is guaranteed not to be running — safe to touch mFxChain directly
    // from the control thread here, same reasoning as buildDspGraph()'s
    // direct chain.clear()/insert() calls. Order matters: drain any
    // already-retired modules first (collectGarbage()), THEN clear()
    // whatever the chain still holds — otherwise a module retired just
    // before stop() would be freed twice (once by collectGarbage(), once
    // by clear() if it somehow still appeared in a slot — it can't, since
    // remove() already nulled the slot, but draining first is the correct
    // order regardless of that).
    collectGarbage();
    mFxChain.clear();
    mSlotTypesShadow.fill(DspModuleType::None);

    LOGI("AudioEngine stopped");
}

// ── Hot path: called from audio thread ────────────────────────────────────────
oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* /*stream*/,
    void* audioData,
    int32_t numFrames)
{
    auto* out = static_cast<float*>(audioData);

    if (!mDspReady.load(std::memory_order_acquire)) {
        // Graph not built yet (should not normally happen — requestStart()
        // is only called after buildDspGraph() — but guarding here means a
        // future refactor mistake produces silence, not a crash).
        raiseError(kErrorDspNotReady);
        std::memset(out, 0, numFrames * kChannels * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    auto t0 = std::chrono::high_resolution_clock::now();

    // Zero output
    std::memset(out, 0, numFrames * kChannels * sizeof(float));

    // Apply any pending control-thread commands (parameter changes, MIDI)
    // before rendering this block. Bounded, lock-free, no allocation.
    processCommands();

    renderAudio(out, numFrames);

    // CPU load estimate
    auto t1 = std::chrono::high_resolution_clock::now();
    double renderMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
    double budgetMs = (double)numFrames / mSampleRate * 1000.0;
    mCpuLoad.store((float)(renderMs / budgetMs));
    mLastCallbackMs.store((float)renderMs, std::memory_order_relaxed);

    return oboe::DataCallbackResult::Continue;
}

// ── Audio-thread-only: drain command queue ─────────────────────────────────
void AudioEngine::processCommands() {
    EngineCommand cmd;
    // Bounded by construction: the queue has fixed Capacity, so this loop
    // drains at most Capacity items even under sustained producer pressure
    // within this single callback — it cannot spin indefinitely.
    while (mCommandQueue.pop(cmd)) {
        applyCommand(cmd);
        mProcessedCommands.fetch_add(1, std::memory_order_relaxed);
    }
    // A NEW dropped command doesn't stop rendering, but it's worth
    // surfacing. droppedCount() is cumulative (never resets), so this
    // compares against the last-seen value rather than checking ">0"
    // directly — otherwise the flag would re-raise every callback forever
    // after the first-ever drop, making clearError() a no-op for this bit.
    uint64_t dropped = mCommandQueue.droppedCount();
    if (dropped > mLastSeenDroppedCount) {
        raiseError(kErrorCommandQueueFull);
        mLastSeenDroppedCount = dropped;
    }
}

void AudioEngine::applyCommand(const EngineCommand& cmd) {
    switch (cmd.type) {
        case EngineCommandType::NoteOn: {
            // intA = note, intB = velocity (0-127)
            SynthVoice* target = nullptr;
            SynthVoice* oldest = nullptr;
            uint64_t    minAge = UINT64_MAX;
            for (auto& v : mVoices) {
                if (!v->isActive()) { target = v.get(); break; }
                if (v->age() < minAge) { minAge = v->age(); oldest = v.get(); }
            }
            if (!target) target = oldest; // voice steal
            if (target) target->noteOn(cmd.intA, cmd.intB / 127.0f);
            break;
        }
        case EngineCommandType::NoteOff:
            for (auto& v : mVoices) {
                if (v->isActive() && v->note() == cmd.intA) v->noteOff();
            }
            break;
        case EngineCommandType::AllNotesOff:
            for (auto& v : mVoices) v->kill();
            break;
        case EngineCommandType::PitchBend:
            for (auto& v : mVoices) {
                if (v->isActive()) v->setPitchBend(cmd.floatA);
            }
            break;
        case EngineCommandType::SetParameter:
            applyParameter(cmd.paramId, cmd.floatA);
            break;

        // ── Fase 7: dynamic FX chain ────────────────────────────────────
        case EngineCommandType::InsertModule: {
            auto* incoming = static_cast<DspModule*>(cmd.ptrA);
            DspModule* old = mFxChain.insert(cmd.intA, incoming);
            if (old && !mRetireQueue.push(old)) {
                // Retire queue full — see its declaration in AudioEngine.h
                // for why this is a leak, not corruption. Signaled via the
                // same atomic error-flag mechanism as every other
                // audio-thread condition (raiseError()) — NOT via LOGE,
                // which would do I/O on the audio thread. (An earlier
                // version of this code did call LOGE() here; that was a
                // realtime-safety bug, caught and fixed in this
                // hardening pass — see docs/adr for the corresponding
                // audit entry.)
                raiseError(kErrorRetireQueueFull);
            }
            break;
        }
        case EngineCommandType::RemoveModule: {
            DspModule* old = mFxChain.remove(cmd.intA);
            if (old && !mRetireQueue.push(old)) {
                raiseError(kErrorRetireQueueFull);
            }
            break;
        }
        case EngineCommandType::SetModuleParameter:
            mFxChain.setParameter(cmd.intA, static_cast<uint8_t>(cmd.intB), cmd.floatA);
            break;
        case EngineCommandType::MoveModule: {
            mFxChain.move(cmd.intA, cmd.intB);
            DspModule* displaced = mFxChain.takeLastDisplaced();
            if (displaced && !mRetireQueue.push(displaced)) {
                raiseError(kErrorRetireQueueFull);
            }
            break;
        }
    }
}

// Single dispatch point for every float-valued parameter. Adding a new
// parameter later is: one DspParameterId enum value + one case here +
// one public setter that calls pushParameter(). No new command type,
// no new queue plumbing.
void AudioEngine::applyParameter(DspParameterId id, float value) {
    // Fase 7: ReverbXxx/DelayXxx no longer hit a dedicated mReverb/mDelay
    // field — they look up the FIRST module of the matching type in
    // mFxChain and apply there. If the caller previously called
    // removeModule() on the module that used to occupy that role, the
    // lookup returns nullptr and this is a safe no-op — there is nothing
    // to apply the parameter to, and nothing crashes.
    DspModule* reverb;
    DspModule* delay;
    switch (id) {
        case DspParameterId::MasterVolume:  mMasterVolume = value;        break;
        case DspParameterId::MasterPan:     mMasterPan    = value;        break;
        case DspParameterId::ReverbMix:
            reverb = mFxChain.findFirstOfType(DspModuleType::Reverb);
            if (reverb) reverb->setParameter(Reverb::Param::Mix, value);
            break;
        case DspParameterId::ReverbRoom:
            reverb = mFxChain.findFirstOfType(DspModuleType::Reverb);
            if (reverb) reverb->setParameter(Reverb::Param::Room, value);
            break;
        case DspParameterId::ReverbDamp:
            reverb = mFxChain.findFirstOfType(DspModuleType::Reverb);
            if (reverb) reverb->setParameter(Reverb::Param::Damp, value);
            break;
        case DspParameterId::DelayMix:
            delay = mFxChain.findFirstOfType(DspModuleType::Delay);
            if (delay) delay->setParameter(Delay::Param::Mix, value);
            break;
        case DspParameterId::DelayTime:
            delay = mFxChain.findFirstOfType(DspModuleType::Delay);
            if (delay) delay->setParameter(Delay::Param::Time, value);
            break;
        case DspParameterId::DelayFeedback:
            delay = mFxChain.findFirstOfType(DspModuleType::Delay);
            if (delay) delay->setParameter(Delay::Param::Feedback, value);
            break;
    }
}

void AudioEngine::renderAudio(float* out, int numFrames) {
    // No lock: mVoices/mMasterVolume/mMasterPan/FX are exclusively
    // audio-thread-owned once buildDspGraph() has run (see header).

    int active = 0;
    for (auto& voice : mVoices) {
        if (!voice->isActive()) continue;
        voice->render(out, numFrames);
        active++; // voice may go idle inside render(); count pre-render actives
    }
    mActiveVoiceCount.store(active, std::memory_order_relaxed);

    // Apply the dynamic FX chain, in slot order (Fase 7). Defaults to
    // Reverb@0 -> Delay@1 (see buildDspGraph()) — same order as before
    // this phase — but is now a real, reconfigurable graph: whatever is
    // currently loaded, in whatever order, runs here.
    mFxChain.process(out, numFrames);

    // Master volume + pan
    float volL = mMasterVolume * (mMasterPan <= 0 ? 1.0f : 1.0f - mMasterPan);
    float volR = mMasterVolume * (mMasterPan >= 0 ? 1.0f : 1.0f + mMasterPan);
    for (int i = 0; i < numFrames; i++) {
        out[i * 2]     *= volL;
        out[i * 2 + 1] *= volR;
    }
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGE("Stream error: %s — reopening", oboe::convertToText(error));
    raiseError(kErrorStream);
    if (!reopenStream()) {
        raiseError(kErrorStreamRecoveryFailed);
        LOGE("Stream recovery failed — engine is stopped, requires an explicit start() from the control thread");
    }
}

bool AudioEngine::reopenStream() {
    stop();
    return start(mPerformanceProfile);
}

// ── Control thread → command queue (never blocks, never touches DSP state) ──
bool AudioEngine::pushCommand(const EngineCommand& cmd) {
    bool ok = mCommandQueue.push(cmd);
    if (!ok) {
        LOGE("Command queue full — dropped command type=%d", (int)cmd.type);
    }
    return ok;
}

void AudioEngine::pushParameter(DspParameterId id, float value) {
    EngineCommand cmd;
    cmd.type    = EngineCommandType::SetParameter;
    cmd.floatA  = value;
    cmd.paramId = id;
    pushCommand(cmd);
}

// ── MIDI (control thread — JNI-facing) ───────────────────────────────────────
void AudioEngine::noteOn(int /*channel*/, int note, int velocity) {
    pushCommand({EngineCommandType::NoteOn, note, velocity, 0.0f});
}

void AudioEngine::noteOff(int /*channel*/, int note) {
    pushCommand({EngineCommandType::NoteOff, note, 0, 0.0f});
}

void AudioEngine::allNotesOff() {
    pushCommand({EngineCommandType::AllNotesOff, 0, 0, 0.0f});
}

void AudioEngine::sendCC(int channel, int cc, int value) {
    // CC handling — expand per instrument as modules are added
    switch (cc) {
        case 7:  setMasterVolume(value / 127.0f); break;  // Main Volume
        case 10: setMasterPan((value - 64) / 64.0f); break; // Pan
        case 91: setReverbMix(value / 127.0f); break;       // Reverb Send
        case 93: setDelayMix(value / 127.0f); break;        // Chorus/Delay Send
        case 123: allNotesOff(); break;                      // All Notes Off
        default: break;
    }
}

void AudioEngine::setPitchBend(int /*channel*/, float semitones) {
    pushCommand({EngineCommandType::PitchBend, 0, 0, semitones});
}

void AudioEngine::setMasterVolume(float vol) {
    pushParameter(DspParameterId::MasterVolume, vol);
}

void AudioEngine::setMasterPan(float pan) {
    pushParameter(DspParameterId::MasterPan, pan);
}

void AudioEngine::setTempo(float /*bpm*/) {
    // No tempo-dependent DSP exists yet (no sequencer/LFO sync in this
    // phase) — intentionally a no-op until a consumer needs it. Left as a
    // stable API entry point rather than removed, per the "stable public
    // API" principle (Fase 6 §16).
}

void AudioEngine::setReverbMix(float mix) {
    pushParameter(DspParameterId::ReverbMix, mix);
}

void AudioEngine::setDelayMix(float mix) {
    pushParameter(DspParameterId::DelayMix, mix);
}

void AudioEngine::setDelayTime(float s) {
    pushParameter(DspParameterId::DelayTime, s);
}

void AudioEngine::setDelayFeedback(float fb) {
    pushParameter(DspParameterId::DelayFeedback, fb);
}

// ── Dynamic FX chain — control-thread API (Fase 7) ──────────────────────────

bool AudioEngine::insertModule(int slot, DspModuleType type) {
    if (slot < 0 || slot >= DspChain::kMaxSlots) return false;

    // Free anything the audio thread has already retired before doing
    // more work — keeps the retire queue from accumulating across a
    // session of frequent module swaps (see collectGarbage()'s doc
    // comment for why this is safe and cheap to call opportunistically).
    collectGarbage();

    DspModule* mod = createDspModule(type, mSampleRate);
    if (!mod) {
        LOGE("insertModule: no factory for DspModuleType=%d — no-op", (int)type);
        return false; // unimplemented module type — nothing allocated, nothing to free.
    }

    EngineCommand cmd;
    cmd.type = EngineCommandType::InsertModule;
    cmd.intA = slot;
    cmd.ptrA = mod;
    if (!pushCommand(cmd)) {
        // Command queue was full — this command never reaches the audio
        // thread, so `mod` is still exclusively control-thread-owned.
        // Deleting it directly here (not via the retire queue) is
        // correct: the retire queue is for modules the AUDIO thread has
        // taken out of rotation, not ones that never made it in.
        delete mod;
        return false;
    }

    mSlotTypesShadow[slot] = type;
    return true;
}

bool AudioEngine::removeModule(int slot) {
    if (slot < 0 || slot >= DspChain::kMaxSlots) return false;

    EngineCommand cmd;
    cmd.type = EngineCommandType::RemoveModule;
    cmd.intA = slot;
    if (!pushCommand(cmd)) return false;

    mSlotTypesShadow[slot] = DspModuleType::None;
    return true;
}

void AudioEngine::setModuleParameter(int slot, uint8_t paramId, float value) {
    if (slot < 0 || slot >= DspChain::kMaxSlots) return;

    EngineCommand cmd;
    cmd.type   = EngineCommandType::SetModuleParameter;
    cmd.intA   = slot;
    cmd.intB   = paramId;
    cmd.floatA = value;
    pushCommand(cmd);
}

bool AudioEngine::moveModule(int fromSlot, int toSlot) {
    if (fromSlot < 0 || fromSlot >= DspChain::kMaxSlots) return false;
    if (toSlot   < 0 || toSlot   >= DspChain::kMaxSlots) return false;

    EngineCommand cmd;
    cmd.type = EngineCommandType::MoveModule;
    cmd.intA = fromSlot;
    cmd.intB = toSlot;
    if (!pushCommand(cmd)) return false;

    DspModuleType moved = mSlotTypesShadow[fromSlot];
    mSlotTypesShadow[toSlot]   = moved;
    mSlotTypesShadow[fromSlot] = DspModuleType::None;
    return true;
}

DspModuleType AudioEngine::getModuleType(int slot) const {
    if (slot < 0 || slot >= DspChain::kMaxSlots) return DspModuleType::None;
    return mSlotTypesShadow[slot];
}

void AudioEngine::collectGarbage() {
    // Control-thread-only — see mRetireQueue's declaration in
    // AudioEngine.h. `delete` never runs on the audio thread.
    DspModule* mod;
    while (mRetireQueue.pop(mod)) {
        delete mod;
    }
}

int AudioEngine::getBufferSize() const {
    return mStream ? mStream->getBufferSizeInFrames() : 0;
}

int32_t AudioEngine::getXrunCount() const {
    if (!mStream) return 0;
    auto result = mStream->getXRunCount();
    return result ? result.value() : 0;
}

int AudioEngine::getActiveVoices() const {
    // Reads a value published by the audio thread once per callback —
    // never touches SynthVoice internals directly from the control thread.
    return mActiveVoiceCount.load(std::memory_order_relaxed);
}

} // namespace eliner
