// ADR 0031 — Los ajustes del click llegan al SONIDO por el camino real:
//   AudioEngine::setMetronome*() → cola SPSC → hilo de audio → TransportEngine → Metronome.
// AudioEngine REAL contra el backend de audio FALSO (ver oboe_fake.cpp). Con el metrónomo como único
// generador de señal, la salida es solo el click: se mide la señal, no el estado interno.
#include "AudioEngine.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

using eliner::AudioEngine;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;

struct Rig {
    std::unique_ptr<AudioEngine> e = std::make_unique<AudioEngine>();
    std::vector<float> all;
    Rig() {
        e->start(0);
        e->setTempo(120.0f);
        e->setBeatsPerBar(4);
        e->setMetronomeVolume(1.0f);
        e->setMetronomeEnabled(true);
    }
    ~Rig() { e->stop(); }
    // Renderiza [frames] y acumula la salida (estéreo intercalada).
    void render(int frames, int blk = 480) {
        std::vector<float> b(2 * 4096);
        for (int pos = 0; pos < frames; pos += blk) {
            const int n = std::min(blk, frames - pos);
            std::fill(b.begin(), b.begin() + 2 * n, 0.0f);
            e->onAudioReady(nullptr, b.data(), n);
            all.insert(all.end(), b.begin(), b.begin() + 2 * n);
        }
    }
    int clicks(int gap = 300) const {
        int c = 0, quiet = gap + 1;
        for (size_t i = 0; i < all.size() / 2; ++i) {
            if (std::fabs(all[2 * i]) > 1e-4f) { if (quiet > gap) ++c; quiet = 0; } else ++quiet;
        }
        return c;
    }
    float energy() const { double s = 0; for (float x : all) s += double(x) * x; return float(s); }
};
} // namespace

int main() {
    // 2 s a 120 BPM = 4 pulsos (0, 0.5, 1.0, 1.5 s).
    { Rig r; r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 4, "referencia: 4 pulsos en 2 s a 120 BPM con subdivisión 1"); }

    { Rig r; r.e->setMetronomeSubdivision(2); r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 8, "subdivisión 2 (corcheas) por la cola de comandos: 8 clicks en 2 s"); }

    { Rig r; r.e->setMetronomeSubdivision(4); r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 16, "subdivisión 4 (semicorcheas): 16 clicks en 2 s"); }

    // Valores inválidos: no llegan a la cola ni cambian nada.
    { Rig r; r.e->setMetronomeSubdivision(0); r.e->setMetronomeSubdivision(9); r.e->setMetronomeSound(-1); r.e->setMetronomeSound(99);
      r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 4, "subdivisión o sonido inválidos se ignoran: sigue habiendo 4 pulsos y el sonido original"); }

    // Sonidos: cada uno cambia la señal respecto a Clásico.
    { Rig base; base.e->setTransportRunning(true); base.render(kSR);
      bool allDiffer = true;
      for (int s = 1; s < 5; ++s) {
          Rig r; r.e->setMetronomeSound(s); r.e->setTransportRunning(true); r.render(kSR);
          if (r.all == base.all) allDiffer = false;
      }
      check(allDiffer, "los sonidos 1–4 producen una señal distinta de Clásico a través del motor"); }

    // Patrón: "todos iguales" (sin acento) por la cola de comandos: el primer tiempo no destaca.
    { Rig r; r.e->setMetronomeBeatLevels(0xAAAAu, 0xAAAAu); r.e->setTransportRunning(true); r.render(2 * kSR);
      auto pk = [&](int from) { float p = 0; for (int i = from; i < from + 2400; ++i) p = std::fmax(p, std::fabs(r.all[2 * i])); return p; };
      check(std::fabs(pk(0) - pk(24000)) < 1e-3f, "patrón todos-iguales por la cola: el primer tiempo suena como el resto"); }

    // Patrón con un tiempo mudo: 3 pulsos audibles en 2 s (el 1.º, mudo, no suena) — atómico, un solo comando.
    { Rig r; r.e->setMetronomeBeatLevels(0xAAAAu & ~0x3u, 0xAAAAu);   // tiempo 0 → Mute
      r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 3, "un tiempo en Mute por la cola de comandos: 3 clicks en 2 s en lugar de 4"); }

    // Patrón inválido: no se encola ni cambia el sonido.
    { Rig r; r.e->setMetronomeBeatLevels(0x10000u, 0u); r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 4, "un patrón con palabra > 16 bits se ignora: siguen los 4 pulsos"); }

    // Unidad de pulso (ADR 0032): corchea = pulso a la mitad → el doble de pulsos con el mismo tempo.
    { Rig r; r.e->setBeatUnit(8); r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 8, "unidad de corchea (x/8): 8 pulsos en 2 s a 120 BPM"); }
    { Rig r; r.e->setBeatUnit(16); r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 16, "unidad de semicorchea (x/16): 16 pulsos en 2 s"); }
    { Rig r; r.e->setBeatUnit(2); r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 2, "unidad de blanca (x/2): 2 pulsos en 2 s"); }
    { Rig r; r.e->setBeatUnit(3); r.e->setBeatUnit(0); r.e->setBeatUnit(32); r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 4, "una unidad inválida (3, 0, 32) se ignora: sigue la negra"); }
    // La subdivisión se mide en PULSOS: con unidad de corchea y 2 clicks por pulso, 16 clicks en 2 s.
    { Rig r; r.e->setBeatUnit(8); r.e->setMetronomeSubdivision(2); r.e->setTransportRunning(true); r.render(2 * kSR);
      check(r.clicks() == 16, "la subdivisión sigue la unidad de pulso: x/8 con 2 clicks por pulso = 16 en 2 s"); }

    // Parar: sin clicks sueltos aunque haya subdivisión.
    { Rig r; r.e->setMetronomeSubdivision(4); r.e->setTransportRunning(true); r.render(2000);
      r.e->setTransportRunning(false); r.render(kSR);
      // tras la cola del primer click (≈110 ms) no debe quedar nada
      float tail = 0; for (int i = 9000; i < kSR; ++i) tail = std::fmax(tail, std::fabs(r.all[2 * i]));
      check(tail == 0.0f, "tras parar el transporte no suena ningún click intermedio"); }

    std::fprintf(stdout, gFailures ? "== %d FALLO(S) ==\n" : "== TODO OK ==\n", gFailures);
    return gFailures ? 1 : 0;
}
