// Fase 1.1 — Core Hardening, §16 (DspChain.moveModule() / mSlotTypesShadow).
//
// Verifica, de forma EJECUTABLE, que el shadow de control-thread
// (mSlotTypesShadow) permanece consistente con el grafo DSP real del
// hilo de audio (DspChain::mSlots) para las 5 rutas de moveModule()
// identificadas en la auditoría de esta fase:
//   1. move válido                    -> shadow y grafo coinciden
//   2. same-slot (fromSlot == toSlot)  -> ambos no-op, nada cambia
//   3. empty source                    -> ambos no-op, nada cambia
//   4. move hacia slot ocupado         -> destino sobreescrito en ambos
//   5. secuencia de moves repetidos    -> consistencia se mantiene en cada paso
//
// Réplica exacta de la lógica real de dos archivos:
//   - DspChain::move()/insert()/remove() (eliner/include/eliner/dsp/DspChain.h)
//   - AudioEngine::moveModule() CORREGIDO (eliner/src/main/cpp/core/AudioEngine.cpp)
// No se reimplementa DspModule (no hace falta: solo importa el tipo por
// slot, que es justamente lo que el shadow rastrea), así que los slots
// se modelan como `int` (0 = vacío, >0 = un "tipo" de módulo), que es
// isomorfo a DspModuleType para efectos de esta prueba.
//
// Compilación (misma que el resto de tests standalone de esta fase, ver
// docs/adr/0015-fase1-cierre-verificacion.md, sección "Testing C++ / Build"):
//   g++ -std=c++20 -Wall -Wextra -Wpedantic -fsanitize=address,undefined
//   test_dsp_chain_move_shadow_consistency.cpp -o /tmp/t && /tmp/t
#include <array>
#include <cstdio>
#include <cstdint>

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
}

constexpr int kMaxSlots = 8;
constexpr int kNone = 0;

// ── Réplica exacta de DspChain (solo la parte relevante: slots + move) ──
struct DspChainModel {
    std::array<int, kMaxSlots> mSlots{};       // 0 == vacío (DspModuleType::None)
    int mLastDisplaced = kNone;

    int insert(int slot, int module) {
        int old = mSlots[slot];
        mSlots[slot] = module;
        return old;
    }
    int remove(int slot) { return insert(slot, kNone); }

    bool move(int fromSlot, int toSlot) {
        if (fromSlot == toSlot) return false;
        int mover = mSlots[fromSlot];
        if (mover == kNone) return false;
        int displaced = insert(toSlot, mover);
        mSlots[fromSlot] = kNone;
        mLastDisplaced = displaced;
        return true;
    }
};

// ── Réplica exacta de AudioEngine tras el fix de esta fase ──────────────
// (pushCommand() modelado como "siempre acepta" — el caso de cola llena
// ya está cubierto por otros tests de esta fase, aquí el foco es
// exclusivamente la relación shadow<->grafo real cuando SÍ se encola).
struct EngineModel {
    DspChainModel mChain;                       // "hilo de audio" — grafo real
    std::array<int, kMaxSlots> mSlotTypesShadow{}; // "hilo de control" — lo que Kotlin puede leer

    void insertModule(int slot, int type) {
        // insert() nunca falla en DspChain real (ver DspChain.h) — por
        // eso insertModule() SÍ puede actualizar el shadow incondicional-
        // mente en cuanto pushCommand() acepta, sin la corrección de
        // precondiciones que sí necesita moveModule().
        mSlotTypesShadow[slot] = type;
        applyOnAudioThread_Insert(slot, type);
    }

    // moveModule() — lógica CORREGIDA bajo prueba (mirror exacto del
    // AudioEngine.cpp real tras el fix de esta fase).
    bool moveModule(int fromSlot, int toSlot) {
        if (fromSlot == toSlot) return false;
        if (mSlotTypesShadow[fromSlot] == kNone) return false;

        // pushCommand() asumido exitoso (cola no llena) — el comando
        // queda encolado para el hilo de audio.
        bool pushed = true;
        if (!pushed) return false;

        int moved = mSlotTypesShadow[fromSlot];
        mSlotTypesShadow[toSlot]   = moved;
        mSlotTypesShadow[fromSlot] = kNone;

        // Simula el consumo INMEDIATO del comando por el hilo de audio
        // (en producción es asíncrono, pero la propiedad de invariante
        // bajo prueba —consistencia tras la ejecución— es la misma
        // independientemente de cuándo se drene, dado el orden FIFO de
        // un único productor ya validado en ADR 0015).
        mChain.move(fromSlot, toSlot);
        return true;
    }

