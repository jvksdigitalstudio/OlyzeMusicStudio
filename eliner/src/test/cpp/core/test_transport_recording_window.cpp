// Ventana de grabación del TransportEngine (ADR 0038): qué frames de cada bloque pertenecen a una grabación.
//
// Es lo que consume la captura para empezar y parar con precisión de muestra. Se comprueba renderizando bloques
// reales y acumulando, frame a frame, qué dice recordingBlock():
//   · el conteo previo NO se graba; la grabación empieza en el frame EXACTO del primer pulso tras el conteo,
//     con la posición de grabación; sin conteo empieza con el primer bloque; armar a mitad de reproducción
//     empieza en el siguiente bloque;
//   · desarmar o pausar cierran la toma; reanudar con la grabación armada abre una toma nueva;
//   · parado o sin armar, nunca hay grabación;
//   · el resultado no depende del tamaño de bloque.
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "TransportEngine.h"

using namespace eliner;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;
constexpr int kBeat = 24000; // 120 BPM

// Acumula, por frame absoluto, si estaba grabando; y registra los eventos de inicio/fin con su frame absoluto.
struct Trace {
    std::vector<char>         rec;          // 1 si el frame absoluto pertenece a la grabación
    std::vector<std::int64_t> startFrames;  // frame absoluto de cada inicio
    std::vector<std::int64_t> startPos;     // posición de cada inicio
    std::vector<std::int64_t> stopFrames;   // frame absoluto de cada fin
    std::int64_t              cursor = 0;
};

void run(TransportEngine& t, Trace& tr, int frames, int blk) {
    std::vector<float> buf(static_cast<size_t>(blk) * 2);
    for (int pos = 0; pos < frames; pos += blk) {
        const int n = std::min(blk, frames - pos);
        std::fill(buf.begin(), buf.begin() + static_cast<long>(n) * 2, 0.0f);
        (void)t.advance(n);
        t.renderClick(buf.data(), n);
        const auto& rb = t.recordingBlock();
        if (rb.stopped) tr.stopFrames.push_back(tr.cursor);
        if (rb.started) { tr.startFrames.push_back(tr.cursor + rb.firstFrame); tr.startPos.push_back(rb.startPosition); }
        for (int i = 0; i < n; ++i) tr.rec.push_back(static_cast<char>(rb.active && i >= rb.firstFrame));
        tr.cursor += n;
    }
}
std::int64_t recordedFrames(const Trace& tr) { return std::count(tr.rec.begin(), tr.rec.end(), 1); }
std::int64_t firstRec(const Trace& tr) { for (size_t i = 0; i < tr.rec.size(); ++i) if (tr.rec[i]) return static_cast<std::int64_t>(i); return -1; }

void setup(TransportEngine& t, bool armed, int bars) {
    t.prepare(kSR); t.setTempo(120.0); t.setBeatsPerBar(4);
    t.setMetronomeEnabled(false);
    t.setRecordArmed(armed); t.setCountInBars(bars);
}
} // namespace

