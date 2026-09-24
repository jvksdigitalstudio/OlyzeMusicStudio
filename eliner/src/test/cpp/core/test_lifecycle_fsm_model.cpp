// Sección 8 del prompt de cierre — verifica, de forma EJECUTABLE, la
// lógica exacta de `LifecycleState`/`startLocked()`/`stopLocked()` de
// `AudioEngine.h`/`.cpp` — replicada aquí (mismo enum, misma decisión de
// "no sobrescribir Failed"), porque el AudioEngine real depende de Oboe/
// NDK, no disponibles en este entorno. Esto no es una aproximación floja:
// es exactamente la misma máquina de estados, con las mismas transiciones,
// solo sin las líneas que tocan oboe::AudioStream.
#include <cstdio>
#include <cstdint>

enum class LifecycleState : uint8_t { Stopped, Starting, Running, Stopping, Recovering, Failed };

const char* name(LifecycleState s) {
    switch (s) {
        case LifecycleState::Stopped: return "Stopped";
        case LifecycleState::Starting: return "Starting";
        case LifecycleState::Running: return "Running";
        case LifecycleState::Stopping: return "Stopping";
        case LifecycleState::Recovering: return "Recovering";
        case LifecycleState::Failed: return "Failed";
    }
    return "?";
}

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
}

/** Réplica exacta de la lógica de transición real (AudioEngine.cpp). */
struct EngineModel {
    LifecycleState state = LifecycleState::Stopped;
    bool requestStartWillSucceed = true; // controla el resultado simulado

    void startLocked() {
        state = LifecycleState::Starting;
        if (!requestStartWillSucceed) {
            state = LifecycleState::Failed;
            stopLocked(); // mismo orden que AudioEngine::startLocked() real
            return;
        }
        state = LifecycleState::Running;
    }

    void stopLocked() {
        auto current = state; // capturado ANTES de cualquier mutación — igual que el .cpp real
        if (current == LifecycleState::Running || current == LifecycleState::Recovering) {
            state = LifecycleState::Stopping;
        }
        // ... (cleanup real omitido — no cambia el estado) ...
        if (current != LifecycleState::Failed) {
            state = LifecycleState::Stopped;
        }
        // si current == Failed, el estado queda en Failed — la decisión bajo prueba
    }

    void reopenStream() {
        state = LifecycleState::Recovering;
        stopLocked();
        startLocked();
    }
};

int main() {
    std::fprintf(stdout, "== test_lifecycle_fsm_model ==\n");

    // ── Caso 1: arranque exitoso ──
    {
        EngineModel e;
        e.requestStartWillSucceed = true;
        e.startLocked();
        check(e.state == LifecycleState::Running, "start() exitoso termina en Running");
    }

    // ── Caso 2: arranque fallido — FAILED debe PERSISTIR, no quedar en Stopped ──
    {
        EngineModel e;
        e.requestStartWillSucceed = false;
        e.startLocked();
        check(e.state == LifecycleState::Failed,
              "start() fallido deja el estado observable en Failed (persistente) tras el cleanup interno — NO en Stopped, que lo haría indistinguible de un stop() intencional");
    }

    // ── Caso 3: la ÚNICA salida de Failed es un start() explícito ──
    {
        EngineModel e;
        e.requestStartWillSucceed = false;
        e.startLocked();
        check(e.state == LifecycleState::Failed, "precondición: en Failed");
        e.requestStartWillSucceed = true;
        e.startLocked();
        check(e.state == LifecycleState::Running, "un nuevo start() explícito saca a Running, sin importar que el estado previo fuera Failed (persistente)");
    }

    // ── Caso 4: stop() normal desde Running SÍ termina en Stopped (no en Failed) ──
    {
        EngineModel e;
        e.requestStartWillSucceed = true;
        e.startLocked();
        e.stopLocked();
        check(e.state == LifecycleState::Stopped, "stop() normal desde Running termina en Stopped — la persistencia de Failed no afecta este camino");
    }

    // ── Caso 5: reopenStream() con éxito — pasa por Recovering, termina en Running ──
    {
        EngineModel e;
        e.requestStartWillSucceed = true;
        e.startLocked();
        e.reopenStream();
        check(e.state == LifecycleState::Running, "reopenStream() exitoso termina en Running, pasando por Recovering internamente");
    }

    // ── Caso 6: reopenStream() con fallo — termina en Failed persistente, no en Stopped ──
    {
        EngineModel e;
        e.requestStartWillSucceed = true;
        e.startLocked();
        e.requestStartWillSucceed = false; // el reintento de reopenStream() fallará
        e.reopenStream();
        check(e.state == LifecycleState::Failed,
              "reopenStream() fallido también deja el estado en Failed persistente — mismo camino que un start() fallido normal, consistente");
    }

    // ── Caso 7: llamar stopLocked() dos veces seguidas (segundo stop() redundante) no rompe nada ──
    {
        EngineModel e;
        e.requestStartWillSucceed = true;
        e.startLocked();
        e.stopLocked();
        e.stopLocked(); // redundante — mismo patrón que el destructor llamando stop() tras un stop() explícito previo
        check(e.state == LifecycleState::Stopped, "un segundo stopLocked() redundante deja el estado en Stopped, sin efectos extraños");
    }

    std::fprintf(stdout, "\n== %s (%d fallos) ==\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
