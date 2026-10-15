// Fuzzing determinista del TransportEngine (reloj + metrónomo): operaciones aleatorias, incluidos valores
// basura, con tamaños de bloque aleatorios. Bajo ASan/UBSan (ver run_native_tests.sh).
//
// Invariantes que NUNCA pueden romperse, hagan lo que hagan los parámetros:
//   · la salida es finita (sin NaN/Inf) y no supera el techo de nivel del metrónomo (0,8);
//   · ningún acceso fuera de rango (lo vigilan los sanitizers);
//   · la secuencia de pulsos publicada nunca retrocede;
//   · tras desactivar el metrónomo y esperar a que se apague, el silencio es absoluto.
// Semilla fija: un fallo es reproducible. Cambiar kSeed explora otra trayectoria.
#include "TransportEngine.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <vector>

using eliner::TransportEngine;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
struct Rng {
    std::uint64_t s;
    std::uint32_t next() { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return static_cast<std::uint32_t>(s >> 11); }
    int range(int lo, int hi) { return lo + static_cast<int>(next() % static_cast<std::uint32_t>(hi - lo + 1)); }
    double unit() { return (next() & 0xFFFFFF) / 16777216.0; }
};
constexpr double kNan = std::numeric_limits<double>::quiet_NaN();
constexpr double kInf = std::numeric_limits<double>::infinity();
} // namespace

int main() {
    constexpr std::uint64_t kSeeds[] = {0x9E3779B97F4A7C15ull, 0xC2B2AE3D27D4EB4Full, 0x165667B19E3779F9ull, 0xD6E8FEB86659FD93ull};
    for (std::uint64_t seed : kSeeds) {
        Rng r{seed};
        TransportEngine t;
        t.prepare(r.range(0, 1) ? 48000 : 44100);
        t.setMetronomeEnabled(true);
        t.setMetronomeVolume(1.0f);

        bool finiteOk = true, boundedOk = true, monotonicOk = true;
        std::uint64_t lastSeq = 0;
        std::vector<float> buf(2 * 1024);

        for (int iter = 0; iter < 6000; ++iter) {
            switch (r.range(0, 11)) {
                case 0: t.setTempo(r.range(0, 1) ? r.range(-50, 400) : (r.range(0, 3) == 0 ? kNan : r.unit() * 320.0)); break;
                case 1: t.setBeatsPerBar(r.range(-3, 20)); break;
                case 2: t.setBeatUnit(r.range(-2, 40)); break;
                case 3: t.setClickBeatLevels(r.next() & 0xFFFF, r.next() & 0xFFFF); break;
                case 4: t.setClickBeatLevels(r.next(), r.next()); break;            // palabras > 16 bits: se ignoran
                case 5: t.setClickSound(r.range(-2, 8)); break;
                case 6: t.setClickSubdivision(r.range(-2, 7)); break;
                case 7: t.setMetronomeEnabled(r.range(0, 1) == 1); break;
                case 8: t.setMetronomeVolume(r.range(0, 5) == 0 ? static_cast<float>(kInf) : static_cast<float>(r.unit() * 1.4 - 0.2)); break;
                case 9: t.setRunning(r.range(0, 3) != 0); break;
                case 10: t.setDelaySyncBeats(static_cast<float>(r.unit() * 8.0)); break;
                default: break;
            }
            const int n = r.range(1, 1024);
            std::fill(buf.begin(), buf.begin() + 2 * n, 0.0f);
            t.advance(n);
            t.renderClick(buf.data(), n);
            for (int i = 0; i < 2 * n; ++i) {
                if (!std::isfinite(buf[i])) finiteOk = false;
                if (std::fabs(buf[i]) > 0.8f + 1e-4f) boundedOk = false;
            }
            const std::uint64_t seq = eliner::pulse::sequenceOf(t.pulseSnapshot());
            if (seq < lastSeq) monotonicOk = false;
            lastSeq = seq;
            const double d = t.delaySyncSeconds(0.01, 1.99);
            if (!std::isfinite(d) || d < 0.01 - 1e-9 || d > 1.99 + 1e-9) finiteOk = false;
        }

        // Silencio absoluto tras apagar el metrónomo (se deja apagar la cola del último click).
        t.setMetronomeEnabled(false);
        float tail = 0.0f;
        for (int blk = 0; blk < 200; ++blk) {
            std::fill(buf.begin(), buf.begin() + 2 * 480, 0.0f);
            t.advance(480);
            t.renderClick(buf.data(), 480);
            if (blk >= 100) for (int i = 0; i < 2 * 480; ++i) tail = std::fmax(tail, std::fabs(buf[i]));
        }
        char msg[96];
        std::snprintf(msg, sizeof msg, "semilla %016llx: salida siempre finita y delay siempre en rango", static_cast<unsigned long long>(seed));
        check(finiteOk, msg);
        std::snprintf(msg, sizeof msg, "semilla %016llx: la salida nunca supera el techo de nivel", static_cast<unsigned long long>(seed));
        check(boundedOk, msg);
        std::snprintf(msg, sizeof msg, "semilla %016llx: la secuencia de pulsos nunca retrocede", static_cast<unsigned long long>(seed));
        check(monotonicOk, msg);
        std::snprintf(msg, sizeof msg, "semilla %016llx: silencio absoluto tras apagar el metrónomo", static_cast<unsigned long long>(seed));
        check(tail == 0.0f, msg);
    }
    std::fprintf(stdout, gFailures ? "== %d FALLO(S) ==\n" : "== TODO OK ==\n", gFailures);
    return gFailures ? 1 : 0;
}