int main() {
    // ── 1. Con conteo: NO se graba el conteo y la grabación empieza en el frame exacto del primer pulso tras él ──
    for (int blk : {64, 333, 480, 1000, 1001, 4800, 7000}) {   // 333, 1001 y 7000 NO dividen 96000: el inicio cae a mitad de bloque
        TransportEngine t; setup(t, true, 1);
        t.setRunning(true);
        Trace tr; run(t, tr, 8 * kBeat, blk);
        char msg[96];
        std::snprintf(msg, sizeof msg, "bloque %d: la grabación empieza justo en el frame 96000 (tras 1 compás de conteo)", blk);
        check(firstRec(tr) == 4 * kBeat && tr.startFrames.size() == 1 && tr.startFrames[0] == 4 * kBeat, msg);
        std::snprintf(msg, sizeof msg, "bloque %d: se graban exactamente los 4 pulsos posteriores al conteo", blk);
        check(recordedFrames(tr) == 4 * kBeat && tr.startPos[0] == 0, msg);
    }
    // ── 2. Con conteo desde una posición que no es el origen: la posición de inicio es la de grabación ──
    {
        TransportEngine t; setup(t, true, 2);
        t.locate(3 * 960);
        t.setRunning(true);
        Trace tr; run(t, tr, 14 * kBeat, 480);
        check(firstRec(tr) == 8 * kBeat && tr.startPos.size() == 1 && tr.startPos[0] == 3 * 960,
              "conteo de 2 compases desde 2880 ticks: empieza tras 8 pulsos con la posición 2880");
    }
    // ── 3. Sin conteo: empieza con el primer bloque ──
    {
        TransportEngine t; setup(t, true, 0);
        t.setRunning(true);
        Trace tr; run(t, tr, 2 * kBeat, 480);
        check(firstRec(tr) == 0 && recordedFrames(tr) == 2 * kBeat && tr.startFrames.size() == 1 && tr.startPos[0] == 0,
              "conteo = 0: se graba desde el frame 0");
    }
    // ── 4. Armar a mitad de reproducción: empieza en el siguiente bloque, con la posición de ese momento ──
    {
        TransportEngine t; setup(t, false, 1);
        t.setRunning(true);
        Trace tr; run(t, tr, kBeat + 6000, 480);
        check(recordedFrames(tr) == 0, "reproduciendo sin armar: nada se graba");
        t.setRecordArmed(true);
        run(t, tr, 2 * kBeat, 480);
        check(tr.startFrames.size() == 1 && tr.startFrames[0] == kBeat + 6000 && tr.startPos[0] == 1200,
              "armar a mitad de reproducción: empieza en el siguiente bloque, en la posición 1200 (30000 frames a 25 por tick)");
        check(recordedFrames(tr) == 2 * kBeat, "y se graba todo lo que sigue");
    }
    // ── 5. Desarmar cierra la toma ──
    {
        TransportEngine t; setup(t, true, 0);
        t.setRunning(true);
        Trace tr; run(t, tr, kBeat, 480);
        t.setRecordArmed(false);
        run(t, tr, kBeat, 480);
        check(tr.stopFrames.size() == 1 && tr.stopFrames[0] == kBeat && recordedFrames(tr) == kBeat,
              "desarmar: la toma termina en el siguiente bloque y no se graba más");
    }
    // ── 6. Pausar cierra la toma; reanudar con la grabación armada abre otra ──
    {
        TransportEngine t; setup(t, true, 0);
        t.setRunning(true);
        Trace tr; run(t, tr, kBeat, 480);
        t.setRunning(false);
        run(t, tr, 3 * kBeat, 480);
        check(tr.stopFrames.size() == 1 && recordedFrames(tr) == kBeat, "pausar: la toma se cierra");
        t.setRunning(true);
        run(t, tr, kBeat, 480);
        check(tr.startFrames.size() == 2 && tr.startPos[1] == 960 && recordedFrames(tr) == 2 * kBeat,
              "reanudar con la grabación armada: toma NUEVA que empieza en la posición donde se paró (960)");
    }
    // ── 7. Parado o sin armar: nunca hay grabación ──
    {
        TransportEngine t; setup(t, true, 1);
        Trace tr; run(t, tr, 4 * kBeat, 480);
        check(recordedFrames(tr) == 0 && tr.startFrames.empty(), "parado con la grabación armada: nada");
        TransportEngine u; setup(u, false, 1); u.setRunning(true);
        Trace tu; run(u, tu, 6 * kBeat, 480);
        check(recordedFrames(tu) == 0, "reproduciendo sin armar: nada");
    }
    // ── 8. Desarmar durante el conteo: no llega a grabarse nada ──
    {
        TransportEngine t; setup(t, true, 4);
        t.setRunning(true);
        Trace tr; run(t, tr, 2 * kBeat, 480);
        t.setRecordArmed(false);
        run(t, tr, 20 * kBeat, 480);
        check(recordedFrames(tr) == 0 && tr.startFrames.empty() && tr.stopFrames.empty(),
              "desarmar durante el conteo: no hay inicio ni fin de toma");
    }
    // ── 9. Parar durante el conteo: tampoco ──
    {
        TransportEngine t; setup(t, true, 4);
        t.setRunning(true);
        Trace tr; run(t, tr, 3 * kBeat, 480);
        t.setRunning(false);
        run(t, tr, 20 * kBeat, 480);
        check(recordedFrames(tr) == 0 && tr.startFrames.empty(), "pausar durante el conteo: no hay toma");
    }
    // ── 10. locate durante la grabación no parte la toma ──
    {
        TransportEngine t; setup(t, true, 0);
        t.setRunning(true);
        Trace tr; run(t, tr, kBeat, 480);
        t.locate(0);
        run(t, tr, kBeat, 480);
        check(tr.startFrames.size() == 1 && tr.stopFrames.empty() && recordedFrames(tr) == 2 * kBeat,
              "locate durante la grabación: la toma sigue (una sola toma continua)");
    }
    // ── 11. Independencia del tamaño de bloque en un guion completo ──
    {
        auto script = [](int blk) {
            TransportEngine t; setup(t, true, 1);
            t.setRunning(true);
            Trace tr; run(t, tr, 6 * kBeat, blk);
            t.setRunning(false); run(t, tr, 2 * kBeat, blk);
            t.setRunning(true);  run(t, tr, 3 * kBeat, blk);
            return tr;
        };
        const Trace a = script(64), b = script(480), c = script(4800);
        check(a.rec == b.rec && b.rec == c.rec, "el guion conteo → grabar → pausar → reanudar da el MISMO patrón de frames con bloques de 64, 480 y 4800");
        check(a.startFrames == b.startFrames && b.startFrames == c.startFrames, "…y los mismos inicios de toma");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
