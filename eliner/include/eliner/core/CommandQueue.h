#pragma once
#include <atomic>
#include <array>
#include <cstdint>

namespace eliner {

// ── Realtime Command Queue ──────────────────────────────────────────────────
// Single-Producer / Single-Consumer lock-free ring buffer.
//
// Producer : Control thread ONLY (JNI-facing methods on AudioEngine).
// Consumer : Audio thread ONLY (drained at the top of onAudioReady(),
//            before rendering, never blocking).
//
// Design constraints (see Fase 6 §3-6):
//   - No mutex, no allocation, no JNI calls on the consumer side.
//   - Fixed capacity, power-of-two sized ring buffer, POD payload only.
//   - push() never blocks: if the queue is full the command is dropped and
//     a counter is incremented (visible via droppedCount()). This favors
//     audio-thread determinism over losing an occasional command — a
//     dropped ParameterChange will simply be superseded by the next one;
//     a dropped NoteOn/NoteOff is a real risk with a capacity this large
//     under normal use (256 commands is far beyond one callback's worth
//     of MIDI/UI traffic), so overflow is only expected under pathological
//     conditions (e.g. MIDI flood) and is treated as best-effort delivery.
//   - Memory ordering: tail (producer-owned) published with release,
//     observed by consumer with acquire; head (consumer-owned) published
//     with release, observed by producer with acquire. This is the
//     standard SPSC ring buffer pattern and is safe with exactly one
//     producer thread and one consumer thread.
template <typename T, size_t Capacity>
class SpscCommandQueue {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    // Producer side (control thread). Never blocks. Returns false if full
    // (command dropped); caller may inspect droppedCount() for diagnostics.
    bool push(const T& item) {
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t nextTail = (tail + 1) & kMask;
        if (nextTail == mHead.load(std::memory_order_acquire)) {
            mDroppedCount.fetch_add(1, std::memory_order_relaxed);
            return false; // full
        }
        mBuffer[tail] = item;
        mTail.store(nextTail, std::memory_order_release);
        return true;
    }

    // Consumer side (audio thread). Never blocks. Returns false if empty.
    bool pop(T& out) {
        const size_t head = mHead.load(std::memory_order_relaxed);
        if (head == mTail.load(std::memory_order_acquire)) {
            return false; // empty
        }
        out = mBuffer[head];
        mHead.store((head + 1) & kMask, std::memory_order_release);
        return true;
    }

    // Diagnostics only — safe to call from either thread.
    uint64_t droppedCount() const { return mDroppedCount.load(std::memory_order_relaxed); }

private:
    static constexpr size_t kMask = Capacity - 1;

    std::array<T, Capacity> mBuffer{};
    std::atomic<size_t> mHead{0};
    std::atomic<size_t> mTail{0};
    std::atomic<uint64_t> mDroppedCount{0};
};

// ── Engine command payload ──────────────────────────────────────────────────
// Extensible tagged-union style command. Add new Types/fields as needed —
// keep it POD (no heap-owning members) so it can live in the ring buffer.
//
// Parameter changes (float-valued, single target) use ONE generic command
// type — SetParameter — carrying a DspParameterId rather than a dedicated
// EngineCommandType per parameter. This is the realtime-safe parameter
// system required by Fase 6 §6: adding a new float/bool/enum-as-int
// parameter later means adding one enum value here, not a new command
// type, a new queue case, and a new setter. MIDI events (NoteOn/NoteOff/
// AllNotesOff/PitchBend) keep their own command types because they carry
// different semantics (voice allocation, not a simple state write) and
// are not "parameters" in this sense.
// Fase 7 additions (InsertModule/RemoveModule/SetModuleParameter/MoveModule):
// the dynamic FX chain (see dsp/DspChain.h) — these mutate mFxChain instead
// of the fixed master-FX fields SetParameter already covers. Kept as
// separate command types rather than folded into SetParameter/DspParameterId
// because they carry different payloads (a slot index, sometimes a raw
// module pointer) that don't fit the "one float value" shape SetParameter
// was designed around.
enum class EngineCommandType : uint8_t {
    NoteOn,
    NoteOff,
    AllNotesOff,
    PitchBend,
    SetParameter,
    InsertModule,       // intA = slot, ptrA = already-constructed DspModule* (control-thread-allocated)
    RemoveModule,        // intA = slot
    SetModuleParameter,  // intA = slot, intB = module-local paramId, floatA = value
    MoveModule,           // intA = fromSlot, intB = toSlot
};

// Identifies WHICH parameter a SetParameter command targets. Grouped by
// owner (Master / Reverb / Delay) so future FX modules extend this enum
// without touching existing values (append-only for command-stream
// stability, though no persistence/serialization depends on it yet).
enum class DspParameterId : uint8_t {
    MasterVolume,   // 0.0 – 1.0
    MasterPan,      // -1.0 – 1.0
    ReverbMix,      // 0.0 – 1.0
    ReverbRoom,     // 0.0 – 1.0
    ReverbDamp,     // 0.0 – 1.0
    DelayMix,       // 0.0 – 1.0
    DelayTime,      // 0.01 – 2.0 (seconds)
    DelayFeedback,  // 0.0 – 0.95
};

struct EngineCommand {
    EngineCommandType type;
    int   intA   = 0;   // NoteOn/NoteOff: note. NoteOn: velocity carried in intB.
                         // InsertModule/RemoveModule/SetModuleParameter: slot.
                         // MoveModule: fromSlot.
    int   intB   = 0;   // NoteOn: velocity (0-127).
                         // SetModuleParameter: module-local paramId (uint8_t range).
                         // MoveModule: toSlot.
    float floatA = 0.0f; // PitchBend: semitones. SetParameter/SetModuleParameter: the value.
    DspParameterId paramId = DspParameterId::MasterVolume; // valid only when type == SetParameter
    void* ptrA   = nullptr; // InsertModule: the DspModule* to place at intA (control-thread-
                             // allocated — see AudioEngine::insertModule()). Unused otherwise;
                             // stays nullptr for every other command type, including the
                             // default-constructed EngineCommand the ring buffer is filled
                             // with (see CommandQueue's std::array<T, Capacity>{} init) — so
                             // an uninitialized/never-pushed slot never looks like a pending
                             // module transfer.
};

// Capacity: generous headroom above worst-case per-callback traffic
// (MIDI + UI parameter changes). Must be a power of two.
using EngineCommandQueue = SpscCommandQueue<EngineCommand, 256>;

} // namespace eliner
