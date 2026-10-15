// ADR 0028 — TempoClock (reloj de transporte con precisión de muestra).
//
// Prueba el código de PRODUCCIÓN (TempoClock.cpp). Propiedades verificadas:
//   · el pulso cae en el FRAME exacto (posición absoluta k·framesPorPulso)
//   · independencia del tamaño de bloque (mismo resultado con cualquier bloque)
//   · sin deriva tras una hora de reloj
//   · fase continua al cambiar de tempo (sin pulsos repetidos ni omitidos)
//   · compás, start idempotente, stop/start, límites y entradas inválidas
#include "TempoClock.h"
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <limits>
#include <vector>

using eliner::TempoClock;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}

struct Hit { std::int64_t frame; std::uint32_t beatInBar; };

// Corre [totalFrames] con bloques de tamaño cíclico [blockSizes] y devuelve los
// pulsos con su posición ABSOLUTA.
std::vector<Hit> run(TempoClock& c, std::int64_t totalFrames, const std::vector<int>& blockSizes) {
    std::vector<Hit> hits;
    std::int64_t pos = 0; std::size_t bi = 0;
    TempoClock::Beat buf[TempoClock::kMaxBeatsPerBlock];
    while (pos < totalFrames) {
        int n = blockSizes[bi++ % blockSizes.size()];
        if (pos + n > totalFrames) n = static_cast<int>(totalFrames - pos);
        const int cnt = c.advance(n, buf, TempoClock::kMaxBeatsPerBlock);
        for (int i = 0; i < cnt; ++i) hits.push_back({pos + buf[i].frameOffset, buf[i].beatInBar});
        pos += n;
    }
    return hits;
}
}

