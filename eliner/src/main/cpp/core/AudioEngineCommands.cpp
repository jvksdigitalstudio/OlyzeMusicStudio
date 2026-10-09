// AudioEngine — hilo de AUDIO: vaciado de la cola y aplicación de comandos.
//
// Es el ÚNICO lugar que muta el estado de audio (voces, FX, volumen master,
// transporte) en respuesta a órdenes del hilo de control, de modo que no hace
// falta ningún lock. Ver AudioEngine.cpp para el reparto de unidades.
//
// Esta unidad solo DECODIFICA y DELEGA: la lógica de voces vive en VoicePool y
// la del tiempo musical en TransportEngine.
#include "AudioEngine.h"
#include "Reverb.h" // applyParameter(): Reverb::Param
#include "Delay.h"  // applyParameter(): Delay::Param y límites de tiempo

namespace eliner {

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
        // ── MIDI: se delega en VoicePool, que posee el contrato de canal (§17):
        // valida el rango, roba voz y recuerda el bend por canal.
        // NoteOn: intA = note, intB = velocity (0-127), channel = canal MIDI (0-15).
        case EngineCommandType::NoteOn:
            mVoices.noteOn(cmd.channel, cmd.intA, cmd.intB);
            break;
        case EngineCommandType::NoteOff:
            mVoices.noteOff(cmd.channel, cmd.intA);
            break;
        case EngineCommandType::AllNotesOff:
            mVoices.allNotesOff();
            break;
        case EngineCommandType::PitchBend:
            mVoices.setPitchBend(cmd.channel, cmd.floatA);
            break;
        case EngineCommandType::SetParameter:
            applyParameter(cmd.paramId, cmd.floatA);
            break;

        // ── Fase 7: dynamic FX chain ────────────────────────────────────
        case EngineCommandType::InsertModule: {
            auto* incoming = static_cast<DspModule*>(cmd.ptrA);
            DspModule* old = mFxChain.insert(cmd.intA, incoming);
            applyDelayTempoSync(); // ADR 0028: un Delay nuevo nace al tempo vigente
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
            applyDelayTempoSync(); // ADR 0028: puede cambiar cuál es "el primer Delay"
            if (old && !mRetireQueue.push(old)) {
                raiseError(kErrorRetireQueueFull);
            }
            break;
        }
        case EngineCommandType::SetModuleParameter: {
            // ADR 0028: fijar a mano el TIEMPO de un Delay desactiva el sync al
            // tempo (último que escribe gana). Simplificación documentada: con
            // varios Delay en la cadena el sync es global.
            const DspModule* target =
                (cmd.intA >= 0 && cmd.intA < DspChain::kMaxSlots) ? mFxChain.at(cmd.intA) : nullptr;
            if (target && target->type() == DspModuleType::Delay &&
                cmd.intB == static_cast<int>(Delay::Param::Time)) {
                mTransport.setDelaySyncEnabled(false);
            }
            mFxChain.setParameter(cmd.intA, static_cast<uint8_t>(cmd.intB), cmd.floatA);
            break;
        }
        case EngineCommandType::MoveModule: {
            mFxChain.move(cmd.intA, cmd.intB);
            applyDelayTempoSync(); // ADR 0028: el orden de slots decide cuál es el primer Delay
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
            // ADR 0028: un tiempo fijado a mano desactiva el sync al tempo
            // (último que escribe gana; setDelayTempoSync(true) lo reactiva).
            mTransport.setDelaySyncEnabled(false);
            delay = mFxChain.findFirstOfType(DspModuleType::Delay);
            if (delay) delay->setParameter(Delay::Param::Time, value);
            break;
        case DspParameterId::DelayFeedback:
            delay = mFxChain.findFirstOfType(DspModuleType::Delay);
            if (delay) delay->setParameter(Delay::Param::Feedback, value);
            break;

        // ── Transport / tempo (ADR 0028) ────────────────────────────────
        case DspParameterId::Tempo:
            mTransport.setTempo(static_cast<double>(value));
            applyDelayTempoSync();
            break;
        case DspParameterId::TransportRunning:
            mTransport.setRunning(value >= 0.5f);
            break;
        case DspParameterId::BeatsPerBar:
            mTransport.setBeatsPerBar(static_cast<int>(value));
            break;
        case DspParameterId::MetronomeEnabled:
            mTransport.setMetronomeEnabled(value >= 0.5f);
            break;
        case DspParameterId::MetronomeVolume:
            mTransport.setMetronomeVolume(value);
            break;
        case DspParameterId::MetronomeSound:
            mTransport.setClickSound(static_cast<int>(value));
            break;
        case DspParameterId::MetronomeAccent:
            mTransport.setClickAccent(value >= 0.5f);
            break;
        case DspParameterId::MetronomeSubdivision:
            mTransport.setClickSubdivision(static_cast<int>(value));
            break;
        case DspParameterId::DelaySyncEnabled:
            mTransport.setDelaySyncEnabled(value >= 0.5f);
            applyDelayTempoSync();
            break;
        case DspParameterId::DelaySyncBeats:
            mTransport.setDelaySyncBeats(value);
            applyDelayTempoSync();
            break;
    }
}

void AudioEngine::applyDelayTempoSync() {
    // El TransportEngine sabe CUÁNTO debe durar el eco al tempo vigente; este
    // método sabe DÓNDE aplicarlo (el primer Delay de la cadena).
    if (!mTransport.delaySyncEnabled()) return;
    DspModule* delay = mFxChain.findFirstOfType(DspModuleType::Delay);
    if (!delay) return;
    const double seconds = mTransport.delaySyncSeconds(
        static_cast<double>(Delay::kMinTimeSeconds),
        static_cast<double>(Delay::kMaxTimeSeconds));
    delay->setParameter(Delay::Param::Time, static_cast<float>(seconds));
}

} // namespace eliner
