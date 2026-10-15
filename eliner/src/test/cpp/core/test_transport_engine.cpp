// TransportEngine — unidad aislada (sin AudioEngine, sin Oboe, sin cola de comandos).
//
// Antes de extraerlo, reloj + metrónomo + pulso + estado del sync del delay solo
// se podían probar a través del motor completo. Ahora se prueba el contrato propio:
// avance por bloques, pulso publicado, click en el bus, y el CÁLCULO del tiempo del
// delay (aplicarlo al módulo es cosa de AudioEngine y lo cubre test_engine_transport_integration).
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "TransportEngine.h"

using eliner::TransportEngine;
namespace pulse = eliner::pulse;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;

// Avanza [frames] en bloques de [blk] y devuelve el total de pulsos vistos.
int run(TransportEngine& t, int frames, int blk = 480) {
    int beats = 0;
    for (int pos = 0; pos < frames; pos += blk) beats += t.advance(std::min(blk, frames - pos));
    return beats;
}
} // namespace

int main() {
    // ── Reposo ──
    {
        TransportEngine t; t.prepare(kSR);
        check(!t.isRunning(), "recién creado: parado");
        check(run(t, kSR) == 0, "parado no emite pulsos");
        check(t.pulseSnapshot() == 0, "parado desde el inicio: pulso publicado = 0");
    }

    // ── Avance y publicación ──
    {
        TransportEngine t; t.prepare(kSR);
        t.setTempo(120.0); t.setBeatsPerBar(4); t.setRunning(true);
        check(t.isRunning(), "setRunning(true) arranca el reloj");
        const int beats = run(t, 3 * kSR);   // 120 BPM → pulso cada 24000 frames; 0,24k,…,120k
        check(beats == 6, "3 s a 120 BPM: 6 pulsos");
        const auto p = t.pulseSnapshot();
        check(pulse::sequenceOf(p) == 6 && pulse::beatInBarOf(p) == 1 && pulse::runningOf(p),
              "el pulso publicado refleja secuencia 6, tiempo 2 del compás, en marcha");
    }

    // ── Parada: publica running=0 conservando la secuencia ──
    {
        TransportEngine t; t.prepare(kSR);
        t.setRunning(true); (void)run(t, kSR);
        const auto before = t.pulseSnapshot();
        t.setRunning(false);
        const auto after = t.pulseSnapshot();
        check(!pulse::runningOf(after) && pulse::sequenceOf(after) == pulse::sequenceOf(before),
              "parar publica running=0 y conserva la secuencia");
        check(run(t, kSR) == 0 && t.pulseSnapshot() == after, "parado: sin pulsos y valor estable");
    }

    // ── Cambio de tempo en marcha: sin pulsos repetidos ni omitidos (fase continua) ──
    {
        TransportEngine t; t.prepare(kSR);
        t.setTempo(120.0); t.setRunning(true);
        int beats = run(t, kSR / 2 + 100);          // pulsos en 0 y 24000 → 2
        t.setTempo(240.0);                           // el pulso en curso se reescala
        beats += run(t, kSR);
        check(beats > 2 && beats <= 2 + 8, "cambiar el tempo en marcha sigue produciendo pulsos coherentes");
    }

    // ── Delay sincronizado: SOLO estado y cálculo ──
    {
        TransportEngine t; t.prepare(kSR);
        check(t.delaySyncEnabled(), "el sync del delay está activo por defecto (compatibilidad con el motor anterior)");
        t.setTempo(120.0);
        check(std::fabs(t.delaySyncSeconds(0.01, 1.99) - 0.375) < 1e-9,
              "por defecto a 120 BPM: corchea con puntillo = 0,375 s (el tiempo histórico del Delay)");
        t.setTempo(60.0);
        check(std::fabs(t.delaySyncSeconds(0.01, 1.99) - 0.75) < 1e-9, "a 60 BPM la duración se duplica");
        t.setDelaySyncBeats(1.0f); t.setTempo(120.0);
        check(std::fabs(t.delaySyncSeconds(0.01, 1.99) - 0.5) < 1e-9, "una negra a 120 BPM = 0,5 s");
        t.setTempo(20.0); t.setDelaySyncBeats(4.0f);   // 12 s: no cabe → se pliega por octavas
        const double folded = t.delaySyncSeconds(0.01, 1.99);
        check(folded >= 0.01 && folded <= 1.99, "una duración que no cabe se pliega al rango del delay");
        t.setDelaySyncEnabled(false);
        check(!t.delaySyncEnabled(), "setDelaySyncEnabled(false) desactiva el sync");
    }

    // ── Click: bus propio, solo con metrónomo activo y transporte en marcha ──
    {
        auto energy = [](bool enabled) {
            TransportEngine t; t.prepare(kSR);
            t.setMetronomeEnabled(enabled); t.setMetronomeVolume(1.0f);
            t.setTempo(120.0); t.setRunning(true);
            std::vector<float> buf(2 * 480, 0.0f);
            double e = 0.0;
            for (int i = 0; i < 20; ++i) {
                std::fill(buf.begin(), buf.end(), 0.0f);
                t.advance(480);
                t.renderClick(buf.data(), 480);
                for (float v : buf) e += std::fabs(v);
            }
            return e;
        };
        check(energy(true) > 1.0, "metrónomo activo: el primer pulso produce click audible");
        check(energy(false) == 0.0, "metrónomo apagado: silencio absoluto, aunque el reloj corra");
    }

    std::fprintf(stdout, gFailures ? "== %d FALLO(S) ==\n" : "== TODO OK ==\n", gFailures);
    return gFailures ? 1 : 0;
}
