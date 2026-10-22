// ADR 0028 — Auditoría: pruebas de PROPIEDADES con oráculo independiente y fuzzing.
//
// Complementa a test_tempo_clock / test_metronome / test_delay_tempo_glide (casos
// escritos a mano) con entradas ALEATORIAS pero deterministas (semilla fija):
//   P1  TempoClock vs un oráculo INDEPENDIENTE (fase musical integrada por tramos
//       en long double): mismas posiciones de pulso con cambios de tempo aleatorios,
//       tamaños de bloque de 1 a 9000 frames y frecuencias de 8 kHz a 192 kHz.
//   P2  TempoClock: invariantes estructurales con start/stop/compás/prepare al azar.
//   P3  Metronome: salida siempre finita y acotada con operaciones aleatorias.
//   P4  Delay: salida finita y acotada con tiempo/feedback/mix aleatorios (el filtro de
//       deslizamiento no puede hacer inestable el bucle de realimentación).
// Cualquier fallo imprime la semilla y la iteración para reproducirlo.
#include "Delay.h"
#include "Metronome.h"
#include "TempoClock.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <random>
#include <vector>

using eliner::Delay;
using eliner::Metronome;
using eliner::TempoClock;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
bool finite(float x) { return std::isfinite(x); }
}

int main() {
    // ── P1. TempoClock contra un oráculo independiente ──
    {
        const int sampleRates[] = {8000, 22050, 44100, 48000, 96000, 192000};
        long scenarios = 0, beatsCompared = 0; double worst = 0.0; bool allOk = true;
        for (int sr : sampleRates) {
            for (unsigned seed = 1; seed <= 40; ++seed) {
                std::mt19937 rng(seed * 7919u + static_cast<unsigned>(sr));
                std::uniform_real_distribution<double> bpmDist(20.0, 300.0);
                std::uniform_int_distribution<int> blockDist(1, 9000);
                std::uniform_int_distribution<int> opDist(0, 99);

                TempoClock c; c.prepare(sr);
                double bpm = bpmDist(rng); c.setTempo(bpm); c.start();

                // Oráculo: posición musical continua b(t) en pulsos; dB/dt = bpm / (60·sr) por frame.
                // Los pulsos caen cuando b cruza un entero. Se integra por tramos y se guardan
                // los instantes (en frames, long double) de cada cruce.
                long double b = 0.0L;                    // pulsos acumulados (el pulso 0 está en t=0)
                long double oracleT = 0.0L;              // frames absolutos hasta donde se ha integrado
                long double nextBeatIdx = 0.0L;          // próximo entero a cruzar
                std::vector<long double> oracleBeats; oracleBeats.push_back(0.0L); nextBeatIdx = 1.0L;
                auto integrate = [&](long double frames, double curBpm) {
                    const long double rate = static_cast<long double>(curBpm) / (60.0L * sr);   // pulsos por frame
                    long double remaining = frames;
                    while (remaining > 0) {
                        const long double toNext = (nextBeatIdx - b) / rate;                     // frames hasta el próximo cruce
                        if (toNext <= remaining) {
                            oracleT += toNext; b = nextBeatIdx; remaining -= toNext;
                            oracleBeats.push_back(oracleT); nextBeatIdx += 1.0L;
                        } else { b += rate * remaining; oracleT += remaining; remaining = 0; }
                    }
                };

                std::vector<double> got;                 // pulsos del reloj real (frames absolutos, como double)
                std::int64_t pos = 0;
                for (int blocks = 0; blocks < 300; ++blocks) {
                    if (opDist(rng) < 30) {              // 30 %: cambio de tempo entre bloques
                        const double nb = bpmDist(rng);
                        c.setTempo(nb); bpm = nb;
                    }
                    const int n = blockDist(rng);
                    TempoClock::Beat ev[TempoClock::kMaxBeatsPerBlock];
                    const int cnt = c.advance(n, ev, TempoClock::kMaxBeatsPerBlock);
                    for (int i = 0; i < cnt; ++i) got.push_back(static_cast<double>(pos + ev[i].frameOffset));
                    integrate(static_cast<long double>(n), bpm);
                    pos += n;
                }
                // comparar: cada pulso del reloj debe ser floor(pulso del oráculo) ±1 frame, y los conteos coinciden
                // (los del oráculo con t < pos)
                std::size_t expectedCount = 0; for (auto t : oracleBeats) if (t < static_cast<long double>(pos)) ++expectedCount;
                if (got.size() != expectedCount) { allOk = false; std::fprintf(stderr, "  P1 conteo: sr=%d seed=%u got=%zu oracle=%zu\n", sr, seed, got.size(), expectedCount); }
                for (std::size_t i = 0; i < got.size() && i < oracleBeats.size(); ++i) {
                    const double d = std::fabs(got[i] - static_cast<double>(std::floor(oracleBeats[i])));
                    if (d > worst) worst = d;
                    if (d > 1.0) { allOk = false; std::fprintf(stderr, "  P1 posición: sr=%d seed=%u beat=%zu got=%.0f oracle=%.3Lf\n", sr, seed, i, got[i], oracleBeats[i]); break; }
                    ++beatsCompared;
                }
                ++scenarios;
            }
        }
        std::fprintf(stdout, "       (%ld escenarios, %ld pulsos comparados, desviación máxima %.0f frame)\n", scenarios, beatsCompared, worst);
        check(allOk, "P1. TempoClock coincide con el oráculo independiente (±1 frame) con tempo, bloque y frecuencia aleatorios");
    }

    // ── P2. Invariantes estructurales con operaciones aleatorias ──
    {
        bool ok = true; long events = 0;
        for (unsigned seed = 1; seed <= 200 && ok; ++seed) {
            std::mt19937 rng(seed);
            std::uniform_int_distribution<int> op(0, 9), blk(1, 6000), bar(-5, 40), srd(0, 5);
            std::uniform_real_distribution<double> bpm(-50.0, 400.0);
            const int srs[] = {8000, 22050, 44100, 48000, 96000, 192000};
            TempoClock c; c.prepare(srs[srd(rng)]);
            std::int64_t pos = 0, last = -1; bool lastValid = false;
            for (int i = 0; i < 400 && ok; ++i) {
                switch (op(rng)) {
                    case 0: c.start(); break;
                    case 1: c.stop(); lastValid = false; break;
                    case 2: c.setTempo(bpm(rng)); break;
                    case 3: c.setBeatsPerBar(bar(rng)); break;
                    case 4: c.prepare(srs[srd(rng)]); break;
                    default: break;
                }
                const int n = blk(rng);
                TempoClock::Beat ev[TempoClock::kMaxBeatsPerBlock];
                const int cnt = c.advance(n, ev, TempoClock::kMaxBeatsPerBlock);
                if (!c.isRunning() && cnt != 0) ok = false;
                if (cnt < 0 || cnt > TempoClock::kMaxBeatsPerBlock) ok = false;
                for (int k = 0; k < cnt && ok; ++k) {
                    const std::int64_t abs = pos + ev[k].frameOffset;
                    if (ev[k].frameOffset < 0 || ev[k].frameOffset >= n) ok = false;                       // dentro del bloque
                    if (ev[k].beatInBar >= static_cast<std::uint32_t>(c.beatsPerBar())) ok = false;       // compás válido
                    if (lastValid && abs <= last) ok = false;                                               // estrictamente creciente
                    last = abs; lastValid = true; ++events;
                }
                if (c.tempo() < 20.0 || c.tempo() > 300.0) ok = false;                                     // tempo siempre acotado
                pos += n;
            }
            if (!ok) std::fprintf(stderr, "  P2 fallo: seed=%u\n", seed);
        }
        std::fprintf(stdout, "       (%ld pulsos verificados)\n", events);
        check(ok, "P2. TempoClock: 200 secuencias aleatorias de start/stop/tempo/compás/prepare respetan todos los invariantes");
    }

    // ── P3. Metronome: salida finita y acotada con operaciones aleatorias ──
    {
        bool ok = true; float maxAbs = 0.f;
        for (unsigned seed = 1; seed <= 100 && ok; ++seed) {
            std::mt19937 rng(seed * 31u);
            std::uniform_int_distribution<int> op(0, 5), blk(1, 4000), srd(0, 5);
            std::uniform_real_distribution<float> vol(-1.f, 2.f);
            std::uniform_real_distribution<double> bpm(20.0, 300.0);
            const int srs[] = {8000, 22050, 44100, 48000, 96000, 192000};
            const int sr = srs[srd(rng)];
            TempoClock c; c.prepare(sr); c.setTempo(bpm(rng)); c.start();
            Metronome m; m.prepare(sr); m.setVolume(1.0f);
            for (int i = 0; i < 300 && ok; ++i) {
                switch (op(rng)) {
                    case 0: m.setEnabled(true); break;
                    case 1: m.setEnabled(false); break;
                    case 2: m.setVolume(vol(rng)); break;
                    case 3: c.setTempo(bpm(rng)); break;
                    case 4: c.setBeatsPerBar(1 + static_cast<int>(rng() % 16)); break;
                    default: break;
                }
                const int n = blk(rng);
                std::vector<float> buf(2 * static_cast<std::size_t>(n), 0.0f);
                TempoClock::Beat ev[TempoClock::kMaxBeatsPerBlock];
                const int cnt = c.advance(n, ev, TempoClock::kMaxBeatsPerBlock);
                m.render(buf.data(), n, ev, cnt);
                for (float v : buf) { if (!finite(v)) ok = false; maxAbs = std::fmax(maxAbs, std::fabs(v)); }
            }
            if (!ok) std::fprintf(stderr, "  P3 fallo: seed=%u\n", seed);
        }
        std::fprintf(stdout, "       (amplitud máxima observada: %.3f)\n", maxAbs);
        check(ok && maxAbs <= 0.8f + 1e-4f, "P3. Metronome: salida siempre finita y ≤ 0.8 (margen bajo 0 dBFS) con operaciones aleatorias");
    }

    // ── P4. Delay: finito y acotado con parámetros aleatorios ──
    {
        bool ok = true; float maxAbs = 0.f;
        for (unsigned seed = 1; seed <= 60 && ok; ++seed) {
            std::mt19937 rng(seed * 101u);
            std::uniform_int_distribution<int> srd(0, 3), blk(1, 3000), op(0, 4);
            std::uniform_real_distribution<float> t(0.0f, 2.2f), fb(0.0f, 1.2f), mix(0.0f, 1.0f), x(-0.8f, 0.8f);
            const int srs[] = {8000, 22050, 44100, 48000};
            Delay d(srs[srd(rng)]); d.setMix(1.0f); d.setFeedback(0.95f);
            for (int i = 0; i < 200 && ok; ++i) {
                switch (op(rng)) {
                    case 0: d.setTime(t(rng)); break;
                    case 1: d.setFeedback(fb(rng)); break;
                    case 2: d.setMix(mix(rng)); break;
                    default: break;
                }
                const int n = blk(rng);
                std::vector<float> buf(2 * static_cast<std::size_t>(n));
                for (auto& v : buf) v = x(rng);
                d.process(buf.data(), n);
                for (float v : buf) { if (!finite(v)) ok = false; maxAbs = std::fmax(maxAbs, std::fabs(v)); }
                // la longitud efectiva nunca sale de [1, tamaño-2]
                if (d.currentLengthSamples() < 1.0 || d.currentLengthSamples() > 2.0 * srs[0] * 12.0) ok = false;
            }
            if (!ok) std::fprintf(stderr, "  P4 fallo: seed=%u\n", seed);
        }
        std::fprintf(stdout, "       (amplitud máxima observada: %.2f con feedback hasta 0.95)\n", maxAbs);
        // cota teórica: entrada ≤0.8, ganancia del lazo ≤0.95 (interpolación lineal = combinación convexa)
        // ⇒ |wet| ≤ 0.8/(1-0.95) = 16; salida = dry + 0.7·wet ≤ 0.8 + 11.2 = 12
        check(ok && maxAbs <= 12.5f, "P4. Delay: salida siempre finita y por debajo de la cota teórica (12) con tiempo, feedback y mix aleatorios");
    }

    // ── P4b. El límite de feedback protege de la inestabilidad ──
    {
        // setFeedback acepta cualquier float; si no se limitara a 0.95, un valor ≥1 haría crecer el eco sin límite.
        bool ok = true; float maxAbs = 0.f;
        for (float requested : {1.0f, 1.5f, 10.0f, 1e6f}) {
            Delay d(8000); d.setMix(1.0f); d.setTime(0.02f); d.setFeedback(requested);
            std::mt19937 rng(77);
            std::uniform_real_distribution<float> x(-0.8f, 0.8f);
            std::vector<float> buf(2 * 480);
            for (int blk = 0; blk < 8000 / 480 * 60; ++blk) {            // 60 s de ruido a 8 kHz
                for (auto& v : buf) v = x(rng);
                d.process(buf.data(), 480);
                for (float v : buf) { if (!finite(v)) ok = false; maxAbs = std::fmax(maxAbs, std::fabs(v)); }
            }
        }
        std::fprintf(stdout, "       (60 s con feedback pedido 1…1e6: amplitud máxima %.2f)\n", maxAbs);
        check(ok && maxAbs <= 12.5f, "P4b. feedback pedido ≥ 1 se limita a 0.95: 60 s de ruido nunca superan la cota teórica (12)");
    }

    // ── P4c. Extremo del buffer a varias frecuencias de muestreo ──
    {
        bool ok = true;
        for (int sr : {8000, 11025, 22050, 44100, 48000}) {
            Delay d(sr); d.setMix(1.0f); d.setFeedback(0.9f); d.setTime(99.0f);
            const long frames = static_cast<long>(sr) * 16;                // el glissando completo tarda <9 s a cualquier frecuencia
            std::mt19937 rng(static_cast<unsigned>(sr));
            std::uniform_real_distribution<float> x(-0.8f, 0.8f);
            std::vector<float> buf(2 * 512);
            for (long pos = 0; pos < frames; pos += 512) {
                for (auto& v : buf) v = x(rng);
                d.process(buf.data(), 512);
                for (float v : buf) if (!finite(v)) ok = false;
            }
            if (d.currentLengthSamples() != d.targetLengthSamples() || d.targetLengthSamples() != static_cast<double>(2 * sr - 2)) {
                ok = false; std::fprintf(stderr, "  P4c: sr=%d no alcanzó el extremo (cur=%.1f tgt=%.1f)\n", sr, d.currentLengthSamples(), d.targetLengthSamples());
            }
        }
        check(ok, "P4c. a 8/11.025/22.05/44.1/48 kHz el delay alcanza 2 s − 2 muestras y procesa sobre todo el buffer sin accesos fuera de rango (ASan/UBSan)");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
