// EVIDENCIA EMPÍRICA del hallazgo "CRÍTICO 1" de la auditoría de Fase 1:
// SpscCommandQueue documenta "Producer: Control thread ONLY", pero antes
// del fix de AudioCommandDispatcher.kt, dos hilos reales de Android
// llamaban a AudioEngine::pushCommand() concurrentemente:
//   - hilo UI (main)      — teclado en pantalla -> MainViewModel.noteOn()
//   - hilo "eliner-dsp"   — MidiRouter (ExecutionLane.DSP) -> MidiToSynthBridge
//
// Este test reproduce EXACTAMENTE ese patrón (2 hilos productores reales
// empujando concurrentemente al mismo SpscCommandQueue, sin ningún
// mecanismo de serialización) contra el CommandQueue.h REAL del proyecto,
// compilado con ThreadSanitizer. Se espera — y este test lo confirma —
// que TSAN reporte una data race real, no hipotética.
//
// Este test NO valida una corrección: documenta el problema. La
// corrección real vive en Kotlin (AudioCommandDispatcher, que garantiza
// que solo UN hilo físico llegue jamás a pushCommand()) — ver su propio
// archivo de test (AudioCommandDispatcherTest.kt) para la verificación
// de ESA solución.
//
// Compilar (requiere TSAN para que el reporte de carrera sea explícito):
//   g++ -std=c++20 -fsanitize=thread -pthread -g \
//       -I<repo>/eliner/include/eliner/core \
//       test_command_queue_race_evidence.cpp -o race_evidence
// Ejecutar: ./race_evidence
//   Salida esperada: TSAN imprime "WARNING: ThreadSanitizer: data race" y
//   el proceso termina con código de salida != 0 — CONFIRMANDO el hallazgo.
//   Si TSAN no está disponible, el test igual se ejecuta e imprime
//   cualquier corrupción de datos observable (pérdida silenciosa,
//   duplicación) que logre detectar sin instrumentación, aunque sin TSAN
//   una data race puede "parecer" funcionar por pura suerte de scheduling.

#include "CommandQueue.h"

#include <atomic>
#include <cstdio>
#include <set>
#include <thread>

using eliner::EngineCommand;
using eliner::EngineCommandType;
using eliner::SpscCommandQueue;

int main() {
    std::fprintf(stdout, "== test_command_queue_race_evidence ==\n");
    std::fprintf(stdout,
        "[INFO] Reproduciendo el patrón real pre-fix: 2 hilos productores "
        "(UI + eliner-dsp) empujando concurrentemente al mismo "
        "SpscCommandQueue, sin serializar — exactamente como estaba antes "
        "de AudioCommandDispatcher.kt.\n");

    constexpr int kPerProducer = 50000;
    // Capacidad deliberadamente pequeña: maximiza la probabilidad de que
    // ambos productores calculen el MISMO índice `tail` en la misma
    // ventana de tiempo (más colisiones por unidad de tiempo = más
    // oportunidades de que TSAN sorprenda la race en el acto), sin
    // backoff (busy loop puro) por el mismo motivo.
    SpscCommandQueue<EngineCommand, 4> q;
    std::atomic<int> uiSent{0};
    std::atomic<int> midiSent{0};
    std::atomic<bool> stopConsumer{false};
    std::atomic<int> totalConsumed{0};

    // Consumidor arranca PRIMERO y corre en paralelo real durante todo el
    // test — simula el hilo de audio drenando en cada callback mientras
    // los productores siguen enviando, igual que en producción.
    std::thread consumerThread([&] {
        EngineCommand out;
        while (!stopConsumer.load(std::memory_order_acquire)) {
            if (q.pop(out)) totalConsumed.fetch_add(1, std::memory_order_relaxed);
        }
        while (q.pop(out)) totalConsumed.fetch_add(1, std::memory_order_relaxed);
    });

    // Productor A — simula el hilo UI (toques de teclado en pantalla).
    std::thread uiThread([&] {
        for (int i = 0; i < kPerProducer; ++i) {
            EngineCommand cmd;
            cmd.type = EngineCommandType::NoteOn;
            cmd.intA = i;
            cmd.intB = 1; // origen = UI
            if (q.push(cmd)) uiSent.fetch_add(1, std::memory_order_relaxed);
        }
    });

    // Productor B — simula el hilo "eliner-dsp" (MidiRouter -> MidiToSynthBridge).
    std::thread midiThread([&] {
        for (int i = 0; i < kPerProducer; ++i) {
            EngineCommand cmd;
            cmd.type = EngineCommandType::NoteOn;
            cmd.intA = i;
            cmd.intB = 2; // origen = MIDI externo
            if (q.push(cmd)) midiSent.fetch_add(1, std::memory_order_relaxed);
        }
    });

    uiThread.join();
    midiThread.join();
    stopConsumer.store(true, std::memory_order_release);
    consumerThread.join();

    int totalPushedOk = uiSent.load() + midiSent.load();
    std::fprintf(stdout,
        "[INFO] UI: %d intentos, %d push() reportaron éxito. MIDI: %d intentos, %d push() reportaron éxito. "
        "Suma de éxitos reportados = %d. Total realmente drenado por el consumidor = %d.\n",
        kPerProducer, uiSent.load(), kPerProducer, midiSent.load(), totalPushedOk, totalConsumed.load());
    if (totalConsumed.load() < totalPushedOk) {
        std::fprintf(stdout,
            "[CONFIRMADO] Se perdieron %d comandos que push() reportó como exitosos pero nunca llegaron "
            "al consumidor — pérdida silenciosa por escritura concurrente sin sincronización sobre el mismo slot del ring buffer.\n",
            totalPushedOk - totalConsumed.load());
    }
    std::fprintf(stdout,
        "[INFO] Si totalConsumed != uiSent+midiSent, o si TSAN reportó una "
        "data race arriba, el hallazgo queda confirmado empíricamente: "
        "SpscCommandQueue con 2 productores reales sin serializar no es seguro.\n");

    // Este test documenta el problema — no falla el proceso por el
    // conteo (eso depende del timing/scheduling y no es el punto:
    // el punto es la data race que TSAN detecta a nivel de memoria,
    // independientemente de si el conteo "por suerte" cuadra en esta
    // ejecución particular).
    return 0;
}
