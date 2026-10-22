// ADR 0033 — "Sync" y "Mute" del tapper (ver manual oficial de FL Studio):
//   · Sync: cada toque reinicia el tiempo 1  →  TempoClock::restartBar / TransportEngine::resyncBar
//   · Mute: silenciar mientras se mide y volver a entrar gradualmente  →  Metronome::setDuck
// Se prueba el código REAL: reloj, metrónomo, TransportEngine y, de extremo a extremo, el AudioEngine real
// contra el backend falso (cola de comandos incluida).
#include "AudioEngine.h"
#include "Metronome.h"
#include "TempoClock.h"
#include "TransportEngine.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

using eliner::AudioEngine;
using eliner::Metronome;
using eliner::TempoClock;
using eliner::TransportEngine;
namespace pulse = eliner::pulse;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;

float peak(const std::vector<float>& b, int from, int to) {
    float p = 0.0f; for (int i = from; i < to; ++i) p = std::fmax(p, std::fabs(b[2 * i])); return p;
}
bool finite(const std::vector<float>& b) { for (float x : b) if (!std::isfinite(x)) return false; return true; }
} // namespace

int main() {
    // ── TempoClock::restartBar ──────────────────────────────────────────────
    {
        TempoClock c; c.prepare(kSR); c.setTempo(120.0); c.setBeatsPerBar(4); c.start();
        TempoClock::Beat b[TempoClock::kMaxBeatsPerBlock];
        // 3 pulsos (0, 24000, 48000) → el siguiente sería el tiempo 4 (índice 3) a los 72000.
        c.advance(60000, b, TempoClock::kMaxBeatsPerBlock);
        c.restartBar();
        const int n = c.advance(480, b, TempoClock::kMaxBeatsPerBlock);
        check(n == 1 && b[0].frameOffset == 0 && b[0].beatInBar == 0, "restartBar: el siguiente bloque empieza con un pulso en el tiempo 1, en su primer frame");
        const int m = c.advance(24000, b, TempoClock::kMaxBeatsPerBlock);
        check(m >= 1 && b[0].beatInBar == 1, "…y el compás continúa con el tiempo 2 un pulso después");
    }
    {
        TempoClock c; c.prepare(kSR); c.setTempo(120.0);
        c.restartBar();     // parado: no hace nada (no arranca el reloj)
        TempoClock::Beat b[TempoClock::kMaxBeatsPerBlock];
        check(!c.isRunning() && c.advance(48000, b, TempoClock::kMaxBeatsPerBlock) == 0, "restartBar con el reloj parado no lo arranca ni emite pulsos");
    }

    // ── TransportEngine::resyncBar publica el pulso nuevo ───────────────────
    {
        TransportEngine t; t.prepare(kSR); t.setTempo(120.0); t.setBeatsPerBar(4); t.setRunning(true);
        for (int i = 0; i < 100; ++i) t.advance(480);          // ~1 s: pulsos 0 y 24000 ya pasaron
        const auto before = pulse::sequenceOf(t.pulseSnapshot());
        t.resyncBar();
        t.advance(480);
        const auto p = t.pulseSnapshot();
        check(pulse::sequenceOf(p) == before + 1 && pulse::beatInBarOf(p) == 0 && pulse::runningOf(p),
              "resyncBar: el pulso publicado es un tiempo 1 nuevo (secuencia +1, beatInBar 0)");
    }

    // ── Metronome::setDuck ──────────────────────────────────────────────────
    {
        auto render = [](float duck) {
            Metronome m; m.prepare(kSR); m.setVolume(1.0f); m.setEnabled(true); m.setDuck(duck);
            std::vector<float> w(2 * 4800, 0.0f); m.render(w.data(), 4800, nullptr, 0);
            std::vector<float> o(2 * 4800, 0.0f); TempoClock::Beat b{0, 0}; m.render(o.data(), 4800, &b, 1, 24000.0);
            return o;
        };
        const float full = peak(render(1.0f), 0, 4800), half = peak(render(0.5f), 0, 4800), none = peak(render(0.0f), 0, 4800);
        check(none == 0.0f, "duck 0: el click queda mudo");
        check(std::fabs(half / full - 0.5f) < 0.02f, "duck 0,5: la mitad de amplitud (se multiplica con el volumen del usuario)");
        Metronome m; m.prepare(kSR); m.setDuck(0.3f); m.setDuck(NAN); m.setDuck(INFINITY);
        std::vector<float> o(2 * 4800, 0.0f); m.setVolume(1.0f); m.setEnabled(true);
        TempoClock::Beat b{0, 0}; m.render(o.data(), 4800, &b, 1, 24000.0);
        const float kept = peak(o, 0, 4800);
        Metronome r; r.prepare(kSR); r.setDuck(0.3f); r.setVolume(1.0f); r.setEnabled(true);
        std::vector<float> o2(2 * 4800, 0.0f); r.render(o2.data(), 4800, &b, 1, 24000.0);
        check(std::fabs(kept - peak(o2, 0, 4800)) < 1e-6f, "un duck no finito (NaN, ∞) se ignora y no altera el vigente");
        Metronome c; c.prepare(kSR); c.setVolume(1.0f); c.setEnabled(true); c.setDuck(5.0f);
        std::vector<float> o3(2 * 4800, 0.0f); c.render(o3.data(), 4800, &b, 1, 24000.0);
        check(std::fabs(peak(o3, 0, 4800) - full) < 1e-6f, "un duck > 1 se recorta a 1 (es una ganancia: nunca amplifica)");
    }
    {   // El duck se suaviza: subirlo gradualmente no produce chasquidos ni saltos (la diferencia entre
        // muestras consecutivas no supera la de un click normal).
        Metronome m; m.prepare(kSR); m.setVolume(1.0f); m.setEnabled(true); m.setDuck(0.0f);
        std::vector<float> o(2 * 48000, 0.0f);
        TempoClock::Beat b{0, 0};
        m.render(o.data(), 2400, &b, 1, 24000.0);
        m.setDuck(1.0f);
        TempoClock::Beat b2{0, 1};
        m.render(o.data() + 2 * 2400, 24000, &b2, 1, 24000.0);
        float maxStep = 0.0f;
        for (int i = 1; i < 26400; ++i) maxStep = std::fmax(maxStep, std::fabs(o[2 * i] - o[2 * (i - 1)]));
        check(finite(o) && maxStep < 0.25f, "subir el duck de 0 a 1 es suave: sin saltos bruscos entre muestras");
    }

    // ── De extremo a extremo por la cola de comandos ────────────────────────
    {
        auto e = std::make_unique<AudioEngine>();
        e->start(0); e->setTempo(120.0f); e->setBeatsPerBar(4);
        e->setMetronomeVolume(1.0f); e->setMetronomeEnabled(true); e->setTransportRunning(true);
        std::vector<float> buf(2 * 480); std::vector<float> all;
        auto render = [&](int frames) {
            for (int pos = 0; pos < frames; pos += 480) {
                std::fill(buf.begin(), buf.end(), 0.0f);
                e->onAudioReady(nullptr, buf.data(), 480);
                all.insert(all.end(), buf.begin(), buf.end());
            }
        };
        render(30000);                                   // el tiempo 1 en 0 y el 2 en 24000
        const auto seqBefore = pulse::sequenceOf(e->pulseSnapshot());
        e->resyncBar();
        render(480);
        const auto p = e->pulseSnapshot();
        check(pulse::sequenceOf(p) > seqBefore && pulse::beatInBarOf(p) == 0,
              "resyncBar por la cola de comandos: llega un tiempo 1 nuevo (en lugar del tiempo 3 que tocaba)");

        all.clear();
        e->setMetronomeDuck(0.0f);
        render(24000 * 2);                               // 2 pulsos con el click mudo (dejando apagar la cola del anterior)
        check(peak(all, 12000, 48000) == 0.0f, "setMetronomeDuck(0) por la cola: el click enmudece (tras suavizarse)");
        all.clear();
        e->setMetronomeDuck(1.0f);
        render(24000 * 2);
        check(peak(all, 0, 48000) > 0.1f, "setMetronomeDuck(1): el click vuelve a sonar");
        e->stop();
    }
    {   // Con el transporte parado, resyncBar no arranca nada.
        auto e = std::make_unique<AudioEngine>();
        e->start(0);
        e->resyncBar();
        std::vector<float> buf(2 * 480);
        for (int i = 0; i < 100; ++i) e->onAudioReady(nullptr, buf.data(), 480);
        check(pulse::sequenceOf(e->pulseSnapshot()) == 0 && !pulse::runningOf(e->pulseSnapshot()), "resyncBar con el transporte parado no lo arranca");
        e->stop();
    }

    std::fprintf(stdout, gFailures ? "== %d FALLO(S) ==\n" : "== TODO OK ==\n", gFailures);
    return gFailures ? 1 : 0;
}
