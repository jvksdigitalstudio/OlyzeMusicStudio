// Test REAL, standalone — compila contra el CommandQueue.h real del
// proyecto (eliner/include/eliner/core/CommandQueue.h), sin NDK ni Oboe:
// CommandQueue.h es C++20 puro (<atomic>/<array>/<cstdint>), así que se
// puede compilar y EJECUTAR de verdad con g++ en cualquier entorno con
// compilador C++, incluyendo este. Sin framework (no hay gtest en el
// proyecto — ver CMakeLists.txt) — runner propio basado en asserts, que
// sale con código != 0 si algo falla, para poder automatizarlo.
//
// Objetivo: confirmar el caso QUE SÍ ESTÁ GARANTIZADO por el contrato de
// SpscCommandQueue — 1 productor, 1 consumidor — bajo carga sostenida,
// con y sin ThreadSanitizer. Este es el baseline "correcto" contra el que
// se compara test_command_queue_race_evidence.cpp (2+ productores).
//
// Compilar y ejecutar:
//   g++ -std=c++20 -Wall -Wextra -pthread -I<repo>/eliner/include/eliner/core \
//       test_command_queue_spsc_baseline.cpp -o baseline && ./baseline
//   g++ -std=c++20 -fsanitize=thread -pthread ... (TSAN build)

#include "CommandQueue.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

using eliner::EngineCommand;
using eliner::EngineCommandType;
using eliner::SpscCommandQueue;

namespace {

int gFailures = 0;

void check(bool condition, const char* what) {
    if (!condition) {
        std::fprintf(stderr, "[FAIL] %s\n", what);
        ++gFailures;
    } else {
        std::fprintf(stdout, "[ OK ] %s\n", what);
    }
}

// ── Caso 1: producer/consumer secuencial, sin hilos — corrección funcional pura.
void testSequentialCorrectness() {
    SpscCommandQueue<EngineCommand, 8> q;
    for (int i = 0; i < 7; ++i) { // capacity-1 slots usables (ring buffer)
        EngineCommand cmd;
        cmd.type = EngineCommandType::NoteOn;
        cmd.intA = i;
        check(q.push(cmd), "push secuencial dentro de capacidad debe aceptar");
    }
    EngineCommand overflow;
    overflow.type = EngineCommandType::NoteOn;
    overflow.intA = 999;
    check(!q.push(overflow), "push con la cola llena debe rechazar (no bloquear, no corromper)");
    check(q.droppedCount() == 1, "droppedCount debe reflejar exactamente 1 comando perdido");

    for (int i = 0; i < 7; ++i) {
        EngineCommand out;
        check(q.pop(out), "pop debe entregar cada comando insertado");
        check(out.intA == i, "el orden FIFO debe preservarse exactamente");
    }
    EngineCommand empty;
    check(!q.pop(empty), "pop sobre cola vacía debe fallar, no bloquear");
}

// ── Caso 2: 1 hilo productor real + 1 hilo consumidor real, carga sostenida.
//    Esto es exactamente el contrato que CommandQueue.h documenta como
//    soportado. Debe completarse sin pérdida de datos, sin corrupción, y
//    (cuando se compila con -fsanitize=thread) sin ningún reporte de TSAN.
void testConcurrentSpscNoLoss() {
    constexpr int kTotal = 200000;
    SpscCommandQueue<EngineCommand, 1024> q;
    std::atomic<bool> producerDone{false};

    std::thread producer([&] {
        int i = 0;
        while (i < kTotal) {
            EngineCommand cmd;
            cmd.type = EngineCommandType::SetParameter;
            cmd.intA = i;
            if (q.push(cmd)) ++i; // reintenta si la cola está llena — el
                                   // productor real (control thread) no
                                   // pierde comandos por diseño en este test,
                                   // solo cede tiempo al consumidor.
            else std::this_thread::yield();
        }
        producerDone.store(true, std::memory_order_release);
    });

    std::thread consumer([&] {
        int expectedNext = 0;
        int received = 0;
        EngineCommand out;
        while (received < kTotal) {
            if (q.pop(out)) {
                if (out.intA != expectedNext) {
                    std::fprintf(stderr,
                        "[FAIL] orden roto en consumo concurrente: esperado=%d recibido=%d\n",
                        expectedNext, out.intA);
                    ++gFailures;
                }
                ++expectedNext;
                ++received;
            } else {
                std::this_thread::yield();
            }
        }
    });

    producer.join();
    consumer.join();
    check(producerDone.load(), "el hilo productor debe terminar de enviar todos los comandos");
    // NOTA: droppedCount() cuenta INTENTOS de push fallidos (cola llena en
    // ese instante), no comandos perdidos — este productor reintenta hasta
    // tener éxito, así que droppedCount() > 0 es esperado bajo ráfagas
    // donde el consumidor se queda momentáneamente atrás; lo que garantiza
    // "sin pérdida" es que los 200000 comandos SÍ llegaron, en orden exacto
    // (verificado arriba en el loop del consumidor). No se afirma
    // droppedCount()==0 aquí — afirmarlo sería una expectativa incorrecta
    // sobre lo que esa métrica mide, no un requisito real del contrato SPSC.
    std::fprintf(stdout,
        "[INFO] testConcurrentSpscNoLoss: %d comandos, orden y datos verificados sin pérdida (reintentos por cola llena: %llu)\n",
        kTotal, static_cast<unsigned long long>(q.droppedCount()));
}

} // namespace

int main() {
    std::fprintf(stdout, "== test_command_queue_spsc_baseline ==\n");
    testSequentialCorrectness();
    testConcurrentSpscNoLoss();
    std::fprintf(stdout, "== %s (%d fallos) ==\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
