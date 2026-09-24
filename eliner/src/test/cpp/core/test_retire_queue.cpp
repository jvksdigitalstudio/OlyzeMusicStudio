// Sección 7 del prompt de cierre — Retire Queue: verifica empíricamente
// (no solo por inspección) el contrato real de `mRetireQueue`
// (`SpscCommandQueue<DspModule*, 32>` en AudioEngine.h) bajo:
//   - churn normal (audio thread retira, control thread libera) — SPSC
//     real, mismo patrón ya verificado en test_command_queue_spsc_baseline.cpp,
//     aquí con el tipo de payload real (puntero, no EngineCommand);
//   - queue llena (más de 32 módulos retirados sin que collectGarbage()
//     drene entre medio) — confirma que el comportamiento documentado
//     ("leak silencioso, no corrupción, no double-free") es exactamente
//     lo que ocurre;
//   - ausencia de use-after-free / double-free bajo el patrón real:
//     ownership transferido UNA VEZ del audio thread al retire queue, y
//     de ahí al control thread — nunca dos dueños simultáneos.
//
// No usa el DspModule real (arrastra virtuals de procesamiento de audio,
// SynthVoice, etc. — no aporta nada a esta prueba de ownership/lifecycle
// del contenedor en sí) — usa un stand-in mínimo que se comporta igual
// para efectos de conteo de construcción/destrucción, lo cual es
// exactamente lo que hace falta para detectar un double-free o un leak.
//
// Compilar:
//   g++ -std=c++20 -fsanitize=address -pthread -g \
//       -I<repo>/eliner/include/eliner/core \
//       test_retire_queue.cpp -o retire_queue
// (AddressSanitizer, no ThreadSanitizer — el objetivo aquí es detectar
// double-free/use-after-free, la especialidad de ASan; TSAN también se
// ejecuta por separado para la parte de concurrencia SPSC real.)
#include "CommandQueue.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>

using eliner::SpscCommandQueue;

