#pragma once
// Flags de error de tiempo real publicados por el hilo de audio.
//
// Se separa de AudioEngine.h: es un vocabulario (bitmask) compartido por el
// motor, los puentes JNI y la capa Kotlin (EngineErrorFlags.kt debe coincidir).

#include <cstdint>

namespace eliner {

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

} // namespace eliner
