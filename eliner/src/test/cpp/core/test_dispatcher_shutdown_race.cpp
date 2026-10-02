// Sección 5.6 del prompt de cierre de Fase 1 — "shutdown race": productor
// concurrente con dispatcher.close(), múltiples interleavings.
//
// No puede ejecutarse el AudioCommandDispatcher.kt real aquí (sin
// kotlinc/Gradle — ver limitaciones de entorno). Este test modela el
// MISMO contrato de cierre que esa clase implementa —
// `java.util.concurrent.ExecutorService.execute()`/`submit()` lanzando
// `RejectedExecutionException` tras `shutdown()`, capturada
// específicamente y nunca propagada — con una estructura C++ equivalente
// (mutex + flag de cierre + cola), para poder verificar el comportamiento
// con hilos reales y TSAN, en vez de solo declarar que el diseño Kotlin
// "debería" comportarse así.
//
// Lo que se verifica aquí es la PROPIEDAD del contrato (rechazo
// silencioso tras cierre, sin excepción hacia el llamador, sin acceso
// concurrente inválido a la cola/estructura interna), no el bytecode de
// Kotlin en sí — ver AudioCommandDispatcherTest.kt para las aserciones
// específicas sobre esa clase, marcadas EJECUTADO: NO.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

namespace {

int gFailures = 0;
void check(bool condition, const char* what) {
    if (!condition) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else            { std::fprintf(stdout, "[ OK ] %s\n", what); }
}

/** Modela el mismo contrato que AudioCommandDispatcher.dispatchAsync +
 *  close(): tras cerrar, un intento de despachar se rechaza de forma
 *  silenciosa — nunca lanza, nunca corrompe, nunca vuelve a aceptar. */
class DispatcherModel {
public:
    /** Análogo a dispatchAsync(): intenta encolar; si ya está cerrado,
     *  se descarta SIN lanzar nada hacia el llamador — mismo contrato
     *  que la captura de RejectedExecutionException en Kotlin. */
    bool tryDispatch(int commandId) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mClosed) {
            ++mRejectedCount;
            return false; // rechazado, silenciosamente — nunca lanza
        }
        mExecuted.push_back(commandId);
        return true;
    }

    /** Análogo a close(): idempotente, segura de llamar concurrentemente
     *  varias veces (mismo contrato que ExecutorService.shutdown()). */
    void close() {
        std::lock_guard<std::mutex> lock(mMutex);
        mClosed = true;
    }

    size_t executedCount() const { std::lock_guard<std::mutex> lock(mMutex); return mExecuted.size(); }
    int rejectedCount() const { std::lock_guard<std::mutex> lock(mMutex); return mRejectedCount; }
    bool hasDuplicates() const {
        std::lock_guard<std::mutex> lock(mMutex);
        std::vector<int> sorted = mExecuted;
        std::sort(sorted.begin(), sorted.end());
        return std::adjacent_find(sorted.begin(), sorted.end()) != sorted.end();
    }

private:
    mutable std::mutex mMutex;
    bool mClosed = false;
    std::vector<int> mExecuted;
    int mRejectedCount = 0;
};

} // namespace

