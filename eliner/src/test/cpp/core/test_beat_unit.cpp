// ADR 0032 — Unidad de pulso (denominador del compás) en el reloj de transporte.
//
// El TEMPO sigue en negras por minuto; la unidad solo cambia cuánto dura un PULSO. Se prueba el
// TempoClock y el TransportEngine REALES (sin AudioEngine ni Oboe), sobre la salida de frames.
#include "BeatUnit.h"
#include "ClickSound.h"
#include "TempoClock.h"
#include "TransportEngine.h"
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include <cstdio>
#include <vector>

using eliner::TempoClock;
using eliner::TransportEngine;

// El contrato del vocabulario se fija en COMPILACIÓN.
static_assert(eliner::isValidBeatUnit(2) && eliner::isValidBeatUnit(4) && eliner::isValidBeatUnit(8) && eliner::isValidBeatUnit(16));
static_assert(!eliner::isValidBeatUnit(0) && !eliner::isValidBeatUnit(1) && !eliner::isValidBeatUnit(3) && !eliner::isValidBeatUnit(32));
static_assert(eliner::quartersPerPulse(4) == 1.0 && eliner::quartersPerPulse(8) == 0.5 && eliner::quartersPerPulse(16) == 0.25 && eliner::quartersPerPulse(2) == 2.0);
// Empaquetado del patrón: 2 bits por tiempo, 8 tiempos por palabra.
static_assert(eliner::beatLevelOf(0x0003u, 0u, 0) == 3 && eliner::beatLevelOf(0x000Cu, 0u, 1) == 3);
static_assert(eliner::beatLevelOf(0u, 0x0003u, 8) == 3 && eliner::beatLevelOf(0u, 0xC000u, 15) == 3);
static_assert(eliner::beatLevelOf(0xFFFFu, 0u, 8) == 0, "las palabras no se mezclan");
static_assert(eliner::kMaxPatternBeats == 16);

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;

// Frames (absolutos) de todos los pulsos al renderizar [frames] en bloques de [blk].
std::vector<long> pulses(TempoClock& c, int frames, int blk = 480, std::vector<unsigned>* inBar = nullptr) {
    std::vector<long> out; TempoClock::Beat b[TempoClock::kMaxBeatsPerBlock];
    for (int pos = 0; pos < frames; pos += blk) {
        const int n = c.advance(std::min(blk, frames - pos), b, TempoClock::kMaxBeatsPerBlock);
        for (int i = 0; i < n; ++i) { out.push_back(pos + b[i].frameOffset); if (inBar) inBar->push_back(b[i].beatInBar); }
    }
    return out;
}
} // namespace

int main() {
    // ── Duración del pulso: negra 24000 frames a 120 BPM; corchea 12000; semicorchea 6000; blanca 48000 ──
    for (auto [den, spacing] : {std::pair<int, long>{4, 24000}, {8, 12000}, {16, 6000}, {2, 48000}}) {
        TempoClock c; c.prepare(kSR); c.setTempo(120.0); c.setQuartersPerPulse(eliner::quartersPerPulse(den)); c.start();
        const auto p = pulses(c, 2 * kSR);
        bool even = p.size() >= 2;
        for (size_t i = 1; i < p.size(); ++i) if (std::labs((p[i] - p[i - 1]) - spacing) > 1) even = false;
        char msg[96]; std::snprintf(msg, sizeof msg, "unidad 1/%d: un pulso cada %ld frames a 120 BPM (±1)", den, spacing);
        check(even, msg);
    }

    // ── 6/8: seis pulsos de corchea por compás; el compás se completa cada 6 pulsos ──
    {
        TempoClock c; c.prepare(kSR); c.setTempo(120.0); c.setBeatsPerBar(6); c.setQuartersPerPulse(0.5); c.start();
        std::vector<unsigned> bar; const auto p = pulses(c, 2 * kSR, 480, &bar);   // 8 pulsos en 2 s
        check(p.size() == 8, "6/8 a 120 BPM (negra): 8 pulsos de corchea en 2 s");
        check(bar.size() == 8 && bar[0] == 0 && bar[5] == 5 && bar[6] == 0 && bar[7] == 1, "el compás de 6 pulsos se reinicia al 7.º pulso");
    }

    // ── Cambiar la unidad EN MARCHA mantiene la fase: ni pulsos repetidos ni omitidos ──
    {
        TempoClock c; c.prepare(kSR); c.setTempo(120.0); c.start();
        auto p1 = pulses(c, 12000 + 100);                // pulsos en 0 y 24000? → solo 0 (el siguiente a 24000)
        c.setQuartersPerPulse(0.5);                       // el tiempo restante (≈ 11900) se reescala a la mitad
        auto p2 = pulses(c, 24000);
        check(p1.size() == 1 && !p2.empty(), "un solo pulso antes del cambio");
        // El siguiente pulso llega ~ (24000-12100)/2 ≈ 5950 frames tras el cambio, no 11900.
        check(p2[0] > 5800 && p2[0] < 6100, "al cambiar a corchea el pulso en curso se acorta proporcionalmente (fase continua)");
    }

    // ── Valores inválidos: se IGNORAN, no se recortan ──
    {
        TempoClock c; c.prepare(kSR); c.setQuartersPerPulse(0.5);
        c.setQuartersPerPulse(0.0); c.setQuartersPerPulse(-1.0); c.setQuartersPerPulse(100.0); c.setQuartersPerPulse(NAN); c.setQuartersPerPulse(0.1);
        check(c.quartersPerPulse() == 0.5, "unidades fuera de [0,25, 2] o no finitas se ignoran");
    }

    // ── TransportEngine: la unidad NO altera el cálculo del delay sincronizado (sigue en negras) ──
    {
        TransportEngine t; t.prepare(kSR); t.setTempo(120.0);
        const double base = t.delaySyncSeconds(0.01, 1.99);
        t.setBeatUnit(8);
        check(std::fabs(t.delaySyncSeconds(0.01, 1.99) - base) < 1e-12, "el delay sincronizado no depende de la unidad de pulso");
        t.setBeatUnit(7);   // inválido
        check(std::fabs(t.delaySyncSeconds(0.01, 1.99) - base) < 1e-12 && std::fabs(t.tempo() - 120.0) < 1e-12, "una unidad inválida no cambia nada");
    }

    std::fprintf(stdout, gFailures ? "== %d FALLO(S) ==\n" : "== TODO OK ==\n", gFailures);
    return gFailures ? 1 : 0;
}
