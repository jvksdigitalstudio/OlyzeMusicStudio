#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <array>
#include "EngineConstants.h"
#include "LifecycleState.h"
#include "EngineErrorFlags.h"
#include "CommandQueue.h"
#include "DspChain.h"
#include "DspModuleFactory.h"
#include "VoicePool.h"
#include "TransportEngine.h"

namespace eliner {


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

    /** Estado formal del lifecycle (Objetivo C) — más granular que
     *  [isRunning]; ver el enum [LifecycleState] arriba para las
     *  transiciones válidas. Lectura atómica, segura desde cualquier
     *  hilo de control. */
    LifecycleState getLifecycleState() const { return mLifecycleState.load(std::memory_order_acquire); }

    // MIDI events (called from Kotlin/JVM via JNI bridge)
    void  noteOn (int channel, int note, int velocity);
    void  noteOff(int channel, int note);
    void  allNotesOff();
    void  sendCC (int channel, int cc, int value);
    void  setPitchBend(int channel, float semitones);

    // Master controls
    void  setMasterVolume(float vol);   // 0.0 – 1.0
    void  setMasterPan(float pan);      // -1.0 – 1.0

    // ── Transport / tempo (ADR 0028) ─────────────────────────────────────
    // Todos son seguros desde el hilo de control (no bloquean, no asignan):
    // validan la entrada y la envían al hilo de audio por la cola de
    // comandos. Los valores no finitos se ignoran; los fuera de rango se
    // limitan. El hilo de audio es el único dueño del reloj y del metrónomo.
    //
    // setTempo: 20–300 BPM. Cambiar el tempo con el transporte en marcha
    // conserva la fase musical (sin pulsos repetidos ni omitidos) y, si el
    // sync del delay está activo, reajusta el tiempo del delay.
    void  setTempo(float bpm);
    // Arranca/detiene el transporte. Arrancar posiciona el primer tiempo en
    // el primer frame del siguiente bloque; un arranque repetido no reinicia.
    void  setTransportRunning(bool running);
    void  setBeatsPerBar(int beats);          // 1–16
    void  setMetronomeEnabled(bool enabled);
    void  setMetronomeVolume(float volume);
    // Click del metrónomo (ADR 0031): sonido (ClickSound), acento del primer
    // tiempo y clics por pulso (ClickSubdivision, 1–4). Valores inválidos se ignoran.
    void  setMetronomeSound(int sound);
    void  setMetronomeAccent(bool enabled);
    void  setMetronomeSubdivision(int perBeat);   // 0.0 – 1.0
    // Con sync activo, el tiempo del primer Delay de la cadena es
    // `beats` pulsos al tempo actual (plegado por octavas al rango del
    // delay). Fijar DelayTime a mano lo desactiva. Activo por defecto con
    // 0.75 pulsos (corchea con puntillo = 0.375 s a 120 BPM, el tiempo por
    // defecto histórico del Delay).
    void  setDelayTempoSync(bool enabled, float beats);

    // Posición de pulso para la UI (ADR 0029). Instantánea empaquetada según
    // eliner/transport/BeatPulse.h; la escribe el hilo de audio y se puede
    // leer desde CUALQUIER hilo sin bloquear (un solo atomic, lectura acquire).
    // Es el instante en que el bloque de audio fue RENDERIZADO: el sonido sale
    // por el altavoz con la latencia de salida del dispositivo (decenas de ms)
    // después, así que un indicador visual adelanta ese tiempo al oído.
    std::uint64_t pulseSnapshot() const { return mTransport.pulseSnapshot(); }

