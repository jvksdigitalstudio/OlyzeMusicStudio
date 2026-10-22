// Posición del transporte (ADR 0036): conversiones puras de Position.h y la posición del TempoClock.
//
// Qué fija este test:
//   · Position.h: ticks por pulso, división de SUELO con negativos, SPP (semicorcheas, 14 bits, recorte),
//     compás|pulso|tick también antes del origen.
//   · TempoClock: cada pulso trae su posición exacta; positionTicks() es función de los frames avanzados (no del
//     tamaño de bloque); pause() conserva posición y fase y start() continúa; stop() rebobina; locate() coloca el
//     reloj en límite de pulso, a mitad de pulso y en negativo con el tiempo del compás correcto; la fase se
//     conserva al cambiar tempo, frecuencia de muestreo o unidad de pulso con el reloj pausado.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "Position.h"
#include "TempoClock.h"

using namespace eliner;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}

TempoClock make(double bpm = 120.0, int bar = 4, int sr = 48000) {
    TempoClock c; c.prepare(sr); c.setTempo(bpm); c.setBeatsPerBar(bar); return c;
}
// 120 BPM a 48 kHz: un pulso = 24000 frames = 960 ticks → 25 frames por tick.
constexpr int kBeat = 24000;
} // namespace

int main() {
    // ── Position.h ──
    check(ticksPerPulse(2.0) == 1920 && ticksPerPulse(1.0) == 960 && ticksPerPulse(0.5) == 480 && ticksPerPulse(0.25) == 240,
          "ticks por pulso: blanca 1920, negra 960, corchea 480, semicorchea 240");
    check(ticksPerPulse(0.0) == 1, "una duración degenerada nunca da 0 ticks");
    check(floorDiv(7, 4) == 1 && floorDiv(-1, 4) == -1 && floorDiv(-4, 4) == -1 && floorDiv(-5, 4) == -2 && floorDiv(0, 4) == 0,
          "floorDiv redondea hacia abajo con negativos");
    check(floorMod(-1, 4) == 3 && floorMod(-4, 4) == 0 && floorMod(5, 4) == 1 && floorMod(-5, 4) == 3,
          "floorMod es siempre >= 0");
    check(clampPositionTicks(-1000000) == kMinPositionTicks && clampPositionTicks(1LL << 40) == kMaxPositionTicks
              && clampPositionTicks(123) == 123, "clampPositionTicks recorta a [min, max]");
    check(static_cast<std::int64_t>(static_cast<float>(kMaxPositionTicks)) == kMaxPositionTicks
              && static_cast<std::int64_t>(static_cast<float>(kMaxPositionTicks - 1)) == kMaxPositionTicks - 1,
          "el máximo y su anterior son EXACTOS en float (viajan como parámetro float)");

    // SPP
    check(songPositionFor(0) == 0 && songPositionFor(-500) == 0, "SPP: 0 y negativos → 0");
    check(songPositionFor(239) == 0 && songPositionFor(240) == 1 && songPositionFor(959) == 3 && songPositionFor(960) == 4,
          "SPP: semicorcheas (240 ticks), redondeo hacia abajo");
    check(songPositionFor(static_cast<std::int64_t>(kMaxSongPosition) * 240) == kMaxSongPosition
              && songPositionFor((static_cast<std::int64_t>(kMaxSongPosition) + 1) * 240) == kMaxSongPosition
              && songPositionFor(kMaxPositionTicks) == kMaxSongPosition, "SPP: recorta a 14 bits (16383)");
    check(songPositionLsb(0x1234 & 0x3FFF) == (0x1234 & 0x7F) && songPositionMsb(0x1234 & 0x3FFF) == ((0x1234 >> 7) & 0x7F),
          "SPP: mitades de 7 bits LSB/MSB");
    check(songPositionLsb(16383) == 127 && songPositionMsb(16383) == 127 && songPositionLsb(128) == 0 && songPositionMsb(128) == 1,
          "SPP: 16383 → 127/127 y 128 → LSB 0, MSB 1");

    // Compás | pulso | tick
    {
        const auto a = barBeatTickFor(0, 4, 1.0);
        check(a.bar == 0 && a.beat == 0 && a.tick == 0, "origen: compás 0, pulso 0, tick 0");
        const auto b = barBeatTickFor(4 * 960 + 960 + 7, 4, 1.0);
        check(b.bar == 1 && b.beat == 1 && b.tick == 7, "5 pulsos y 7 ticks en 4/4 → compás 1, pulso 1, tick 7");
        const auto c = barBeatTickFor(-1, 4, 1.0);
        check(c.bar == -1 && c.beat == 3 && c.tick == 959, "−1 tick = último tick del último pulso del compás −1");
        const auto d = barBeatTickFor(-4 * 960, 4, 1.0);
        check(d.bar == -1 && d.beat == 0 && d.tick == 0, "−4 pulsos = inicio del compás −1");
        const auto e = barBeatTickFor(3 * 480, 3, 0.5);
        check(e.bar == 1 && e.beat == 0 && e.tick == 0, "3/8 con pulso de corchea: 3 pulsos = compás 1");
        check(barBeatTickFor(100, 0, 1.0).beat == 0, "un compás de 0 pulsos se trata como 1 (sin dividir por cero)");
    }

    // ── TempoClock: la posición de cada pulso ──
    {
        TempoClock c = make(); c.start();
        TempoClock::Beat b[8];
        const int n = c.advance(4 * kBeat + 1, b, 8);
        bool ok = n == 5;
        for (int i = 0; i < n; ++i) ok = ok && b[i].positionTicks == i * 960LL && b[i].frameOffset == i * kBeat % (4 * kBeat + 1);
        check(ok, "cada pulso trae la posición exacta de su inicio: 0, 960, 1920…");
    }
    {
        TempoClock c = make(); c.start();
        TempoClock::Beat b[8];
        (void)c.advance(12000, b, 8);
        check(c.positionTicks() == 480, "a mitad del primer pulso la posición es 480 ticks (25 frames por tick)");
        (void)c.advance(12000, b, 8);
        check(c.positionTicks() == 960, "al completar el pulso son 960");
    }

    // ── La posición no depende del tamaño de bloque ──
    {
        // Mismos frames totales (960000) con bloques distintos.
        auto total = [](int blk) {
            TempoClock c = make(137.3, 5); c.start();
            TempoClock::Beat b[8];
            for (int done = 0; done < 960000; done += blk) (void)c.advance(blk, b, 8);
            return c.positionTicks();
        };
        check(total(64) == total(480) && total(480) == total(960) && total(960) == total(4800),
              "mismos frames con bloques de 64, 480, 960 y 4800: la MISMA posición");
    }

    // ── pause / start / stop ──
    {
        TempoClock c = make(); c.start();
        TempoClock::Beat b[8];
        (void)c.advance(kBeat + 6000, b, 8);        // pulso 1 + 6000 frames → 960 + 240 = 1200 ticks
        const auto at = c.positionTicks();
        c.pause();
        check(!c.isRunning() && c.advance(48000, b, 8) == 0, "pause(): deja de emitir");
        check(c.positionTicks() == at && at == 1200, "pause(): la posición se conserva (1200 ticks)");
        c.start();
        const int n = c.advance(18000 + 1, b, 8);   // faltaban 18000 frames para el pulso 2 (en 1920)
        check(n == 1 && b[0].frameOffset == 18000 && b[0].positionTicks == 1920 && b[0].beatInBar == 2,
              "start() tras pause(): el siguiente pulso llega justo cuando tocaba (fase conservada), con su tiempo");
    }
    {
        TempoClock c = make(); c.start();
        TempoClock::Beat b[8];
        (void)c.advance(3 * kBeat + 77, b, 8);
        c.stop();
        check(c.positionTicks() == 0, "stop(): rebobina a 0");
        c.start();
        const int n = c.advance(10, b, 8);
        check(n == 1 && b[0].frameOffset == 0 && b[0].beatInBar == 0 && b[0].positionTicks == 0,
              "stop()+start(): vuelve al primer tiempo en el frame 0");
    }
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.start(); c.start();
        const int n = c.advance(100, b, 8);
        check(n == 1 && b[0].positionTicks == 0, "un start() repetido no reinicia");
        (void)c.advance(kBeat, b, 8);
        c.start();
        check(c.advance(1, b, 8) == 0, "start() con el reloj en marcha no hace nada (no emite un pulso extra)");
    }

    // ── locate ──
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.locate(4 * 960);                          // límite de pulso: inicio del compás 1
        c.start();
        int n = c.advance(10, b, 8);
        check(n == 1 && b[0].frameOffset == 0 && b[0].beatInBar == 0 && b[0].positionTicks == 3840,
              "locate en un límite de pulso: ese pulso cae en el frame 0 con su tiempo");
    }
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.locate(2 * 960 + 240);                    // pulso 2 + un cuarto → faltan 3/4 de pulso = 18000 frames
        check(c.positionTicks() == 2 * 960 + 240, "locate a mitad de pulso: la posición es exactamente esa");
        c.start();
        int n = c.advance(18000 + 1, b, 8);
        check(n == 1 && b[0].frameOffset == 18000 && b[0].beatInBar == 3 && b[0].positionTicks == 3 * 960,
              "locate a mitad de pulso: el siguiente pulso llega cuando toca y es el tiempo 4");
    }
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.locate(-4 * 960);                         // un compás ANTES del origen
        c.start();
        int n = c.advance(4 * kBeat, b, 8);
        bool ok = n == 4;
        for (int i = 0; i < n; ++i) ok = ok && b[i].beatInBar == static_cast<std::uint32_t>(i) && b[i].positionTicks == -3840 + i * 960LL;
        check(ok, "locate en negativo: el compás −1 empieza en el tiempo 1 y las posiciones son negativas");
        check(c.positionTicks() == 0, "…y al acabar el compás −1 la posición es 0");
        n = c.advance(1, b, 8);
        check(n == 1 && b[0].beatInBar == 0 && b[0].positionTicks == 0, "el pulso del origen es el tiempo 1 con posición 0");
    }
    {
        TempoClock c = make(120.0, 4); TempoClock::Beat b[8];
        c.locate(-960);                             // un pulso antes del origen: tiempo 4 del compás −1
        c.start();
        (void)c.advance(1, b, 8);
        check(b[0].beatInBar == 3, "locate(−1 pulso): el pulso es el tiempo 4 del compás anterior");
    }
    {
        TempoClock c = make();
        c.locate(-100000000); check(c.positionTicks() == kMinPositionTicks, "locate recorta por debajo");
        c.locate(1LL << 40);  check(c.positionTicks() == kMaxPositionTicks, "locate recorta por encima");
    }
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.start(); (void)c.advance(2 * kBeat + 5000, b, 8);
        c.locate(960);                              // con el reloj EN MARCHA
        int n = c.advance(10, b, 8);
        check(n == 1 && b[0].frameOffset == 0 && b[0].positionTicks == 960 && b[0].beatInBar == 1,
              "locate con el reloj en marcha: salta y el siguiente pulso cae en el frame 0");
    }

    // ── La fase se conserva al cambiar parámetros con el reloj PAUSADO ──
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.start(); (void)c.advance(kBeat + 12000, b, 8);   // mitad del pulso 1: 1440 ticks
        c.pause();
        c.setTempo(60.0);                                  // el pulso dura el doble: 48000 frames
        check(c.positionTicks() == 1440, "cambiar el tempo con el reloj pausado no mueve la posición");
        c.start();
        const int n = c.advance(24000 + 1, b, 8);          // falta la mitad del pulso (24000 frames a 60 BPM)
        check(n == 1 && b[0].frameOffset == 24000 && b[0].positionTicks == 1920,
              "…y la fracción que falta del pulso se reescala (fase continua)");
    }
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.start(); (void)c.advance(12000, b, 8);
        c.pause();
        c.prepare(96000);                                  // el doble de frecuencia de muestreo
        check(c.positionTicks() == 480, "cambiar la frecuencia con el reloj pausado no mueve la posición");
        c.start();
        const int n = c.advance(24000 + 1, b, 8);
        check(n == 1 && b[0].frameOffset == 24000, "…y el pulso llega tras los frames reescalados");
    }

    // ── Cambio de unidad de pulso: la posición se conserva ──
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.start(); (void)c.advance(12000, b, 8);          // 480 ticks, mitad del pulso 0
        c.setQuartersPerPulse(0.5);                        // el pulso pasa a corchea (480 ticks)
        check(c.positionTicks() == 480, "cambiar la unidad a mitad de pulso no mueve la posición");
        const int n = c.advance(6000 + 1, b, 8);           // faltaba la mitad del pulso, ahora de 12000 frames
        check(n == 1 && b[0].frameOffset == 6000 && b[0].positionTicks == 480 + 240,
              "…el siguiente pulso llega a mitad del restante y su posición es coherente");
    }

    // ── Pulsos que no caben en la lista: la posición sigue siendo exacta ──
    {
        TempoClock c = make(300.0, 4, 8000); c.start();    // pulso de 1600 frames
        TempoClock::Beat b[2];
        const int n = c.advance(1600 * 5 + 1, b, 2);        // 6 pulsos; solo caben 2 en la lista
        check(n == 2 && c.positionTicks() == 5 * 960, "con más pulsos que capacidad, la posición cuenta TODOS");
    }

    // ── restartBar (Sync del tapper) ──
    {
        TempoClock c = make(); TempoClock::Beat b[8];
        c.start(); (void)c.advance(2 * kBeat + 1, b, 8);   // el siguiente pulso es el tiempo 3 (índice 2→3)
        c.restartBar();
        const int n = c.advance(1, b, 8);
        check(n == 1 && b[0].beatInBar == 0 && b[0].frameOffset == 0 && b[0].positionTicks == 0,
              "restartBar: el pulso siguiente es el tiempo 1 y la posición vuelve al inicio de ese compás");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
