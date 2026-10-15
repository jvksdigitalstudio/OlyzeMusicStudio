// Fase 1.1 §14 — réplica ejecutable, en C++ puro, de la lógica de
// coalescing "latest-value" + cola acotada/backpressure añadida a
// AudioCommandDispatcher.kt (ParamTarget / pendingParams / flushScheduled
// / orderedExecutor). Existe porque este entorno no tiene kotlinc/JDK
// completo disponible (ver ADR 0018) — mismo patrón ya establecido en
// Fase 1 (test_dispatcher_pattern_multi_producer.cpp) y en esta misma
// Fase 1.1 (test_midi_backend_generation_model.cpp, ADR 0017).
// AudioCommandDispatcherCoalescingTest.kt (el test Kotlin real, con
// JUnit4) queda escrito y listo para CI, pero NO se declara ejecutado
// aquí.
#include <atomic>
#include <cstdio>
#include <cstdint>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
}

// ── Réplica de la "cola acotada + backpressure" (orderedExecutor real) ──
// ArrayBlockingQueue<Runnable> de capacidad fija + AbortPolicy se modela
// aquí como un deque de tamaño acotado con rechazo explícito — la
// propiedad relevante bajo prueba es "encolar puede fallar y el llamador
// se entera (false), nunca crece sin límite", no el mecanismo de threads
// de Java en sí (ya cubierto, para el caso de cierre, por
// test_dispatcher_shutdown_race.cpp / test_dispatcher_pattern_multi_producer.cpp).
struct BoundedOrderedExecutor {
    explicit BoundedOrderedExecutor(size_t capacity) : mCapacity(capacity) {}
    size_t mCapacity;
    std::deque<std::string> mQueue; // guarda una descripción de la tarea, no la tarea en sí — basta para contar/inspeccionar en este modelo
    bool mClosed = false;

    // Retorna false si está cerrado O si la cola ya está a capacidad —
    // mismo contrato que orderedExecutor.execute() lanzando
    // RejectedExecutionException por AMBAS razones en el Kotlin real.
    bool submit(const std::string& taskDescription) {
        if (mClosed || mQueue.size() >= mCapacity) return false;
        mQueue.push_back(taskDescription);
        return true;
    }

    // Simula el hilo dispatcher drenando una tarea (FIFO).
    std::string drainOne() {
        std::string t = mQueue.front();
        mQueue.pop_front();
        return t;
    }

    bool empty() const { return mQueue.empty(); }
};

// ── Réplica de ParamTarget / pendingParams / flushScheduled / setParameter() ──
enum class ParamTarget { MasterVolume, ReverbMix, DelayMix, DelayTime, DelayFeedback };

struct DispatcherModel {
    BoundedOrderedExecutor executor{4}; // capacidad pequeña a propósito, para poder forzar rechazo en el test de backpressure
    std::map<ParamTarget, float> pendingParams;
    std::atomic<bool> flushScheduled{false};
    std::vector<std::pair<ParamTarget, float>> deliveredToDelegate; // lo que el "delegate" realmente vio, en orden de aplicación

    // Réplica exacta de AudioCommandDispatcher.setParameter().
    void setParameter(ParamTarget target, float value) {
        pendingParams[target] = value; // put() simple — la llamada más reciente siempre gana.
        scheduleFlushIfNeeded();
    }

    // Réplica exacta de AudioCommandDispatcher.scheduleFlushIfNeeded().
    void scheduleFlushIfNeeded() {
        bool expected = false;
        if (!flushScheduled.compare_exchange_strong(expected, true)) return; // ya hay un flush reservado

        bool accepted = executor.submit("flush");
        if (!accepted) {
            // Rechazado (cola llena o cerrado) — revertir la reserva, EL
            // FIX ESPECÍFICO bajo prueba: sin esto, flushScheduled queda
            // en true para siempre y ningún setParameter() futuro vuelve
            // a intentar encolar un flush real.
            flushScheduled.store(false);
        }
    }

    // Réplica exacta de drainAndApplyPendingParams() — se invoca cuando el
    // "hilo dispatcher" simulado drena la tarea "flush" de la cola.
    void runFlush() {
        // El hilo dispatcher real (ThreadPoolExecutor) retira la tarea de
        // su cola en el momento de EMPEZAR a ejecutarla, no al terminar —
        // por eso esto va primero: el slot que ocupaba esta tarea "flush"
        // en orderedExecutor ya está libre mientras el resto de este
        // método corre, exactamente como en el Kotlin real.
        executor.drainOne();
        flushScheduled.store(false); // liberar ANTES de drenar, igual que el Kotlin real
        auto batch = pendingParams; // snapshot
        for (auto& [target, value] : batch) {
            // remove(key, value) condicional — aquí simplificado porque
            // este modelo es single-threaded para el drenaje (el
            // ConcurrentHashMap real solo necesita esa forma condicional
            // para la carrera productor/drenaje concurrente, ya cubierta
            // conceptualmente por el propio Kotlin real vía
            // ConcurrentHashMap — no es lo que este test aísla).
            auto it = pendingParams.find(target);
            if (it != pendingParams.end() && it->second == value) {
                pendingParams.erase(it);
            }
            deliveredToDelegate.emplace_back(target, value);
        }
    }
};