    // FX chain — legacy fixed-target API (pre-Fase-7). Kept unchanged as a
    // stable entry point (same principle as Fase 6 §16 / setTempo()):
    // these still work exactly as before, now internally routed through
    // mFxChain by module type (see applyParameter() in the .cpp) rather
    // than directly against dedicated mReverb/mDelay fields. If the
    // targeted module has been removed from the chain via removeModule()
    // (see below), the call is a safe no-op — there is nothing to apply
    // the parameter to.
    void  setReverbMix(float mix);      // 0.0 – 1.0
    // Fase 1.1 §22 (ADR 0023): ReverbRoom/ReverbDamp ya tenían manejo
    // completo en applyCommand() (case DspParameterId::ReverbRoom/ReverbDamp,
    // ver abajo) pero NUNCA existió un setter público que los alcanzara —
    // dos ramas de código nativo, correctas y sin bug, que nada podía
    // ejecutar jamás. Encontrado auditando el catálogo completo de
    // DspParameterId contra su superficie pública realmente alcanzable
    // (exactamente el propósito del catálogo de parámetros de esta fase).
    void  setReverbRoom(float room);    // 0.0 – 1.0
    void  setReverbDamp(float damp);    // 0.0 – 1.0
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
    // ── Fase 1 de estabilización — Objetivo D/E ──────────────────────────
    // start()/stop() (públicos, arriba) son wrappers finos: toman
    // mLifecycleMutex UNA vez y delegan aquí. reopenStream() (más abajo)
    // también corre bajo el lock que ÉL MISMO toma, y llama a estas
    // versiones "Locked" directamente — NUNCA a start()/stop() públicos
    // desde dentro de una región ya bloqueada, porque std::mutex no es
    // reentrante (relockear desde el mismo hilo sería un deadlock real,
    // no hipotético: start() necesita poder invocar limpieza equivalente
    // a stop() en su propio path de fallo — ver el fix del Objetivo F).
    bool  startLocked(int performanceProfile);
    void  stopLocked();

    // Audio-thread-only. Drains mCommandQueue (bounded: at most Capacity
    // items, so this loop always terminates deterministically) and applies
    // each command directly to voice/FX state. This is the ONLY place
    // that mutates mVoices / mMasterVolume / mMasterPan / FX parameters —
    // control thread never touches them directly, so no lock is needed.
    void  processCommands();
    void  applyCommand(const EngineCommand& cmd);
    void  renderAudio(float* outputBuffer, int numFrames);
    bool  reopenStream();

    // Control-thread-only. Builds mVoices/mFxChain against the
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

    // ── Fase 1 de estabilización — Objetivo D/E ──────────────────────────
    // Serializa start()/stop()/reopenStream() entre sí. Sin esto, dos
    // hilos de CONTROL reales y distintos podían mutar mStream/mFxChain
    // del mismo objeto AudioEngine simultáneamente sin ninguna
    // coordinación:
    //   - el hilo que invoca JNI (protegido, del lado del puntero gEngine,
    //     por el mutex de EliNerAudioBridge.cpp — pero eso protege el
    //     ACCESO AL PUNTERO, no las mutaciones internas de ESTE objeto
    //     una vez obtenida una referencia válida);
    //   - el hilo interno de Oboe que invoca onErrorAfterClose() ->
    //     reopenStream() -> stop()+start() — un hilo separado que NUNCA
    //     pasa por JNI ni por el mutex de gEngine, documentado como tal
    //     arriba en el propio coment ario de raiseError().
    // Ninguno de los tres métodos protegidos aquí se llama jamás desde
    // onAudioReady() (el callback de audio realtime) — así que este mutex
    // nunca compite con el hilo de audio, y por lo tanto no viola
    // realtime-safety (¡A.2 del prompt de esta fase: "El audio callback
    // NO debe adquirir mutexes bloqueantes" — este no es ese callback).
    std::mutex mLifecycleMutex;
    std::atomic<LifecycleState> mLifecycleState{LifecycleState::Stopped};

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

    // Polifonía: reparto de voces, robo y pitch bend por canal (ver dsp/VoicePool.h).
    // Audio-thread-owned; se construye en buildDspGraph() con la frecuencia real.
    VoicePool mVoices;

    // FX — same lifecycle as mVoices.

    // ── Dynamic FX chain (Fase 7) ────────────────────────────────────────
    // Audio-thread-owned (see DspChain.h). Default-populated in
    // buildDspGraph() with Reverb@slot0 + Delay@slot1 — same signal path
    // as before this phase — every other slot starts empty.
    DspChain mFxChain;

    // ── Transport / tempo (ADR 0028, 0029) — audio-thread-owned ──────────
    // Reloj + metrónomo + publicación de pulso + estado del sync del delay, en
    // una sola pieza (ver transport/TransportEngine.h). Cambios solo vía
    // comandos (applyParameter) o buildDspGraph() (antes de mDspReady).
    TransportEngine mTransport;
    // Recalcula el tiempo del primer Delay de mFxChain a partir del tempo y
    // del TransportEngine. No hace nada si el sync está desactivado o no hay
    // Delay en la cadena. Hilo de audio (o buildDspGraph antes de mDspReady).
    void applyDelayTempoSync();

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
