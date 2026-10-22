// ADR 0035 de extremo a extremo: AudioEngine REAL (cola de comandos, applyParameter, TransportEngine) contra el
// backend de audio falso. Los tests de test_transport_count_in cubren la lógica; este cubre el CABLEADO:
//   setRecordArmed / setCountInBars / setClickMode  →  cola  →  hilo de audio  →  conteo audible y pulso publicado.
//
// También fija que un valor inválido NO se encola (la validación del hilo de control) y que el orden de los
// comandos se respeta: armar y arrancar en la misma ráfaga inicia el conteo.
#include "AudioEngine.h"
#include "ClickPolicy.h"
#include "CountIn.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

using eliner::AudioEngine;
namespace pulse = eliner::pulse;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kBeat = 24000; // 120 BPM a 48 kHz

float peak(const std::vector<float>& b, int fromFrame, int toFrame) {
    float p = 0.0f;
    const int n = static_cast<int>(b.size()) / 2;
    for (int i = std::max(0, fromFrame); i < std::min(n, toFrame); ++i) p = std::fmax(p, std::fabs(b[2 * static_cast<size_t>(i)]));
    return p;
}

struct Rig {
    std::unique_ptr<AudioEngine> e = std::make_unique<AudioEngine>();
    std::vector<float> buf = std::vector<float>(2 * 480);
    std::vector<float> all;
    Rig() { e->start(0); e->setTempo(120.0f); e->setBeatsPerBar(4); e->setMetronomeVolume(1.0f); }
    ~Rig() { e->stop(); }
    void render(int frames) {
        for (int pos = 0; pos < frames; pos += 480) {
            std::fill(buf.begin(), buf.end(), 0.0f);
            e->onAudioReady(nullptr, buf.data(), 480);
            all.insert(all.end(), buf.begin(), buf.end());
        }
    }
};
} // namespace

int main() {
    // ── Armar + arrancar en la misma ráfaga: conteo de 1 compás con el metrónomo apagado ──
    {
        Rig r;
        r.e->setMetronomeEnabled(false);
        r.e->setCountInBars(1);
        r.e->setRecordArmed(true);
        r.e->setTransportRunning(true);
        r.render(8 * kBeat);
        bool count = true, rec = true;
        for (int b = 0; b < 4; ++b) count = count && peak(r.all, b * kBeat, b * kBeat + 4800) > 0.05f;
        for (int b = 4; b < 8; ++b) rec = rec && peak(r.all, b * kBeat, b * kBeat + 4800) == 0.0f;
        check(count, "por la cola de comandos: el conteo de 1 compás suena con el metrónomo apagado");
        check(rec, "…y la grabación posterior queda en silencio (metrónomo apagado)");
        const auto p = r.e->pulseSnapshot();
        check(pulse::runningOf(p) && !pulse::countInOf(p), "el pulso publicado tras el conteo ya no marca conteo");
    }

    // ── El pulso publicado marca el conteo mientras dura ──
    {
        Rig r;
        r.e->setCountInBars(2);
        r.e->setRecordArmed(true);
        r.e->setTransportRunning(true);
        r.render(5 * kBeat + 100);              // pulso 5: segundo compás del conteo, tiempo 2
        const auto p = r.e->pulseSnapshot();
        check(pulse::countInOf(p) && pulse::countInBarOf(p) == 1 && pulse::beatInBarOf(p) == 1,
              "a mitad del 2.º compás del conteo: marca de conteo, compás 1, tiempo 2");
        r.e->setTransportRunning(false);
        r.render(480);
        const auto q = r.e->pulseSnapshot();
        check(!pulse::runningOf(q) && !pulse::countInOf(q), "parar durante el conteo publica running=0 sin marca de conteo");
    }

    // ── ClickMode por la cola ──
    {
        Rig r;
        r.e->setMetronomeEnabled(true);
        r.e->setCountInBars(1);
        r.e->setClickMode(static_cast<int>(eliner::ClickMode::CountInOnly));
        r.e->setRecordArmed(true);
        r.e->setTransportRunning(true);
        r.render(8 * kBeat);
        check(peak(r.all, 3 * kBeat, 3 * kBeat + 4800) > 0.05f && peak(r.all, 5 * kBeat, 5 * kBeat + 4800) == 0.0f,
              "CountInOnly por la cola: suena el conteo y se silencia la grabación (aunque el metrónomo esté encendido)");
    }

    // ── La validación del hilo de control: un conteo inexistente NO se encola ──
    {
        Rig r;
        r.e->setMetronomeEnabled(false);
        r.e->setCountInBars(2);
        r.e->setCountInBars(3);                 // inválido: se ignora, no se «recorta» a 2 ni a 4
        r.e->setClickMode(42);                  // inválido: se ignora
        r.e->setRecordArmed(true);
        r.e->setTransportRunning(true);
        r.render(10 * kBeat);
        check(peak(r.all, 7 * kBeat, 7 * kBeat + 4800) > 0.05f, "conteo = 2 sigue vigente tras un 3 inválido (el pulso 7 aún es de conteo)");
        check(peak(r.all, 9 * kBeat, 9 * kBeat + 4800) == 0.0f, "…y la grabación empieza en el pulso 8 (2 compases)");
    }

    // ── Sin armar no hay conteo, aunque haya duración configurada ──
    {
        Rig r;
        r.e->setMetronomeEnabled(false);
        r.e->setCountInBars(4);
        r.e->setTransportRunning(true);
        r.render(4 * kBeat);
        check(peak(r.all, 0, 4 * kBeat) == 0.0f, "reproducir sin grabación armada: sin conteo y sin click (metrónomo apagado)");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