int main() {
    std::fprintf(stdout, "=== test_dispatcher_coalescing_and_backpressure_model ===\n");

    // ── Caso 1: N llamadas rápidas al MISMO parámetro colapsan en 1 entrega ──
    {
        DispatcherModel d;
        for (int i = 0; i < 200; ++i) {
            d.setParameter(ParamTarget::MasterVolume, i / 200.0f);
        }
        // Solo debe haber UNA tarea "flush" en la cola, sin importar
        // cuántas veces se llamó a setParameter() — esto ES el coalescing.
        check(d.executor.mQueue.size() == 1,
              "Caso 1: 200 llamadas al mismo parámetro encolan una única tarea de flush");
        d.runFlush();
        check(d.deliveredToDelegate.size() == 1,
              "Caso 1: el delegate recibe una única entrega para ese parámetro");
        check(d.deliveredToDelegate[0].second == 199 / 200.0f,
              "Caso 1: el valor entregado es el ÚLTIMO recibido, no el primero ni un promedio");
    }

    // ── Caso 2: parámetros DISTINTOS se batchean juntos, ninguno se pierde ──
    {
        DispatcherModel d;
        d.setParameter(ParamTarget::MasterVolume, 0.5f);
        d.setParameter(ParamTarget::ReverbMix, 0.3f);
        d.setParameter(ParamTarget::DelayMix, 0.2f);
        check(d.executor.mQueue.size() == 1, "Caso 2: sigue siendo una única tarea de flush (batching)");
        d.runFlush();
        check(d.deliveredToDelegate.size() == 3, "Caso 2: los 3 parámetros distintos se entregan, ninguno se pierde");
    }

    // ── Caso 3: ciclos de flush repetidos — el mecanismo no es de un solo uso ──
    {
        DispatcherModel d;
        d.setParameter(ParamTarget::MasterVolume, 0.1f);
        d.runFlush(); // primer ciclo completo
        check(d.deliveredToDelegate.size() == 1, "Caso 3.1: primer flush entrega 1 valor");

        for (int i = 0; i < 30; ++i) d.setParameter(ParamTarget::MasterVolume, 0.5f + i / 100.0f);
        check(d.executor.mQueue.size() == 1,
              "Caso 3.2: un SEGUNDO ciclo de coalescing también colapsa correctamente "
              "(flushScheduled se liberó bien tras el primer runFlush())");
        d.runFlush();
        check(d.deliveredToDelegate.size() == 2, "Caso 3.3: segunda entrega acumulada correctamente");
        check(d.deliveredToDelegate[1].second == 0.5f + 29 / 100.0f, "Caso 3.4: último valor del segundo ciclo es el correcto");
    }

    // ── Caso 4 (EL FIX ESPECÍFICO): cola llena revierte flushScheduled ──
    {
        DispatcherModel d;
        // Satura el executor con 4 tareas ficticias no relacionadas (su
        // capacidad de prueba es 4) para forzar el rechazo del submit()
        // del flush.
        for (int i = 0; i < 4; ++i) check(d.executor.submit("filler"), "Caso 4.0: llenar la cola de relleno");
        check(d.executor.mQueue.size() == 4, "Caso 4.1: cola a capacidad completa");

        d.setParameter(ParamTarget::DelayFeedback, 0.9f); // scheduleFlushIfNeeded() será rechazado por cola llena
        check(d.flushScheduled.load() == false,
              "Caso 4.2 [BUG SIN EL FIX]: flushScheduled se revierte a false tras un rechazo por cola llena, "
              "en vez de quedar atascado en true para siempre");

        // Libera espacio y confirma que un setParameter() posterior SÍ
        // puede reservar un flush nuevo — la prueba real de que no quedó
        // atascado.
        d.executor.drainOne();
        d.setParameter(ParamTarget::DelayFeedback, 0.95f);
        check(d.executor.mQueue.size() == 4 /* 3 relleno restante + 1 flush nuevo */,
              "Caso 4.3: tras liberar espacio, un setParameter() posterior SÍ logra encolar un flush");
    }

    // ── Caso 5: eventos discretos (no-parámetros) NUNCA se coalescen ──
    {
        BoundedOrderedExecutor discreteQueue{256}; // misma capacidad que la real (kOrderedQueueCapacity)
        for (int i = 0; i < 50; ++i) {
            check(discreteQueue.submit("noteOn#" + std::to_string(i)),
                  "Caso 5: cada noteOn discreto se encola individualmente, no se coalesce");
        }
        check(discreteQueue.mQueue.size() == 50,
              "Caso 5: 50 noteOn producen 50 entradas en la cola (vs. 1 para 200 setMasterVolume del Caso 1)");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n",
                 gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