int main() {
    std::fprintf(stdout, "== test_dispatcher_shutdown_race ==\n");

    // ── Interleaving 1: close() ANTES de que el productor empiece ──
    {
        DispatcherModel d;
        d.close();
        bool accepted = d.tryDispatch(1);
        check(!accepted, "close() antes del primer comando: se rechaza, no se ejecuta");
        check(d.executedCount() == 0, "nada se ejecuta tras un cierre previo");
        check(d.rejectedCount() == 1, "el rechazo se contabiliza (observable, no un fallo silencioso sin rastro)");
    }

    // ── Interleaving 2: close() concurrente con un productor real, carga
    //    sostenida — el caso que realmente importa (múltiples
    //    interleavings posibles según el scheduler). ──
    {
        DispatcherModel d;
        constexpr int kAttempts = 20000;
        std::atomic<int> producerAcceptedLocally{0};
        std::atomic<bool> noExceptionEscaped{true}; // en C++ no hay excepciones que capturar aquí (el diseño ya no lanza) — el propio hecho de que el programa no crashee/aborte demuestra la propiedad

        std::thread producer([&] {
            for (int i = 0; i < kAttempts; ++i) {
                if (d.tryDispatch(i)) producerAcceptedLocally.fetch_add(1, std::memory_order_relaxed);
                // Sin try/catch aquí a propósito: si tryDispatch() alguna
                // vez lanzara (no debería, por diseño), el programa
                // terminaría abruptamente (terminate/abort) — la AUSENCIA
                // de ese crash, confirmada por el "PASS" final, ES la
                // evidencia de que el contrato "nunca lanza hacia el
                // llamador" se cumple bajo carga real concurrente con
                // close().
            }
        });
        std::thread closer([&] {
            // Cierra en algún punto intermedio, no al principio ni al
            // final — maximiza la probabilidad de que el scheduler
            // real intercale distintos ordenamientos entre corridas.
            std::this_thread::sleep_for(std::chrono::microseconds(500));
            d.close();
            d.close(); // segunda llamada — sección 5, "múltiples llamadas
                       // simultáneas a close() si el contrato lo permite":
                       // aquí se permite (idempotente), verificado abajo
                       // que no rompe nada.
        });

        producer.join();
        closer.join();

        check(noExceptionEscaped.load(), "ninguna excepci\u00f3n escap\u00f3 hacia el llamador durante la carrera producer/close (el programa no abort\u00f3)");
        check(static_cast<size_t>(producerAcceptedLocally.load()) == d.executedCount(),
              "el conteo que ve el productor coincide exactamente con lo realmente ejecutado — sin doble conteo ni p\u00e9rdida de contabilidad");
        check(d.executedCount() + static_cast<size_t>(d.rejectedCount()) == kAttempts,
              "todo intento termina contabilizado como ejecutado o como rechazado — ninguno desaparece sin explicaci\u00f3n");
        check(!d.hasDuplicates(), "ning\u00fan comando se ejecuta dos veces bajo la carrera");
        std::fprintf(stdout, "[INFO] Interleaving 2: intentos=%d ejecutados=%zu rechazados=%d (cierre concurrente en punto intermedio, 2 llamadas a close())\n",
                     kAttempts, d.executedCount(), d.rejectedCount());
    }

    // ── Interleaving 3: múltiples productores + close() concurrente —
    //    el escenario más fiel a producción (UI + MIDI, ambos pudiendo
    //    estar en vuelo justo cuando onCleared()/AppServices.shutdown()
    //    llama a audioDispatcher.close()). ──
    {
        DispatcherModel d;
        constexpr int kPerProducer = 8000;
        std::atomic<int> totalAccepted{0};
        std::vector<std::thread> producers;
        for (int p = 0; p < 3; ++p) {
            producers.emplace_back([&, p] {
                for (int i = 0; i < kPerProducer; ++i) {
                    if (d.tryDispatch(p * kPerProducer + i)) totalAccepted.fetch_add(1, std::memory_order_relaxed);
                }
            });
        }
        std::thread closer([&] {
            std::this_thread::sleep_for(std::chrono::microseconds(300));
            d.close();
        });
        for (auto& t : producers) t.join();
        closer.join();

        check(static_cast<size_t>(totalAccepted.load()) == d.executedCount(),
              "con 3 productores + close() concurrente, la contabilidad sigue exacta — sin carreras en el propio mecanismo de conteo");
        check(d.executedCount() + static_cast<size_t>(d.rejectedCount()) == 3 * kPerProducer,
              "todo intento de los 3 productores termina contabilizado — ninguno se pierde sin explicaci\u00f3n, incluso con cierre en vuelo");
        check(!d.hasDuplicates(), "ning\u00fan comando duplicado con 3 productores concurrentes + cierre");
        std::fprintf(stdout, "[INFO] Interleaving 3: ejecutados=%zu rechazados=%d (3 productores + close() concurrente)\n",
                     d.executedCount(), d.rejectedCount());
    }

    std::fprintf(stdout, "\n== %s (%d fallos) ==\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