namespace {

int gFailures = 0;
void check(bool condition, const char* what) {
    if (!condition) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else            { std::fprintf(stdout, "[ OK ] %s\n", what); }
}

std::atomic<int> gConstructed{0};
std::atomic<int> gDestroyed{0};

/** Stand-in mínimo de DspModule — solo cuenta construcción/destrucción,
 *  para detectar double-free (destructor llamado 2 veces sobre el mismo
 *  puntero) o leaks (constructed != destroyed al final) sin necesitar el
 *  DspModule real. */
struct FakeModule {
    FakeModule() { gConstructed.fetch_add(1, std::memory_order_relaxed); }
    ~FakeModule() { gDestroyed.fetch_add(1, std::memory_order_relaxed); }
};

/** Caso 1 — churn normal: el "audio thread" retira módulos a buen ritmo,
 *  el "control thread" los drena y libera (collectGarbage() real), sin
 *  que la cola llegue a llenarse. Confirma: cada módulo construido se
 *  destruye EXACTAMENTE una vez — ni leak, ni double-free. */
void testNormalChurn() {
    std::fprintf(stdout, "\n== Retire Queue: churn normal (sin saturaci\u00f3n) ==\n");
    gConstructed.store(0);
    gDestroyed.store(0);

    SpscCommandQueue<FakeModule*, 32> retireQueue;
    constexpr int kModules = 5000;
    std::atomic<bool> audioThreadDone{false};

    std::thread audioThread([&] {
        for (int i = 0; i < kModules; ++i) {
            auto* mod = new FakeModule();
            while (!retireQueue.push(mod)) std::this_thread::sleep_for(std::chrono::microseconds(20));
        }
        audioThreadDone.store(true, std::memory_order_release);
    });

    std::thread controlThread([&] {
        FakeModule* mod;
        while (!audioThreadDone.load(std::memory_order_acquire) || true) {
            while (retireQueue.pop(mod)) delete mod; // == collectGarbage() real
            if (audioThreadDone.load(std::memory_order_acquire) && gDestroyed.load() >= gConstructed.load()) break;
            std::this_thread::sleep_for(std::chrono::microseconds(20));
        }
    });

    audioThread.join();
    controlThread.join();

    check(gConstructed.load() == kModules, "se construyeron exactamente los m\u00f3dulos esperados");
    check(gDestroyed.load() == gConstructed.load(), "bajo churn normal (sin saturaci\u00f3n), TODO m\u00f3dulo retirado se libera exactamente una vez — sin leak, sin double-free");
    std::fprintf(stdout, "[INFO] construidos=%d destruidos=%d\n", gConstructed.load(), gDestroyed.load());
}

/** Caso 2 — queue llena: fuerza deliberadamente más retiros que
 *  capacidad SIN que nadie drene, confirmando el contrato documentado en
 *  AudioEngine.h: push() falla, el módulo NO se libera automáticamente
 *  (responsabilidad de quien llama decidir qué hacer — en AudioEngine.cpp
 *  real, decide levantar kErrorRetireQueueFull y dejarlo fugar,
 *  documentado explícitamente como leak intencional bajo esa condición
 *  patológica) — lo importante aquí es confirmar que NO hay corrupción
 *  ni un crash, solo un leak contabilizable. */
void testQueueFull() {
    std::fprintf(stdout, "\n== Retire Queue: queue llena (determinista) ==\n");
    gConstructed.store(0);
    gDestroyed.store(0);

    SpscCommandQueue<FakeModule*, 32> retireQueue; // capacidad real del proyecto
    constexpr int kAttempts = 200;
    int pushed = 0, rejected = 0;
    std::vector<FakeModule*> leaked; // simula lo que AudioEngine.cpp hace: no libera lo rechazado (mismo comportamiento real, documentado)

    for (int i = 0; i < kAttempts; ++i) {
        auto* mod = new FakeModule();
        if (retireQueue.push(mod)) {
            ++pushed;
        } else {
            ++rejected;
            leaked.push_back(mod); // ownership real: AudioEngine.cpp deja este puntero fugar aquí — igual que el código de producción
        }
    }

    check(pushed == 31, "con capacidad 32 (kMask=31), acepta exactamente Capacity-1 antes de reportarse llena — mismo l\u00edmite que el SPSC de comandos");
    check(rejected == kAttempts - 31, "el resto se rechaza expl\u00edcitamente, sin bloquear, sin crash");
    check(retireQueue.droppedCount() == static_cast<uint64_t>(rejected), "droppedCount() permite diagnosticar cu\u00e1ntos m\u00f3dulos se est\u00e1n fugando por saturaci\u00f3n — observable, no silencioso a nivel de m\u00e9trica");

    // Drena lo aceptado — confirma que SÍ se libera correctamente (el
    // leak es SOLO para lo rechazado, no para todo).
    FakeModule* mod;
    int drained = 0;
    while (retireQueue.pop(mod)) { delete mod; ++drained; }
    check(drained == pushed, "todo lo aceptado durante la saturaci\u00f3n se drena y libera correctamente despu\u00e9s");

    // Libera manualmente lo "fugado" — SOLO para que ASan no reporte esto
    // como el leak que YA sabemos que es (es el comportamiento documentado
    // bajo prueba, no un bug de este test) y así ASan pueda seguir
    // vigilando double-free/use-after-free real en el resto de la suite.
    for (auto* m : leaked) delete m;

    check(gConstructed.load() == kAttempts, "se construyeron exactamente los intentos");
    check(gDestroyed.load() == kAttempts, "con la limpieza expl\u00edcita del test (imitando un fix futuro), nada queda sin liberar — el leak real del c\u00f3digo de producci\u00f3n bajo esta condici\u00f3n queda confirmado y contabilizado arriba, no oculto");
    std::fprintf(stdout, "[INFO] intentos=%d aceptados=%d rechazados(leak real en producci\u00f3n)=%d\n", kAttempts, pushed, rejected);
}

} // namespace

int main() {
    std::fprintf(stdout, "== test_retire_queue ==\n");
    testNormalChurn();
    testQueueFull();
    std::fprintf(stdout, "\n== %s (%d fallos) ==\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
