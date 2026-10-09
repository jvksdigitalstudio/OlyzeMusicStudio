// AudioEngine — hilo de CONTROL: gestión de la cadena de FX dinámica (Fase 7).
//
// Alta/baja/movimiento de módulos, su espejo de control (mSlotTypesShadow) y la
// recogida de módulos retirados por el hilo de audio. Los cambios viajan por la
// misma cola de comandos; el hilo de audio los aplica en AudioEngineCommands.cpp.
// Ver AudioEngine.cpp para el reparto de unidades.
#include "AudioEngine.h"
#include "EngineLog.h"

namespace eliner {

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

// ── Fase 1.1 — Core Hardening, §16 ──────────────────────────────────────
// Bug real corregido aquí: esta función corría en el hilo de control y
// actualizaba mSlotTypesShadow tan pronto como pushCommand() aceptaba el
// comando en la SPSC — es decir, en cuanto quedó ENCOLADO, no en cuanto
// se EJECUTÓ. pushCommand()==true solo certifica que el comando llegó a
// la cola; el resultado real lo decide DspChain::move() más tarde, en el
// hilo de audio (ver AudioEngine::applyCommand(), caso MoveModule), que
// hace no-op (devuelve false) en dos casos: fromSlot == toSlot, y
// fromSlot vacío. En ambos, el shadow se actualizaba igual —
// "olvidando" el módulo en fromSlot (lo ponía en None) aunque el grafo
// nativo real seguía teniéndolo exactamente donde estaba. Consecuencia:
// getModuleType()/mSlotTypesShadow (lo único que la UI de Kotlin puede
// leer de forma síncrona) divergía silenciosamente del grafo DSP real
// hasta el próximo insert/remove que volviera a pisar ese slot.
//
// Esta clase no tiene un canal de confirmación audio→control (el único
// camino audio→control es la retire queue + error flags, ninguno de los
// dos apto para acks síncronos de comandos individuales sin violar
// realtime-safety en el lado de audio). La corrección no introduce uno:
// en vez de eso, replica en el hilo de control, ANTES de encolar, las
// mismas dos precondiciones de no-op que DspChain::move() evalúa en el
// hilo de audio (ver DspChain.h, move()). Esto es válido — no una
// suposición optimista — porque el invariante que sostiene todo este
// mecanismo de shadow es: "mSlotTypesShadow refleja el estado nativo
// confirmado hasta la última operación aceptada", y el canal de
// transporte es una SPSC de un único productor (AudioCommandDispatcher,
// ver ADR 0015 §"Validación real del modelo de productor único") con
// entrega FIFO estricta — por lo tanto, si el invariante se sostenía
// antes de esta llamada, evaluar las mismas condiciones sobre el shadow
// aquí predice con exactitud lo que DspChain::move() decidirá al
// consumir este mismo comando más tarde, sin necesidad de esperar esa
// ejecución.
bool AudioEngine::moveModule(int fromSlot, int toSlot) {
    if (fromSlot < 0 || fromSlot >= DspChain::kMaxSlots) return false;
    if (toSlot   < 0 || toSlot   >= DspChain::kMaxSlots) return false;

    // Precondición 1 — mirror de "if (fromSlot == toSlot) return false;"
    // en DspChain::move(). Sin este chequeo, el bloque de abajo movía
    // shadow[fromSlot] a None incondicionalmente aunque toSlot==fromSlot
    // dejara el módulo exactamente donde estaba en el grafo real.
    if (fromSlot == toSlot) return false;

    // Precondición 2 — mirror de "if (!mover) return false;" en
    // DspChain::move(). Si el shadow ya dice que fromSlot está vacío,
    // el grafo nativo también lo está (mismo invariante) — no hay nada
    // que mover, y encolar el comando solo arriesgaría pisar toSlot en
    // el shadow con None aunque el módulo real que ocupa toSlot en el
    // grafo nativo no se toque (DspChain::move() hace no-op completo en
    // este caso, ni siquiera llega a tocar toSlot).
    if (mSlotTypesShadow[fromSlot] == DspModuleType::None) return false;

    EngineCommand cmd;
    cmd.type = EngineCommandType::MoveModule;
    cmd.intA = fromSlot;
    cmd.intB = toSlot;
    if (!pushCommand(cmd)) return false;

    // Solo llegamos aquí si ambas precondiciones certifican que
    // DspChain::move() ejecutará la rama de éxito real (no el no-op) —
    // el shadow se actualiza para reflejar exactamente esa rama.
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

} // namespace eliner
