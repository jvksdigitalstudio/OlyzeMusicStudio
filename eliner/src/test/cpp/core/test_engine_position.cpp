// ADR 0036 de extremo a extremo: AudioEngine REAL (cola de comandos → hilo de audio) contra el backend falso.
// test_transport_position cubre la lógica; este cubre el CABLEADO de la posición:
//   setTransportPosition → cola → TransportEngine::locate → transportPositionTicks (lectura atómica).
// Y que parar PAUSA (conserva la posición) mientras que setTransportPosition(0) rebobina.
#include "AudioEngine.h"
#include "Position.h"

#include <cstdio>
#include <memory>
#include <vector>

using eliner::AudioEngine;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kBeat = 24000;
struct Rig {
    std::unique_ptr<AudioEngine> e = std::make_unique<AudioEngine>();
    std::vector<float> buf = std::vector<float>(2 * 480);
    Rig() { e->start(0); e->setTempo(120.0f); e->setBeatsPerBar(4); e->setMetronomeEnabled(false); }
    ~Rig() { e->stop(); }
    void render(int frames) { for (int p = 0; p < frames; p += 480) e->onAudioReady(nullptr, buf.data(), 480); }
};
} // namespace

int main() {
    {
        Rig r;
        check(r.e->transportPositionTicks() == 0, "motor nuevo: posición 0");
        r.e->setTransportRunning(true);
        r.render(2 * kBeat + 12000);
        check(r.e->transportPositionTicks() == 2400, "por la cola: 2,5 pulsos → 2400 ticks");
        r.e->setTransportRunning(false);
        r.render(480);
        const auto paused = r.e->transportPositionTicks();
        r.render(3 * kBeat);
        check(r.e->transportPositionTicks() == paused && paused >= 2400, "parar PAUSA: la posición se conserva y no cambia");
        r.e->setTransportRunning(true);
        r.render(kBeat);
        check(r.e->transportPositionTicks() == paused + 960, "reanudar continúa desde donde se paró");
    }
    {
        Rig r;
        r.e->setTransportPosition(4 * 960);
        r.render(480);
        check(r.e->transportPositionTicks() == 3840, "setTransportPosition en parado coloca el transporte (3840)");
        r.e->setTransportRunning(true);
        r.render(kBeat);
        check(r.e->transportPositionTicks() == 3840 + 960, "y al arrancar continúa desde ahí");
        r.e->setTransportPosition(0);
        r.render(480);
        check(r.e->transportPositionTicks() < 960, "setTransportPosition(0) con el transporte en marcha rebobina");
    }
    {
        Rig r;
        r.e->setTransportPosition(-5000);
        r.e->setTransportPosition(1LL << 40);                    // se recorta, no se desborda
        r.render(480);
        check(r.e->transportPositionTicks() == eliner::kMaxPositionTicks, "una posición enorme se recorta al máximo (exacto en float)");
    }
    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
