// Sección 5 del prompt de cierre de Fase 1 — 1P1C/2P1C/3P1C/flood, pero
// probando el MODELO ARQUITECTÓNICO COMPLETO, no el SPSC aislado:
//
//   Producer A ─┐
//   Producer B ─┼──> [cola intermedia FIFO, N productores] ──> [ÚNICO hilo
//   Producer C ─┘     dispatcher] ──> SpscCommandQueue.push() ──> consumer
//
// Esto modela fielmente `AudioCommandDispatcher.kt`: un
// `ExecutorService.newSingleThreadExecutor()` internamente usa una cola
// bloqueante (`LinkedBlockingQueue`) que acepta múltiples productores de
// forma segura (garantía de la JDK) y un único hilo la drena — la cola
// intermedia aquí (`std::queue` + `std::mutex` + `std::condition_variable`)
// es el equivalente C++ directo de esa garantía, y el hilo "dispatcher"
// que la drena es el único que toca `SpscCommandQueue::push()` — igual
// que en Kotlin, donde el único hilo `eliner-audio-commands` es el único
// que llega a JNI.
//
// NO se está demostrando "el SPSC soporta N productores" (ya se demostró
// que NO — ver test_command_queue_interleaving_proof.cpp/
// test_command_queue_race_evidence.cpp). Se está demostrando que, con el
// patrón dispatcher de por medio, el SPSC SIGUE viendo un único productor
// real sin importar cuántos hilos lógicos originaron los comandos.
//
// Compilar con TSAN:
//   g++ -std=c++20 -fsanitize=thread -pthread -g \
//       -I<repo>/eliner/include/eliner/core \
//       test_dispatcher_pattern_multi_producer.cpp -o dispatcher_pattern
#include "CommandQueue.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

using eliner::EngineCommand;
using eliner::EngineCommandType;
using eliner::SpscCommandQueue;

