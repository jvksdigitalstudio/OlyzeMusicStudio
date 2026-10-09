// ADR 0029 — Publicación de la posición de pulso (AudioEngine REAL, backend FALSO).
//
// Verifica de extremo a extremo la ruta  setTransportRunning()/setBeatsPerBar()
// → cola de comandos → hilo de audio → TempoClock → AudioEngine::pulseSnapshot().
// Es lo que el indicador visual de pulso de la UI lee (vía JNI).
//
// ALCANCE (declarado): lógica propia del motor. No mide la latencia de salida
// real del dispositivo ni prueba Oboe (ver oboe_fake.cpp).
#include "AudioEngine.h"
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <thread>
#include <vector>

using eliner::AudioEngine;
namespace pulse = eliner::pulse;

// ── El formato empaquetado es un contrato con Kotlin: se fija en compilación ──
static_assert(pulse::sequenceOf(pulse::pack(123456789ull, 7, true)) == 123456789ull);
static_assert(pulse::beatInBarOf(pulse::pack(5, 15, false)) == 15);
static_assert(pulse::runningOf(pulse::pack(5, 0, true)));
static_assert(!pulse::runningOf(pulse::pack(5, 15, false)));
static_assert(pulse::pack(0, 0, false) == 0ull);
// Con un tiempo de compás máximo (15) y running activo no se pisan los campos.
static_assert(pulse::pack(1, 15, true) == ((1ull << 16) | (1ull << 8) | 15ull));
// Un jlong es con signo: el valor debe seguir siendo positivo tras años de pulsos.
// La secuencia máxima segura es 2^47 - 1 (a 300 BPM, 5 pulsos/s: ~9e5 años).
static_assert((pulse::pack((1ull << 47) - 1, 15, true) >> 63) == 0ull);

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;

struct Rig {
    std::unique_ptr<AudioEngine> e = std::make_unique<AudioEngine>();
    bool started;
    std::vector<float> buf = std::vector<float>(2 * 4096, 0.0f);
    Rig() { started = e->start(0); }
    ~Rig() { e->stop(); }
    void render(int frames, int blk = 480) {
        for (int pos = 0; pos < frames; pos += blk)
            e->onAudioReady(nullptr, buf.data(), std::min(blk, frames - pos));
    }
};
} // namespace

int main() {
    // ── 1. Reposo: nada publicado antes de arrancar el transporte ──
    {
        Rig r; check(r.started, "el motor arranca contra el backend falso");
        r.render(kSR);
        check(r.e->pulseSnapshot() == 0, "sin transporte en marcha no se publica nada (valor 0)");
    }

    // ── 2. 4/4 a 120 BPM: un pulso cada 24000 frames; en 3 s caen 6 (0,24k,…,120k) ──
    {
        Rig r;
        r.e->setTempo(120.0f);
        r.e->setBeatsPerBar(4);
        r.e->setTransportRunning(true);
        r.render(3 * kSR);
        const auto p = r.e->pulseSnapshot();
        check(pulse::runningOf(p), "en marcha: bit running = 1");
        check(pulse::sequenceOf(p) == 6, "3 s a 120 BPM publican exactamente 6 pulsos");
        check(pulse::beatInBarOf(p) == 1, "el 6.º pulso es el tiempo 2 del compás (índice 1: 5 mod 4)");
    }

    // ── 3. Compás de 1 tiempo: beatInBar no cambia pero la secuencia SÍ ──
    {
        Rig r;
        r.e->setBeatsPerBar(1);
        r.e->setTransportRunning(true);
        r.render(kSR);                       // 2 pulsos a 120 BPM (0 y 24000)
        const auto a = r.e->pulseSnapshot();
        r.render(kSR);                       // 2 más
        const auto b = r.e->pulseSnapshot();
        check(pulse::beatInBarOf(a) == 0 && pulse::beatInBarOf(b) == 0, "1 tiempo por compás: siempre tiempo 0");
        check(pulse::sequenceOf(b) == pulse::sequenceOf(a) + 2, "…pero la secuencia avanza en cada pulso");
    }

    // ── 4. Parar publica 'no running' y conserva la secuencia; arrancar vuelve al tiempo 0 ──
    {
        Rig r;
        r.e->setBeatsPerBar(4);
        r.e->setTransportRunning(true);
        r.render(kSR + 100);                 // pulsos en 0, 24000 y 48000 → secuencia 3
        const auto beforeStop = r.e->pulseSnapshot();
        r.e->setTransportRunning(false);
        r.render(480);                       // el comando se aplica en el siguiente bloque
        const auto stopped = r.e->pulseSnapshot();
        check(!pulse::runningOf(stopped), "tras parar: bit running = 0");
        check(pulse::sequenceOf(stopped) == pulse::sequenceOf(beforeStop), "parar no altera la secuencia");
        r.render(2 * kSR);
        check(r.e->pulseSnapshot() == stopped, "parado, el valor no cambia aunque siga el audio");
        r.e->setTransportRunning(true);
        r.render(480);
        const auto restarted = r.e->pulseSnapshot();
        check(pulse::runningOf(restarted) && pulse::beatInBarOf(restarted) == 0,
              "rearrancar publica el tiempo 0 (primer tiempo del compás)");
        check(pulse::sequenceOf(restarted) == pulse::sequenceOf(stopped) + 1,
              "la secuencia continúa (un pulso nuevo), no se reinicia");
    }

    // ── 5. Lectura concurrente (TSan): un lector no ve nunca la secuencia retroceder ──
    {
        Rig r;
        r.e->setTempo(300.0f);               // pulsos densos: más escrituras durante la lectura
        r.e->setTransportRunning(true);
        std::atomic<bool> stop{false};
        std::atomic<bool> monotonic{true};
        std::atomic<long> reads{0};
        std::thread reader([&] {
            std::uint64_t last = 0;
            while (!stop.load(std::memory_order_relaxed)) {
                const auto s = pulse::sequenceOf(r.e->pulseSnapshot());
                if (s < last) monotonic.store(false);
                last = s;
                reads.fetch_add(1, std::memory_order_relaxed);
            }
        });
        // El render dura milisegundos: sin esta espera podría terminar antes de que el lector
        // arranque y la prueba no ejercitaría nada concurrente. Se espera a su primera lectura.
        while (reads.load(std::memory_order_relaxed) == 0) std::this_thread::yield();
        r.render(20 * kSR, 256);
        stop.store(true);
        reader.join();
        check(monotonic.load(), "lector concurrente: la secuencia nunca retrocede");
        check(reads.load() > 0, "el lector concurrente estaba activo durante el render");
        check(pulse::sequenceOf(r.e->pulseSnapshot()) >= 100, "a 300 BPM, 20 s publican >= 100 pulsos");
    }

    std::fprintf(stdout, gFailures ? "== %d FALLO(S) ==\n" : "== TODO OK ==\n", gFailures);
    return gFailures ? 1 : 0;
}
