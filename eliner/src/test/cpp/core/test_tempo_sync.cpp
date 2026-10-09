// ADR 0028 — TempoSync.h (rango de tempo, validación por bits, plegado).
//
// Prueba el header de PRODUCCIÓN (no un modelo). Valida en particular que
// isFinite() detecta NaN/±Inf por bits: el build real usa -ffast-math, bajo el
// cual std::isfinite puede optimizarse a `true`.
//
// Compilación (ver run_native_tests.sh):
//   g++ -std=c++20 -Wall -Wextra -Wpedantic -fsanitize=address,undefined
//       -I<eliner>/include/eliner/transport test_tempo_sync.cpp -o /tmp/t && /tmp/t
#include "TempoSync.h"
#include <cmath>
#include <cstdio>
#include <limits>

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
bool near(double a, double b, double eps = 1e-9) { return std::fabs(a - b) <= eps; }
}

int main() {
    using namespace eliner::tempo;
    constexpr double kInf = std::numeric_limits<double>::infinity();
    constexpr double kNan = std::numeric_limits<double>::quiet_NaN();

    // ── isFinite por bits ──
    check(isFinite(0.0) && isFinite(1.0) && isFinite(-1e300) && isFinite(5e-324),
          "isFinite(double): cero, normales y denormal son finitos");
    check(!isFinite(kInf) && !isFinite(-kInf) && !isFinite(kNan),
          "isFinite(double): ±Inf y NaN NO son finitos");
    check(isFinite(0.0f) && isFinite(3.5f) && isFinite(1e-45f),
          "isFinite(float): cero, normal y denormal son finitos");
    check(!isFinite(std::numeric_limits<float>::infinity()) &&
          !isFinite(-std::numeric_limits<float>::infinity()) &&
          !isFinite(std::numeric_limits<float>::quiet_NaN()),
          "isFinite(float): ±Inf y NaN NO son finitos");

    // ── clamp de tempo ──
    check(clampBpm(120.0) == 120.0, "clampBpm: valor válido intacto");
    check(clampBpm(1.0) == kMinBpm && clampBpm(-50.0) == kMinBpm, "clampBpm: por debajo → 20");
    check(clampBpm(9999.0) == kMaxBpm, "clampBpm: por encima → 300");
    check(clampBpm(20.0) == 20.0 && clampBpm(300.0) == 300.0, "clampBpm: los extremos son válidos");

    // ── beats → segundos ──
    check(near(secondsPerBeat(120.0), 0.5), "una negra a 120 BPM = 0.5 s");
    check(near(beatsToSeconds(120.0, 0.75), 0.375),
          "corchea con puntillo (0.75) a 120 BPM = 0.375 s (el delay por defecto)");
    check(near(beatsToSeconds(60.0, 1.0), 1.0), "una negra a 60 BPM = 1 s");
    check(near(beatsToSeconds(240.0, 0.25), 0.0625), "semicorchea a 240 BPM = 62.5 ms");
    check(near(beatsToSeconds(5.0, 1.0), 3.0), "tempo fuera de rango se limita antes de convertir (20 BPM → 3 s)");

    // ── plegado por octavas ──
    check(near(foldIntoRange(0.5, 0.01, 1.99), 0.5), "fold: dentro de rango no cambia");
    check(near(foldIntoRange(2.25, 0.01, 1.99), 1.125),
          "fold: 2.25 s (corchea con puntillo a 20 BPM) → 1.125 s (la mitad)");
    check(near(foldIntoRange(8.0, 0.01, 1.99), 1.0), "fold: 8 s → 1 s (÷2 tres veces)");
    check(near(foldIntoRange(0.004, 0.01, 1.99), 0.016), "fold: 4 ms → 16 ms (×2 dos veces)");
    {
        // el plegado conserva la relación rítmica: el resultado es potencia de 2 × original
        const double in = 3.7, out = foldIntoRange(in, 0.01, 1.99);
        const double ratio = in / out;
        check(near(std::log2(ratio), std::round(std::log2(ratio)), 1e-9),
              "fold: el cociente con el valor original es una potencia exacta de 2");
    }
    check(foldIntoRange(1e9, 0.01, 1.99) <= 1.99 && foldIntoRange(1e-9, 0.01, 1.99) >= 0.01,
          "fold: valores extremos terminan dentro del rango (acotado, sin bucle infinito)");

    // ── delay sincronizado: recorre todo el rango de tempo y varias divisiones ──
    {
        bool allInRange = true;
        const double divisions[] = {0.25, 1.0/3.0, 0.5, 2.0/3.0, 0.75, 1.0, 1.5, 2.0, 4.0};
        for (double bpm = kMinBpm; bpm <= kMaxBpm; bpm += 1.0)
            for (double beats : divisions) {
                const double s = syncedDelaySeconds(bpm, beats, 0.01, 1.99);
                if (!(s >= 0.01 && s <= 1.99)) allInRange = false;
            }
        check(allInRange, "syncedDelaySeconds: siempre dentro de [0.01, 1.99] para todo BPM y división");
    }
    check(near(syncedDelaySeconds(120.0, 0.75, 0.01, 1.99), 0.375),
          "syncedDelaySeconds: 120 BPM, 0.75 → 0.375 s (sin plegar)");
    check(near(syncedDelaySeconds(20.0, 0.75, 0.01, 1.99), 1.125),
          "syncedDelaySeconds: 20 BPM, 0.75 → 1.125 s (plegado a la mitad)");

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
