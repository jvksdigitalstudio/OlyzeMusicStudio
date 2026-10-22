// TransportEngine + posición (ADR 0036) — reloj y metrónomo REALES.
//
//   · pausar CONSERVA la posición y la fase; arrancar continúa justo ahí;
//   · locate() coloca el transporte (en parado y en marcha) y el siguiente pulso trae el tiempo que toca;
//   · el conteo previo RETIENE la posición donde se va a grabar y la grabación continúa desde ella, también
//     cuando no es el origen y también cuando el compás cambia durante el conteo;
//   · pausar durante el conteo no mueve la canción; localizar durante el conteo lo cancela;
//   · el resultado no depende del tamaño de bloque.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "TransportEngine.h"

using namespace eliner;
namespace pulse = eliner::pulse;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;
constexpr int kBeat = 24000; // 120 BPM

struct Ev { int block; std::uint32_t beatInBar; bool countIn; std::int64_t pos; };

// Renderiza [frames] en bloques de [blk]. Registra, por cada bloque con pulsos, el pulso publicado y la posición
// publicada tras el bloque; y la posición tras CADA bloque en [pos].
struct Run { std::vector<Ev> beats; std::vector<std::int64_t> pos; };

Run render(TransportEngine& t, int frames, int blk) {
    Run r; std::vector<float> buf(static_cast<size_t>(blk) * 2);
    std::uint64_t lastSeq = pulse::sequenceOf(t.pulseSnapshot());
    int idx = 0;
    for (int p = 0; p < frames; p += blk, ++idx) {
        const int n = std::min(blk, frames - p);
        std::fill(buf.begin(), buf.begin() + static_cast<long>(n) * 2, 0.0f);
        (void)t.advance(n);
        t.renderClick(buf.data(), n);
        const auto snap = t.pulseSnapshot();
        r.pos.push_back(t.positionSnapshot());
        if (pulse::sequenceOf(snap) != lastSeq) {
            lastSeq = pulse::sequenceOf(snap);
            r.beats.push_back({idx, pulse::beatInBarOf(snap), pulse::countInOf(snap), t.positionSnapshot()});
        }
    }
    return r;
}

void setup(TransportEngine& t, bool armed = false, int bars = 0) {
    t.prepare(kSR); t.setTempo(120.0); t.setBeatsPerBar(4);
    t.setMetronomeVolume(0.8f); t.setMetronomeEnabled(false);
    t.setRecordArmed(armed); t.setCountInBars(bars);
}
} // namespace