    void applyOnAudioThread_Insert(int slot, int type) {
        mChain.insert(slot, type);
    }

    bool shadowMatchesRealGraph() const {
        return mSlotTypesShadow == mChain.mSlots;
    }
};

int main() {
    std::fprintf(stdout, "=== test_dsp_chain_move_shadow_consistency ===\n");

    // ── Caso 1: move válido ──────────────────────────────────────────
    {
        EngineModel e;
        e.insertModule(0, /*Reverb=*/1);
        bool ok = e.moveModule(0, 2);
        check(ok, "Caso 1: move válido retorna true");
        check(e.mSlotTypesShadow[2] == 1 && e.mSlotTypesShadow[0] == kNone,
              "Caso 1: shadow refleja el módulo en el nuevo slot");
        check(e.shadowMatchesRealGraph(),
              "Caso 1: shadow == grafo real tras move válido");
    }

    // ── Caso 2: same-slot (el bug real corregido en esta fase) ──────────
    {
        EngineModel e;
        e.insertModule(3, /*Delay=*/2);
        int shadowBefore = e.mSlotTypesShadow[3];
        bool ok = e.moveModule(3, 3);
        check(!ok, "Caso 2: moveModule(slot, slot) retorna false (no encola)");
        check(e.mSlotTypesShadow[3] == shadowBefore,
              "Caso 2 [BUG PRE-FIX]: shadow[3] NO se pierde en same-slot move");
        check(e.shadowMatchesRealGraph(),
              "Caso 2: shadow == grafo real (ambos intactos)");
    }

    // ── Caso 3: empty source ─────────────────────────────────────────
    {
        EngineModel e;
        e.insertModule(5, /*Reverb=*/1); // ocupa el DESTINO, no el origen
        bool ok = e.moveModule(1 /*vacío*/, 5 /*ocupado*/);
        check(!ok, "Caso 3: moveModule(vacío, ocupado) retorna false (no encola)");
        check(e.mSlotTypesShadow[5] == 1,
              "Caso 3 [BUG PRE-FIX]: shadow[toSlot] NO se pisa con None "
              "cuando fromSlot estaba vacío");
        check(e.shadowMatchesRealGraph(),
              "Caso 3: shadow == grafo real (destino real intacto, no tocado por move())");
    }

    // ── Caso 4: move hacia slot ocupado (overwrite, no swap — by design) ──
    {
        EngineModel e;
        e.insertModule(0, /*Reverb=*/1);
        e.insertModule(1, /*Delay=*/2);
        bool ok = e.moveModule(0, 1);
        check(ok, "Caso 4: move hacia slot ocupado retorna true (overwrite)");
        check(e.mSlotTypesShadow[1] == 1 && e.mSlotTypesShadow[0] == kNone,
              "Caso 4: shadow refleja el overwrite (Delay desplazado, Reverb en slot 1)");
        check(e.shadowMatchesRealGraph(),
              "Caso 4: shadow == grafo real tras overwrite");
    }

    // ── Caso 5: secuencia repetida de moves — consistencia en cada paso ──
    {
        EngineModel e;
        e.insertModule(0, /*Reverb=*/1);
        bool step1 = e.moveModule(0, 1);
        bool step1consistent = e.shadowMatchesRealGraph();
        bool step2 = e.moveModule(1, 2);
        bool step2consistent = e.shadowMatchesRealGraph();
        bool step3_noop = e.moveModule(0, 3); // slot 0 ahora vacío tras los moves previos
        bool step3consistent = e.shadowMatchesRealGraph();
        check(step1 && step1consistent, "Caso 5.1: move 0->1 consistente");
        check(step2 && step2consistent, "Caso 5.2: move 1->2 consistente");
        check(!step3_noop && step3consistent,
              "Caso 5.3: move desde slot ya vacío por moves previos "
              "rechazado correctamente, shadow sigue consistente");
        check(e.mSlotTypesShadow[2] == 1, "Caso 5: módulo terminó en slot 2 tras 2 moves reales");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n",
                 gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
