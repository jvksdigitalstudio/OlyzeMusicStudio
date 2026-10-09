// Fase 1.1 §19 — protocolo de cierre de ThreadManager.kt (services/), réplica
// ejecutable de su ESTRUCTURA de sincronización (no de las corrutinas).
//
// Qué se modela y por qué es fiel:
//  - "lanes": 5 cancelaciones de scope + 3 shutdown de executors, en ese orden
//    (ThreadManager.shutdown()). Cada paso se ensancha con un sleep para hacer
//    visible la ventana de carrera — en producción cada paso dura microsegundos,
//    pero el defecto es de ORDEN, no de duración.
//  - OldProtocol: el lock cubría solo el cambio del flag; la cancelación corría
//    fuera del lock (código anterior a esta fase).
//  - NewProtocol: el lock cubre TODA la secuencia; el flag es atómico (en Kotlin,
//    @Volatile) para lecturas sin lock en scopeFor().
//
// Propiedad bajo prueba (postcondición documentada en ThreadManager.shutdown()):
//   "al retornar CUALQUIER llamada a shutdown(), todo está cancelado".
//
// Lo que este test NO puede demostrar: la parte de VISIBILIDAD del modelo de
// memoria de Java (el `var` plano leído sin lock). Eso se razona en ADR 0022;
// aquí solo se verifica lo que sí es ejecutable: la postcondición y la ausencia
// de data races del protocolo nuevo bajo ThreadSanitizer.
//
//   g++ -std=c++20 -Wall -Wextra -Wpedantic -pthread -fsanitize=thread
//       test_thread_manager_shutdown_protocol_model.cpp -o /tmp/t && /tmp/t
#include <atomic>
#include <chrono>
#include <cstdio>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace {
int gFailures = 0;
void check(bool c, const char* w) {
    if (!c) { std::fprintf(stderr, "[FAIL] %s\n", w); ++gFailures; }
    else    { std::fprintf(stdout, "[ OK ] %s\n", w); }
}
using namespace std::chrono_literals;
}

struct Lanes {
    std::atomic<int> cancelled{0};       // scopes cancelados (de 5)
    std::atomic<int> executorsClosed{0}; // executors cerrados (de 3)
    std::chrono::milliseconds step;
    explicit Lanes(std::chrono::milliseconds s) : step(s) {}
    void cancelAndClose() {
        for (int i = 0; i < 5; ++i) { std::this_thread::sleep_for(step); ++cancelled; }
        for (int i = 0; i < 3; ++i) { std::this_thread::sleep_for(step); ++executorsClosed; }
    }
    bool allDone() const { return cancelled.load() == 5 && executorsClosed.load() == 3; }
};

struct OldProtocol {
    std::mutex lock; bool shutDown = false; Lanes lanes;
    std::function<void()> afterFlagSet;
    explicit OldProtocol(std::chrono::milliseconds s) : lanes(s) {}
    void shutdown() {
        { std::lock_guard<std::mutex> g(lock); if (shutDown) return; shutDown = true; }
        if (afterFlagSet) afterFlagSet();
        lanes.cancelAndClose();               // FUERA del lock
    }
};

struct NewProtocol {
    std::mutex lifecycleLock; std::atomic<bool> shutDown{false}; Lanes lanes;
    std::function<void()> afterFlagSet;
    explicit NewProtocol(std::chrono::milliseconds s) : lanes(s) {}
    void shutdown() {
        std::lock_guard<std::mutex> g(lifecycleLock);
        if (shutDown.load()) return;
        shutDown.store(true);
        if (afterFlagSet) afterFlagSet();
        lanes.cancelAndClose();               // DENTRO del lock
    }
    bool scopeForWouldThrow() const { return shutDown.load(); } // lectura sin lock
};

template <class P>
bool secondCallerSeesCompletedShutdown() {
    P p(5ms);
    std::atomic<bool> flagSet{false};
    p.afterFlagSet = [&] { flagSet.store(true); };
    std::thread a([&] { p.shutdown(); });
    while (!flagSet.load()) std::this_thread::sleep_for(1ms); // A ya está dentro de la secuencia de cierre
    p.shutdown();                                              // B: segundo llamador concurrente
    bool complete = p.lanes.allDone();                         // postcondición al retornar B
    a.join();
    return complete;
}

int main() {
    std::fprintf(stdout, "=== test_thread_manager_shutdown_protocol_model ===\n");

    // 1. El protocolo ANTIGUO viola la postcondición (defecto confirmado).
    check(!secondCallerSeesCompletedShutdown<OldProtocol>(),
          "[DEFECTO CONFIRMADO] protocolo antiguo: un 2º shutdown() concurrente retorna ANTES de que el 1º termine de cancelar");

    // 2. El protocolo NUEVO la cumple.
    check(secondCallerSeesCompletedShutdown<NewProtocol>(),
          "protocolo nuevo: al retornar el 2º shutdown(), todo está cancelado y los executors cerrados");

    // 3. Estrés: 8 hilos llaman shutdown() a la vez, 300 rondas; cada llamador comprueba la
    //    postcondición al retornar; otro hilo lee el flag sin lock (scopeFor) a la vez.
    {
        int violations = 0, roundsWithReaderSeeingClosedAfterReturn = 0;
        constexpr int kRounds = 300;
        for (int r = 0; r < kRounds; ++r) {
            NewProtocol p(0ms);
            std::atomic<int> bad{0};
            std::atomic<bool> stopReader{false};
            std::thread reader([&] { while (!stopReader.load()) (void)p.scopeForWouldThrow(); });
            std::vector<std::thread> callers;
            for (int i = 0; i < 8; ++i)
                callers.emplace_back([&] {
                    p.shutdown();
                    if (!p.lanes.allDone()) bad.fetch_add(1);
                    if (!p.scopeForWouldThrow()) bad.fetch_add(1); // tras retornar, el propio hilo debe ver el cierre
                });
            for (auto& t : callers) t.join();
            stopReader.store(true); reader.join();
            if (bad.load() != 0) ++violations;
            if (p.scopeForWouldThrow()) ++roundsWithReaderSeeingClosedAfterReturn;
            if (p.lanes.cancelled.load() != 5 || p.lanes.executorsClosed.load() != 3) ++violations; // exactamente una vez, no doble cierre
        }
        check(violations == 0, "300 rondas x 8 llamadores concurrentes: 0 violaciones de postcondición; cierre ejecutado exactamente una vez");
        check(roundsWithReaderSeeingClosedAfterReturn == kRounds, "el flag queda visible para lectores sin lock en todas las rondas");
    }

    // 4. Idempotencia secuencial.
    {
        NewProtocol p(0ms);
        p.shutdown(); p.shutdown(); p.shutdown();
        check(p.lanes.cancelled.load() == 5 && p.lanes.executorsClosed.load() == 3, "llamadas repetidas no re-cancelan ni re-cierran");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