int main() {
    // ── 1. La posición avanza con el transporte ──
    {
        TransportEngine t; setup(t);
        check(t.positionSnapshot() == 0, "recién creado: posición 0");
        t.setRunning(true);
        (void)render(t, 2 * kBeat + 12000, 480);          // 2,5 pulsos
        check(t.positionSnapshot() == 2400, "2,5 pulsos → 2400 ticks");
    }

    // ── 2. Pausar conserva la posición y arrancar continúa ──
    {
        TransportEngine t; setup(t);
        t.setRunning(true);
        (void)render(t, 2 * kBeat + 12000, 480);
        t.setRunning(false);
        check(t.positionSnapshot() == 2400, "pausar: la posición publicada es la del instante de la pausa");
        (void)render(t, 5 * kBeat, 480);
        check(t.positionSnapshot() == 2400, "pausado, la posición no cambia aunque siga el audio");
        t.setRunning(true);
        const Run r = render(t, kBeat, 480);              // 12000 frames hasta el pulso 3 (en 2880) y 12000 más
        check(!r.beats.empty() && r.beats.front().beatInBar == 3, "reanudar: el siguiente pulso es el tiempo 4, no el 1");
        check(t.positionSnapshot() == 2400 + 960, "reanudar: la posición continúa (2400 + 960)");
    }

    // ── 3. Pausar y reanudar es transparente: misma posición que sin pausa ──
    {
        auto withPause = [](int blk) {
            TransportEngine t; setup(t); t.setRunning(true);
            (void)render(t, 33333, blk); t.setRunning(false); (void)render(t, 7777, blk);
            t.setRunning(true); (void)render(t, 50000, blk); return t.positionSnapshot();
        };
        TransportEngine ref; setup(ref); ref.setRunning(true); (void)render(ref, 33333 + 50000, 480);
        // Con bloques múltiplos del paso la comparación es exacta; con otros, la posición coincide con el total de frames.
        check(withPause(480) == ref.positionSnapshot(), "pausar y reanudar no gana ni pierde ticks");
        check(withPause(64) == withPause(480) && withPause(480) == withPause(4800),
              "…con bloques de 64, 480 y 4800 frames");
    }

    // ── 4. locate: en parado, en marcha y con valores límite ──
    {
        TransportEngine t; setup(t);
        t.locate(1920);
        check(t.positionSnapshot() == 1920, "locate en parado: la posición se publica al momento");
        t.setRunning(true);
        const Run r = render(t, 2 * kBeat, kBeat);
        check(!r.beats.empty() && r.beats.front().beatInBar == 2, "arrancar tras locate(1920): el primer pulso es el tiempo 3");
        t.locate(0);
        const Run q = render(t, kBeat, 480);
        check(!q.beats.empty() && q.beats.front().beatInBar == 0 && q.beats.front().block == 0,
              "locate(0) con el transporte en marcha: el siguiente bloque abre con el tiempo 1");
        t.locate(-5000);
        check(t.positionSnapshot() == 0, "locate negativo: se trata como 0");
        t.setRunning(false);
        t.locate(1LL << 40);
        check(t.positionSnapshot() == kMaxPositionTicks, "locate enorme: se recorta al máximo");
    }

    // ── 5. Conteo desde el origen: la posición queda retenida en 0 y la grabación continúa desde ahí ──
    {
        TransportEngine t; setup(t, true, 1);
        t.setRunning(true);
        const Run r = render(t, 4 * kBeat, 480);          // el compás de conteo entero
        bool held = true;
        for (auto p : r.pos) held = held && p == 0;
        check(held, "durante TODO el conteo la posición permanece en 0 (la canción no se mueve)");
        const Run q = render(t, kBeat + 12000, 480);      // la grabación: pulso 0 y mitad del 1
        check(t.phase() == TransportPhase::Running, "pasado el conteo la fase es Running");
        check(t.positionSnapshot() == 960 + 480, "la grabación avanza desde 0: 1,5 pulsos → 1440 ticks");
        check(!q.beats.empty() && q.beats.front().beatInBar == 0 && !q.beats.front().countIn,
              "el primer pulso de la grabación es el tiempo 1 y no es de conteo");
    }

    // ── 6. Conteo desde una posición que no es un compás: cuenta 1-2-3-4 y entra en SU pulso ──
    {
        TransportEngine t; setup(t, true, 1);
        t.locate(2 * 960);                                 // pulso 2 del compás 0 (tiempo 3)
        t.setRunning(true);
        const Run r = render(t, 4 * kBeat, kBeat);         // un bloque por pulso
        bool held = true, counted = r.beats.size() == 4;
        for (auto p : r.pos) held = held && p == 1920;
        for (size_t i = 0; i < r.beats.size() && i < 4; ++i)
            counted = counted && r.beats[i].countIn && r.beats[i].beatInBar == i;
        check(held, "conteo desde 1920: la posición queda retenida en 1920");
        check(counted, "el conteo cuenta 1-2-3-4 desde el primer tiempo aunque se grabe desde el tiempo 3");
        const Run q = render(t, 3 * kBeat, kBeat);
        check(q.beats.size() == 3 && !q.beats[0].countIn && q.beats[0].beatInBar == 2 && q.beats[1].beatInBar == 3
                  && q.beats[2].beatInBar == 0,
              "la grabación entra en el tiempo 3 y sigue 4, 1… (el tiempo de la posición, no el del conteo)");
        check(t.positionSnapshot() == 1920 + 3 * 960, "la posición continúa exactamente desde 1920 (3 pulsos después: 4800)");
    }

    // ── 6b. Una posición a mitad de pulso se redondea hacia abajo a la rejilla de pulsos ──
    {
        TransportEngine t; setup(t, true, 1);
        t.locate(960 + 400);                               // pulso 1 + 400 ticks
        t.setRunning(true);
        const Run r = render(t, 4 * kBeat, 480);
        bool held = true;
        for (auto p : r.pos) held = held && p == 960;
        check(held, "grabar desde mitad de pulso: la grabación empieza en el pulso de abajo (960)");
    }

    // ── 7. Pausar durante el conteo no mueve la canción ──
    {
        TransportEngine t; setup(t, true, 2);
        t.locate(960);
        t.setRunning(true);
        (void)render(t, 3 * kBeat + 5000, 480);
        t.setRunning(false);
        check(t.positionSnapshot() == 960, "pausar a mitad del conteo: la posición sigue siendo la de grabación (960)");
        t.setRecordArmed(false);
        t.setRunning(true);
        const Run r = render(t, kBeat, 480);
        check(!r.beats.empty() && !r.beats.front().countIn && r.beats.front().beatInBar == 1,
              "reanudar sin armar: continúa desde 960 (el tiempo 2), sin conteo");
    }

    // ── 8. Localizar durante el conteo lo cancela ──
    {
        TransportEngine t; setup(t, true, 4);
        t.setRunning(true);
        (void)render(t, 2 * kBeat + 100, 480);
        check(t.phase() == TransportPhase::CountIn, "a mitad del conteo la fase es CountIn");
        t.locate(3840);
        check(t.isRunning() && t.phase() == TransportPhase::Running, "locate durante el conteo: sigue en marcha y sin conteo");
        const Run r = render(t, 3 * kBeat, kBeat);
        bool none = true;
        for (const auto& e : r.beats) none = none && !e.countIn;
        check(none && !r.beats.empty() && r.beats.front().beatInBar == 0, "ningún pulso posterior es de conteo; empieza en el tiempo 1 del compás 1");
    }

    // ── 9. Cambio de compás durante el conteo: la posición de grabación se respeta ──
    {
        TransportEngine t; setup(t, true, 2);
        t.locate(4 * 960);                                 // compás 1
        t.setRunning(true);
        (void)render(t, 5 * kBeat + 10, kBeat);
        t.setBeatsPerBar(3);                               // 4/4 → 3/4 a mitad del 2.º compás del conteo
        const Run r = render(t, 8 * kBeat, kBeat);
        bool found = false;
        for (const auto& e : r.beats) {
            if (!e.countIn) { found = true; break; }
        }
        check(found, "el conteo termina aunque el compás cambie");
        const Run q = render(t, 2 * kBeat, kBeat);
        // Cuando suena la grabación, la posición vuelve a ser la de grabación + lo transcurrido: nunca por debajo.
        check(t.positionSnapshot() >= 4 * 960, "la posición tras el conteo no es anterior a la de grabación");
        (void)q;
    }

    // ── 10. Armar a mitad de reproducción no mueve la posición ni inicia conteo ──
    {
        TransportEngine t; setup(t, false, 1);
        t.setRunning(true);
        (void)render(t, kBeat + 5000, 480);
        const auto before = t.positionSnapshot();
        t.setRecordArmed(true);
        t.setRunning(true);
        (void)render(t, 480, 480);
        check(t.positionSnapshot() >= before && t.phase() == TransportPhase::Running,
              "armar y repetir play en marcha: la posición sigue avanzando y no hay conteo");
    }

    // ── 11. La posición publicada es monótona mientras suena (sin saltos hacia atrás) ──
    {
        TransportEngine t; setup(t); t.setRunning(true);
        const Run r = render(t, 20 * kBeat, 333);
        bool mono = true;
        for (size_t i = 1; i < r.pos.size(); ++i) mono = mono && r.pos[i] >= r.pos[i - 1];
        check(mono, "la posición no retrocede en 20 pulsos con bloques de 333 frames");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