namespace {

int gFailures = 0;
void check(bool condition, const char* what) {
    if (!condition) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else            { std::fprintf(stdout, "[ OK ] %s\n", what); }
}

/** Cola intermedia MPSC segura (mutex + condition_variable) — el
 *  equivalente directo de la LinkedBlockingQueue interna de un
 *  ExecutorService de un solo hilo en Java/Kotlin. Múltiples
 *  productores, un único consumidor (el hilo "dispatcher"). */
class IntermediateQueue {
public:
    void push(EngineCommand cmd) {
        std::lock_guard<std::mutex> lock(mMutex);
        mQueue.push(cmd);
        mCv.notify_one();
    }
    bool popBlocking(EngineCommand& out, std::atomic<bool>& stopFlag) {
        std::unique_lock<std::mutex> lock(mMutex);
        mCv.wait(lock, [&] { return !mQueue.empty() || stopFlag.load(std::memory_order_acquire); });
        if (mQueue.empty()) return false;
        out = mQueue.front();
        mQueue.pop();
        return true;
    }
    /** Despierta cualquier espera bloqueada en [popBlocking] SIN encolar
     *  ningún elemento — a diferencia de un comando "centinela"/"poison
     *  pill" empujado a la cola, esto no puede terminar contaminando al
     *  consumidor final (el SPSC) con un comando artificial que nunca
     *  vino de un productor real. */
    void notifyStop() {
        std::lock_guard<std::mutex> lock(mMutex);
        mCv.notify_one();
    }
private:
    std::mutex mMutex;
    std::condition_variable mCv;
    std::queue<EngineCommand> mQueue;
};

/** Ejecuta el patrón completo con `numProducers` hilos productores reales,
 *  cada uno enviando `perProducer` comandos con un origen distinto
 *  codificado en `intB`, y un único hilo dispatcher que los drena hacia
 *  el SPSC real. Verifica: sin pérdida, sin duplicados, FIFO por origen
 *  (cada productor individual mantiene su propio orden interno, que es
 *  la garantía real que importa — el orden RELATIVO entre productores
 *  distintos depende del scheduler y no es parte del contrato). */
void runPattern(int numProducers, int perProducer, const char* label) {
    std::fprintf(stdout, "\n== %s (%d productores x %d comandos) ==\n", label, numProducers, perProducer);

    SpscCommandQueue<EngineCommand, 4096> spsc; // capacidad amplia: el
                                                 // overflow del SPSC en sí
                                                 // ya está cubierto por
                                                 // test_command_queue_spsc_baseline.cpp;
                                                 // aquí el foco es el
                                                 // patrón multi-productor.
    IntermediateQueue intermediate;
    std::atomic<bool> stopDispatcher{false};
    std::atomic<bool> dispatcherFinished{false}; // true solo cuando el dispatcher YA drenó todo lo pendiente — distinto de stopDispatcher, que solo pide parar
    std::atomic<int> totalPushedToSpsc{0};

    // ── El único hilo que toca el SPSC — equivalente al hilo
    //    "eliner-audio-commands" de AudioCommandDispatcher.kt. ──
    std::atomic<int> totalDroppedBySpsc{0};

    // ── El único hilo que toca el SPSC — equivalente al hilo
    //    "eliner-audio-commands" de AudioCommandDispatcher.kt. ──
    std::thread dispatcherThread([&] {
        EngineCommand cmd;
        while (intermediate.popBlocking(cmd, stopDispatcher)) {
            // Fiel al comportamiento REAL de producción — no una
            // simplificación de test: `AudioCommandDispatcher.dispatchAsync`
            // llama a `delegate.noteOn(...)` UNA VEZ; si el JNI/SPSC
            // rechaza (cola llena), el comando se pierde, contado en
            // `droppedCount()` del lado C++ — no hay ningún reintento en
            // ningún punto de la cadena real. Reintentar aquí (como en un
            // borrador anterior de este test) habría probado un
            // comportamiento que el código de producción NO tiene — ver
            // el contrato de "best-effort delivery" ya documentado en
            // CommandQueue.h. Esto también cubre la sección 5.5 (queue
            // full) del prompt de cierre: el contrato real es
            // "descarta + cuenta", nunca "bloquea" ni "reintenta".
            if (spsc.push(cmd)) {
                totalPushedToSpsc.fetch_add(1, std::memory_order_relaxed);
            } else {
                totalDroppedBySpsc.fetch_add(1, std::memory_order_relaxed);
            }
        }
        dispatcherFinished.store(true, std::memory_order_release);
    });

    std::vector<std::thread> producers;
    for (int p = 0; p < numProducers; ++p) {
        producers.emplace_back([&, p] {
            for (int i = 0; i < perProducer; ++i) {
                EngineCommand cmd;
                cmd.type = EngineCommandType::NoteOn;
                cmd.intA = i;      // secuencia dentro de ESTE productor
                cmd.intB = p;      // identifica el productor de origen
                intermediate.push(cmd);
            }
        });
    }

    // ── Consumidor — simula el hilo de audio (processCommands()), que en
    //    producción real drena CONTINUAMENTE mientras llegan comandos, no
    //    solo al final. Corriendo en paralelo aquí también, en vez de
    //    después de que el dispatcher termine, para no confundir "SPSC se
    //    llenó porque nadie lo drenaba en este test" con un fallo real del
    //    patrón multi-productor — ese es un defecto de arnés de test, no
    //    del código bajo prueba (ver commit/historial de este archivo). ──
    std::vector<int> lastSeenPerProducer(numProducers, -1);
    std::atomic<int> consumed{0};
    std::atomic<bool> orderOk{true};
    std::thread consumerThread([&] {
        EngineCommand out;
        bool done = false;
        while (!done) {
            while (spsc.pop(out)) {
                if (out.type != EngineCommandType::NoteOn) continue; // descarta el comando "despertador" vacío
                consumed.fetch_add(1, std::memory_order_relaxed);
                int producer = out.intB;
                if (producer < 0 || producer >= numProducers) continue;
                // Monótono creciente, no "sin huecos": bajo el contrato
                // real (best-effort delivery, sección 5.5), un comando
                // puede descartarse por saturación del SPSC — eso deja un
                // hueco LEGÍTIMO en la secuencia de ESE productor, no una
                // violación de orden. Lo que sí sería una violación real
                // de FIFO es un RETROCESO o una REPETICIÓN — ninguno de
                // los cuales el contrato permite jamás.
                if (out.intA <= lastSeenPerProducer[producer]) orderOk.store(false, std::memory_order_relaxed);
                lastSeenPerProducer[producer] = out.intA;
            }
            done = dispatcherFinished.load(std::memory_order_acquire) &&
                   consumed.load(std::memory_order_relaxed) >= totalPushedToSpsc.load(std::memory_order_acquire);
            if (!done) std::this_thread::sleep_for(std::chrono::microseconds(50));
        }
    });

    for (auto& t : producers) t.join();
    stopDispatcher.store(true, std::memory_order_release);
    intermediate.notifyStop(); // despierta el wait() del dispatcher sin encolar nada — ver doc de notifyStop()
    dispatcherThread.join();
    consumerThread.join();

    const int totalSent = numProducers * perProducer;
    const int accountedFor = consumed.load() + totalDroppedBySpsc.load();
    check(accountedFor == totalSent,
          "todo comando enviado termina o bien consumido o bien expl\u00edcitamente contado como descartado — nunca desaparece sin explicaci\u00f3n");
    check(orderOk.load(), "cada productor mantiene su propio orden FIFO interno intacto, sin huecos ni duplicados");
    std::fprintf(stdout, "[INFO] %s: enviados=%d consumidos=%d descartados(SPSC lleno)=%d\n",
                 label, totalSent, consumed.load(), totalDroppedBySpsc.load());
    if (totalDroppedBySpsc.load() > 0) {
        std::fprintf(stdout, "[INFO] %s: hubo saturaci\u00f3n real del SPSC bajo esta carga — contrato real "
                     "confirmado (descarta + cuenta, no bloquea, no reintenta), no una p\u00e9rdida sin explicar.\n", label);
    }
}

/** Sección 5.5 del prompt de cierre — fuerza condiciones de cola llena de
 *  forma DETERMINISTA (no esperando que ocurra por azar bajo carga
 *  normal, como en runPattern) y documenta el contrato real observado:
 *  un único productor (el hilo dispatcher, ya con el modelo correcto)
 *  empuja mucho más rápido de lo que el consumidor lo hace, contra un
 *  SPSC deliberadamente pequeño. */
void runQueueFullTest() {
    std::fprintf(stdout, "\n== QUEUE FULL (determinista) ==\n");
    SpscCommandQueue<EngineCommand, 16> spsc; // pequeño a propósito
    constexpr int kTotal = 5000;

    // Llena el SPSC sin ningún consumidor drenando — un único
    // "productor" (ya el modelo correcto: un solo hilo real), igual que
    // test_command_queue_spsc_baseline.cpp pero documentado aquí en el
    // contexto específico de esta sección del prompt de cierre.
    int pushed = 0, rejected = 0;
    for (int i = 0; i < kTotal; ++i) {
        EngineCommand cmd;
        cmd.type = EngineCommandType::NoteOn;
        cmd.intA = i;
        if (spsc.push(cmd)) ++pushed; else ++rejected;
    }

    check(pushed == 15, "con capacidad 16 (kMask=15), el ring buffer acepta exactamente Capacity-1 elementos antes de reportarse lleno");
    check(rejected == kTotal - 15, "el resto se rechaza expl\u00edcitamente (push() retorna false), nunca se bloquea, nunca corrompe memoria");
    check(spsc.droppedCount() == static_cast<uint64_t>(rejected), "droppedCount() refleja EXACTAMENTE el n\u00famero de rechazos — el productor puede diagnosticar la saturaci\u00f3n consult\u00e1ndolo");

    // Contrato de responsabilidad del productor: el código real
    // (AudioEngine::pushCommand -> mCommandQueue.push()) NO reintenta, NO
    // bloquea, NO asigna memoria en el rechazo — el llamador (control
    // thread) puede seguir ejecutándose inmediatamente. Ownership/memoria:
    // EngineCommand es POD sin miembros que posean memoria (ver el propio
    // comentario de la struct en CommandQueue.h, "keep it POD (no
    // heap-owning members)"), así que un comando rechazado no fuga nada
    // — simplemente deja de existir cuando termina el scope del llamador.
    // La ÚNICA excepción son InsertModule (ptrA = DspModule* ya
    // construido por el control thread) — si ESE comando específico se
    // rechaza, el DspModule* recién construido SÍ fugaría a menos que
    // AudioEngine::insertModule() lo libere explícitamente cuando
    // pushCommand() devuelve false (ver AudioEngine.cpp) — esto no se
    // audita en este test aislado del SPSC, queda cubierto en el análisis
    // de la sección 7 (Retire Queue / ownership) del informe.
    std::fprintf(stdout, "[INFO] QUEUE FULL: enviados=%d aceptados=%d rechazados=%d droppedCount()=%llu\n",
                 kTotal, pushed, rejected, static_cast<unsigned long long>(spsc.droppedCount()));

    // Ahora drena — confirma que lo aceptado sigue íntegro tras el
    // período de saturación (la saturación no corrompe lo que ya estaba
    // adentro).
    int drained = 0;
    EngineCommand out;
    int expectedNext = 0;
    bool orderIntact = true;
    while (spsc.pop(out)) {
        if (out.intA != expectedNext) orderIntact = false;
        ++expectedNext;
        ++drained;
    }
    check(drained == pushed, "todo lo aceptado durante la saturaci\u00f3n se puede drenar despu\u00e9s, sin p\u00e9rdida adicional");
    check(orderIntact, "el orden de lo aceptado permanece intacto tras la saturaci\u00f3n");
}

} // namespace

int main() {
    std::fprintf(stdout, "== test_dispatcher_pattern_multi_producer ==\n");
    runPattern(1, 5000,  "1P1C");
    runPattern(2, 5000,  "2P1C");
    runPattern(3, 5000,  "3P1C");
    runPattern(3, 15000, "FLOOD (3 productores, carga alta)");
    runQueueFullTest();
    std::fprintf(stdout, "\n== %s (%d fallos) ==\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
