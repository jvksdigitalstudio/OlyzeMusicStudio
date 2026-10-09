#pragma once
#include <array>
#include "DspModule.h"

namespace eliner {

// ── DSP Chain — ordered, fixed-capacity slots of loadable modules ──────────
// Fase 7 (DSP Graph real). Replaces the previously hardcoded
// "mReverb->process(); mDelay->process();" sequence in AudioEngine with an
// ordered array of DspModule* that can be inserted, removed, and reordered
// at runtime — the actual capability the Fase 6 → Fase 7 handoff (ADR 0010,
// "próxima fase") called out as needing a real DSP graph, scoped here to
// exactly the single channel that exists today (see README roadmap: full
// multi-channel Mixer is future work, this is the vertical slice).
//
// Ownership / threading — read this before touching this class:
//   - This object is constructed and iterated ONLY by the audio thread
//     (see AudioEngine::renderAudio(), AudioEngine::applyCommand()).
//     Nothing here is atomic because nothing here is meant to be touched
//     from more than one thread concurrently.
//   - insert()/remove()/move()/setParameter() are called EXCLUSIVELY from
//     AudioEngine::applyCommand(), which itself only runs on the audio
//     thread while draining the command queue (see CommandQueue.h) — the
//     control thread never calls these directly. This mirrors exactly how
//     mVoices is already handled elsewhere in AudioEngine.
//   - insert()/remove() return the module that previously occupied the
//     slot (or nullptr) INSTEAD of deleting it. Deleting on the audio
//     thread is not realtime-safe (heap deallocation can lock internally
//     on most allocators) — the caller (AudioEngine) is responsible for
//     pushing the returned pointer onto the retire queue so it gets freed
//     on the control thread instead. See AudioEngine::collectGarbage().
//   - clear() is the one exception: it DOES delete every remaining slot
//     directly. It exists for engine shutdown (AudioEngine::stop()),
//     which only runs once the audio thread is guaranteed to be stopped —
//     see the call site for why that's safe there and nowhere else.
class DspChain {
public:
    static constexpr int kMaxSlots = 8;

    // Renders every occupied slot in order (slot 0 first), in place.
    // Empty slots are skipped. Audio-thread-only.
    void process(float* inout, int numFrames) {
        for (auto* module : mSlots) {
            if (module) module->process(inout, numFrames);
        }
    }

    // Places `module` at `slot`, returning whatever was there before (or
    // nullptr). Caller owns retiring the returned pointer. `module` may be
    // nullptr (equivalent to clearing the slot without retiring — not
    // normally used directly; prefer remove()). No bounds check — callers
    // (AudioEngine) validate `slot` before ever reaching here, since an
    // out-of-range slot means the command itself was malformed.
    DspModule* insert(int slot, DspModule* module) {
        DspModule* old = mSlots[slot];
        mSlots[slot] = module;
        return old;
    }

    // Clears `slot`, returning whatever was there (or nullptr).
    DspModule* remove(int slot) {
        return insert(slot, nullptr);
    }

    // Relocates the module at `fromSlot` into `toSlot`. If `toSlot` was
    // occupied, that module is simply overwritten — by design this is a
    // MOVE, not a swap; a caller wanting to swap two slots issues two
    // move() calls (or the UI layer composes it as remove+insert+insert,
    // which is equally valid since all of it goes through the same
    // ordered command queue and therefore applies atomically w.r.t. any
    // single audio callback boundary). Returns false (no-op) if fromSlot
    // has nothing in it, or if fromSlot == toSlot.
    bool move(int fromSlot, int toSlot) {
        if (fromSlot == toSlot) return false;
        DspModule* mover = mSlots[fromSlot];
        if (!mover) return false;
        DspModule* displaced = insert(toSlot, mover);
        mSlots[fromSlot] = nullptr;
        // A module displaced by a move (not a remove) still needs to be
        // retired — but move() has no return channel for it distinct from
        // its bool. In practice AudioEngine only issues move() between a
        // known-empty destination slot (the Kotlin UI reads slot state
        // before offering a drop target), so `displaced` is expected to be
        // nullptr; if it isn't, retiring it is intentionally NOT this
        // method's job — see AudioEngine::applyCommand()'s MoveModule case
        // for how a non-null displaced module is still safely retired.
        mLastDisplaced = displaced;
        return true;
    }

    // Consumes the module (if any) displaced by the most recent move().
    // AudioEngine::applyCommand() calls this immediately after move() to
    // decide whether something needs retiring — see move()'s doc comment.
    DspModule* takeLastDisplaced() {
        DspModule* d = mLastDisplaced;
        mLastDisplaced = nullptr;
        return d;
    }

    void setParameter(int slot, uint8_t paramId, float value) {
        if (mSlots[slot]) mSlots[slot]->setParameter(paramId, value);
    }

    DspModule* at(int slot) const { return mSlots[slot]; }

    DspModule* findFirstOfType(DspModuleType type) const {
        for (auto* module : mSlots) {
            if (module && module->type() == type) return module;
        }
        return nullptr;
    }

    // Engine-shutdown-only — see class doc comment. Deletes and clears
    // every occupied slot directly (not via the retire queue).
    void clear() {
        for (auto& module : mSlots) {
            delete module;
            module = nullptr;
        }
    }

private:
    std::array<DspModule*, kMaxSlots> mSlots{};
    DspModule* mLastDisplaced = nullptr;
};

} // namespace eliner