int main() {
    // ── 1. Posición exacta, tempo entero (120 BPM @ 48 kHz = 24000 frames/pulso) ──
    {
        TempoClock c; c.prepare(48000); c.setTempo(120.0); c.start();
        auto hits = run(c, 480000, {480});
        bool exact = hits.size() == 20;
        for (std::size_t k = 0; k < hits.size(); ++k) exact = exact && hits[k].frame == static_cast<std::int64_t>(k) * 24000;
        check(exact, "120 BPM @48k: 20 pulsos en 10 s, el pulso k cae EXACTAMENTE en el frame k*24000");
        check(hits.front().frame == 0, "start(): el primer pulso cae en el frame 0");
    }

    // ── 2. Independencia del tamaño de bloque (tempo no entero) ──
    {
        const std::vector<std::vector<int>> layouts = {{64}, {480}, {1024}, {4096}, {7}, {333, 17, 1024, 5, 2048}};
        std::vector<std::vector<Hit>> all;
        for (auto& l : layouts) { TempoClock c; c.prepare(44100); c.setTempo(133.0); c.start(); all.push_back(run(c, 44100 * 20, l)); }
        bool same = true;
        for (std::size_t i = 1; i < all.size(); ++i) {
            same = same && all[i].size() == all[0].size();
            for (std::size_t k = 0; same && k < all[0].size(); ++k)
                same = all[i][k].frame == all[0][k].frame && all[i][k].beatInBar == all[0][k].beatInBar;
        }
        check(same, "133 BPM @44.1k: idéntico con 6 patrones de tamaño de bloque (incluido 1 frame y bloques irregulares)");
        check(all[0].size() == 45, "133 BPM @44.1k: 45 pulsos en 20 s (0..44)");
    }

    // ── 3. Sin deriva tras 1 hora ──
    {
        TempoClock c; c.prepare(44100); c.setTempo(133.0); c.start();
        const double fpb = 44100.0 * 60.0 / 133.0; // 19894.736...
        auto hits = run(c, static_cast<std::int64_t>(44100) * 3600, {1024});
        bool ok = true; std::int64_t worst = 0;
        for (std::size_t k = 0; k < hits.size(); ++k) {
            const std::int64_t ideal = static_cast<std::int64_t>(std::floor(static_cast<double>(k) * fpb));
            const std::int64_t d = hits[k].frame > ideal ? hits[k].frame - ideal : ideal - hits[k].frame;
            if (d > worst) worst = d;
            if (d > 1) ok = false;
        }
        check(hits.size() == 7980, "1 hora a 133 BPM: 7980 pulsos exactos");
        check(ok, "1 hora: cada pulso está a ≤1 frame de su posición ideal (sin deriva acumulada)");
        std::fprintf(stdout, "       (desviación máxima observada: %lld frame)\n", static_cast<long long>(worst));
    }

    // ── 4. Fase continua al cambiar de tempo ──
    {
        TempoClock c; c.prepare(48000); c.setTempo(120.0); c.start();
        // 1) pulso 0 en frame 0. 2) a los 6000 frames (1/4 de pulso) pasamos a 240 BPM.
        auto h1 = run(c, 6000, {6000});
        c.setTempo(240.0);   // quedaban 18000 frames a 120 BPM → 9000 a 240 BPM
        auto h2 = run(c, 40000, {1000});
        check(h1.size() == 1 && h1[0].frame == 0, "fase continua: pulso inicial en 0");
        // posiciones absolutas esperadas tras el cambio: 6000 + 9000, +12000, +12000...
        bool ok = h2.size() >= 3;
        for (std::size_t k = 0; ok && k < 3; ++k) {
            const std::int64_t expect = 6000 + 9000 + static_cast<std::int64_t>(k) * 12000;
            ok = (h2[k].frame + 6000) == expect;
        }
        check(ok, "fase continua: tras 120→240 BPM el siguiente pulso llega a los 9000 frames restantes (la mitad), luego cada 12000");
        bool seq = h2.size() >= 3 && h2[0].beatInBar == 1 && h2[1].beatInBar == 2 && h2[2].beatInBar == 3;
        check(seq, "fase continua: el contador de compás sigue (1,2,3…), sin repetir ni saltar pulsos");
    }
    {
        // desacelerar: 240 → 60 BPM a 1/2 pulso
        TempoClock c; c.prepare(48000); c.setTempo(240.0); c.start();   // 12000 frames/pulso
        (void)run(c, 6000, {6000});                                     // medio pulso
        c.setTempo(60.0);                                               // quedaban 6000 @240 → 24000 @60
        auto h = run(c, 60000, {2048});
        check(!h.empty() && h[0].frame + 6000 == 6000 + 24000, "fase continua al DESACELERAR: 6000 restantes → 24000");
    }

    // ── 5. Compás ──
    {
        TempoClock c; c.prepare(48000); c.setTempo(300.0); c.setBeatsPerBar(3); c.start();
        auto h = run(c, 48000 * 4, {512});
        bool ok = h.size() > 12;
        for (std::size_t k = 0; ok && k < h.size(); ++k) ok = h[k].beatInBar == k % 3;
        check(ok, "compás de 3: beatInBar = 0,1,2,0,1,2…");
        c.setBeatsPerBar(0);  check(c.beatsPerBar() == TempoClock::kMinBeatsPerBar, "setBeatsPerBar(0) → 1");
        c.setBeatsPerBar(99); check(c.beatsPerBar() == TempoClock::kMaxBeatsPerBar, "setBeatsPerBar(99) → 16");
    }
    {
        // reducir el compás con el contador "fuera" del nuevo rango no genera índices inválidos
        TempoClock c; c.prepare(48000); c.setTempo(300.0); c.setBeatsPerBar(8); c.start();
        (void)run(c, 48000, {256});
        c.setBeatsPerBar(2);
        auto h = run(c, 48000, {256});
        bool ok = true; for (auto& x : h) ok = ok && x.beatInBar < 2;
        check(ok, "reducir el compás en marcha nunca produce un beatInBar fuera de rango");
    }

    // ── 6. start / stop ──
    {
        TempoClock c; c.prepare(48000); c.setTempo(120.0);
        TempoClock::Beat b[4];
        check(c.advance(48000, b, 4) == 0 && !c.isRunning(), "sin start(): no emite pulsos");
        c.start(); (void)run(c, 30000, {1000});                        // pulso en 0 y en 24000
        c.start();                                                      // idempotente: NO reinicia
        auto h = run(c, 30000, {1000});                                 // siguiente pulso en 48000 (= +18000 desde 30000)
        check(!h.empty() && h[0].frame == 18000, "start() repetido NO reinicia el compás (el siguiente pulso llega a su hora)");
        c.stop();
        check(c.advance(48000, b, 4) == 0, "stop(): deja de emitir");
        c.start();
        auto h2 = run(c, 100, {100});
        check(h2.size() == 1 && h2[0].frame == 0 && h2[0].beatInBar == 0, "stop()+start(): vuelve al primer tiempo en el frame 0");
    }

    // ── 7. Límite de eventos por bloque ──
    {
        TempoClock c; c.prepare(48000); c.setTempo(300.0); c.start();   // 9600 frames/pulso
        TempoClock::Beat b[1];
        const int n = c.advance(9600 * 5, b, 1);                        // 5 pulsos, capacidad 1
        check(n == 1 && b[0].frameOffset == 0, "maxOut respetado: devuelve 1, sin escribir fuera del buffer");
        TempoClock::Beat b2[8];
        const int m = c.advance(9600, b2, 8);
        check(m == 1 && b2[0].beatInBar == 1, "el contador de compás sigue siendo correcto aunque se descartaran eventos (siguiente = 1)");
        // beatInBar tras 5 pulsos descartados desde 0 es 5%4=1 (hit); se emitió 1 de 5, los otros 4 avanzaron el contador
    }

    // ── 8. Entradas inválidas y límites ──
    {
        TempoClock c; c.prepare(48000); c.setTempo(120.0);
        c.setTempo(std::numeric_limits<double>::quiet_NaN());
        c.setTempo(std::numeric_limits<double>::infinity());
        check(c.tempo() == 120.0, "setTempo(NaN/Inf) se ignora");
        c.setTempo(5.0);    check(c.tempo() == 20.0,  "setTempo(5) → 20 (mínimo)");
        c.setTempo(1000.0); check(c.tempo() == 300.0, "setTempo(1000) → 300 (máximo)");
        c.prepare(0); c.prepare(-5);
        TempoClock::Beat b[4]; c.start();
        check(c.advance(48000, b, 4) >= 1, "prepare(<=0) se ignora (el reloj sigue operativo)");
        check(c.advance(0, b, 4) == 0 && c.advance(-10, b, 4) == 0, "advance(<=0 frames) no hace nada");
    }

    // ── 9. Cambio de frecuencia de muestreo en marcha conserva la fase ──
    {
        TempoClock c; c.prepare(48000); c.setTempo(120.0); c.start();   // 24000 frames/pulso
        (void)run(c, 12000, {12000});                                   // mitad del pulso
        c.prepare(96000);                                               // quedaban 12000 @48k → 24000 @96k
        auto h = run(c, 60000, {4096});
        check(!h.empty() && h[0].frame == 24000, "prepare(sr) en marcha: el tiempo restante se reescala (fase musical intacta)");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
