// ADR 0028 — Auditoría de concurrencia del transporte en AudioEngine.
//
// Ejecuta el AudioEngine REAL (backend de audio falso, ver oboe_fake.cpp) con un
// hilo que hace de "hilo de audio" (llama a onAudioReady en bucle) y uno o varios
// hilos de control que envían órdenes de transporte/FX/notas a la vez. Bajo
// ThreadSanitizer (la suite lo ejecuta también con -fsanitize=thread) comprueba:
//
//   C1  UN productor (el contrato de la cola SPSC): sin data races, salida finita.
//   C2  DOS hilos de control serializados por un candado, que es EXACTAMENTE lo que
//       hace gEngineMutex en los puentes JNI (EngineHandle.h): sin data races. Esto
//       valida el argumento de que el candado basta para que varios hilos de control
//       (UI, hilo de arranque, despachador) sean seguros sobre una cola de un productor.
//
// Los miembros nuevos del transporte (reloj, metrónomo, sync del delay) los toca solo
// el hilo de audio; este test es la comprobación empírica de esa propiedad.
#include "AudioEngine.h"
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <mutex>
#include <random>
#include <thread>
#include <vector>

using eliner::AudioEngine;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}

// Una ráfaga de órdenes aleatorias (incluye entradas inválidas a propósito).
void controlBurst(AudioEngine& e, unsigned seed, int ops, std::mutex* serialize) {
    std::mt19937 rng(seed);
    std::uniform_int_distribution<int> op(0, 14), note(36, 84);
    std::uniform_real_distribution<float> bpm(-10.f, 400.f), unit(-0.5f, 1.5f);
    for (int i = 0; i < ops; ++i) {
        std::unique_lock<std::mutex> lock;
        if (serialize) lock = std::unique_lock<std::mutex>(*serialize);
        switch (op(rng)) {
            case 0:  e.setTempo(bpm(rng)); break;
            case 1:  e.setTempo(std::numeric_limits<float>::quiet_NaN()); break;
            case 2:  e.setTransportRunning(rng() % 2 == 0); break;
            case 3:  e.setMetronomeEnabled(rng() % 2 == 0); break;
            case 4:  e.setMetronomeVolume(unit(rng)); break;
            case 5:  e.setBeatsPerBar(static_cast<int>(rng() % 24) - 4); break;
            case 6:  e.setDelayTempoSync(rng() % 2 == 0, unit(rng) * 4.f); break;
            case 7:  e.setDelayTime(unit(rng)); break;
            case 8:  e.setDelayMix(unit(rng)); break;
            case 9:  e.setDelayFeedback(unit(rng)); break;
            case 10: e.setReverbMix(unit(rng)); break;
            case 11: e.noteOn(0, note(rng), 90); break;
            case 12: e.noteOff(0, note(rng)); break;
            case 13: e.setMasterVolume(unit(rng)); break;
            default: std::this_thread::yield(); break;
        }
    }
}

// Corre el "hilo de audio" mientras [producers] envían órdenes. Devuelve true si toda la salida fue finita.
bool runScenario(int producers, bool serialized, unsigned baseSeed) {
    AudioEngine e;
    if (!e.start(0)) return false;
    std::atomic<bool> stop{false}, finiteOut{true};
    std::atomic<long> blocks{0};
    std::thread audio([&] {
        std::vector<float> buf(2 * 480);
        while (!stop.load(std::memory_order_acquire)) {
            std::fill(buf.begin(), buf.end(), 0.0f);
            e.onAudioReady(nullptr, buf.data(), 480);
            for (float v : buf) if (!std::isfinite(v)) finiteOut.store(false);
            blocks.fetch_add(1, std::memory_order_relaxed);
        }
    });
    std::mutex gate;     // equivalente a gEngineMutex
    std::vector<std::thread> controls;
    for (int p = 0; p < producers; ++p)
        controls.emplace_back([&, p] { controlBurst(e, baseSeed + static_cast<unsigned>(p), 6000, serialized ? &gate : nullptr); });
    for (auto& t : controls) t.join();
    // Deja drenar la cola y rodar un poco más el audio con el estado final.
    const long target = blocks.load() + 200;
    while (blocks.load() < target) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    stop.store(true, std::memory_order_release);
    audio.join();
    e.stop();
    std::fprintf(stdout, "       (%ld bloques de audio procesados durante el escenario)\n", blocks.load());
    return finiteOut.load();
}
}

int main() {
    check(runScenario(/*producers=*/1, /*serialized=*/false, 1000u),
          "C1. un hilo de control + hilo de audio, 6000 órdenes aleatorias: sin data races y salida siempre finita");
    check(runScenario(/*producers=*/2, /*serialized=*/true, 2000u),
          "C2. dos hilos de control serializados por un candado (como gEngineMutex) + hilo de audio: sin data races y salida siempre finita");
    check(runScenario(/*producers=*/3, /*serialized=*/true, 3000u),
          "C3. tres hilos de control serializados por un candado + hilo de audio: sin data races y salida siempre finita");
    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
