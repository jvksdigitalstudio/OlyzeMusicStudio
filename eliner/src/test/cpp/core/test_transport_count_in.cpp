// TransportEngine + conteo previo + política de click (ADR 0035) — reloj y metrónomo REALES, audio medido.
//
// Cada escenario renderiza bloques con advance()+renderClick() (exactamente lo que hace AudioEngine) y mide la
// energía del click en ventanas alrededor de cada pulso. Así se comprueba lo que oye el usuario, no solo el
// estado interno:
//   · el conteo suena con el metrónomo APAGADO (Force) y el click de la grabación respeta el botón;
//   · ClickMode (Always / RecordOnly / CountInOnly) decide qué suena después del conteo;
//   · la grabación empieza en el frame exacto del pulso siguiente al conteo, con el compás en el tiempo 1;
//   · un play repetido, armar a mitad de reproducción o conteo 0 NO inician conteo; parar lo cancela;
//   · el resultado no depende del tamaño de bloque (precisión de muestra);
//   · valores inválidos se ignoran sin tocar el estado.
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
constexpr int kBeat = 24000;                 // 120 BPM, negra: 0,5 s
constexpr int kWin = 4800;                   // 100 ms desde cada pulso: cubre el click completo

struct BeatEvent { std::uint64_t frame; std::uint32_t beatInBar; bool countIn; std::uint32_t countInBar; };

// Renderiza [frames] frames en bloques de [blk], en estéreo intercalado, y registra el pulso publicado tras
// cada bloque que trae pulsos (el valor atómico que lee la UI).
struct Capture {
    std::vector<float> mono;                 // canal izquierdo
    std::vector<BeatEvent> beats;            // un evento por BLOQUE con pulsos (el último del bloque)
};

Capture render(TransportEngine& t, int frames, int blk) {
    Capture c; c.mono.reserve(static_cast<size_t>(frames));
    std::vector<float> buf(static_cast<size_t>(blk) * 2);
    std::uint64_t lastSeq = pulse::sequenceOf(t.pulseSnapshot());
    for (int pos = 0; pos < frames; pos += blk) {
        const int n = std::min(blk, frames - pos);
        std::fill(buf.begin(), buf.begin() + static_cast<long>(n) * 2, 0.0f);
        (void)t.advance(n);
        t.renderClick(buf.data(), n);
        for (int i = 0; i < n; ++i) c.mono.push_back(buf[static_cast<size_t>(i) * 2]);
        const auto snap = t.pulseSnapshot();
        if (pulse::sequenceOf(snap) != lastSeq) {
            lastSeq = pulse::sequenceOf(snap);
            c.beats.push_back({static_cast<std::uint64_t>(pos), pulse::beatInBarOf(snap),
                               pulse::countInOf(snap), pulse::countInBarOf(snap)});
        }
    }
    return c;
}

// Energía RMS de [from, from+len) (recortada al buffer).
double rms(const std::vector<float>& x, int from, int len = kWin) {
    const int a = std::max(0, from), b = std::min(static_cast<int>(x.size()), from + len);
    if (b <= a) return 0.0;
    double s = 0.0;
    for (int i = a; i < b; ++i) s += static_cast<double>(x[static_cast<size_t>(i)]) * x[static_cast<size_t>(i)];
    return std::sqrt(s / (b - a));
}
bool loud(const std::vector<float>& x, int from, int len = kWin)   { return rms(x, from, len) > 1.0e-3; }
bool silent(const std::vector<float>& x, int from, int len = kWin) { return rms(x, from, len) < 1.0e-6; }

// Motor listo: 120 BPM, 4/4, volumen razonable.
void setup(TransportEngine& t, bool metronomeOn, bool armed, int bars, int mode = 0) {
    t.prepare(kSR);
    t.setTempo(120.0); t.setBeatsPerBar(4);
    t.setMetronomeVolume(0.8f);
    t.setMetronomeEnabled(metronomeOn);
    t.setRecordArmed(armed);
    t.setCountInBars(bars);
    t.setClickMode(mode);
}
} // namespace

int main() {
    // ── 1. Conteo de 1 compás con el metrónomo APAGADO: el conteo suena, la grabación no ──
    {
        TransportEngine t; setup(t, /*metro*/false, /*armed*/true, /*bars*/1);
        t.setRunning(true);
        check(t.isRunning(), "arrancar con grabación armada pone el reloj en marcha");
        const Capture c = render(t, 8 * kBeat, 480);
        bool countAudible = true;
        for (int b = 0; b < 4; ++b) countAudible = countAudible && loud(c.mono, b * kBeat);
        check(countAudible, "los 4 pulsos del conteo suenan aunque el metrónomo esté apagado");
        bool recSilent = true;
        for (int b = 4; b < 8; ++b) recSilent = recSilent && silent(c.mono, b * kBeat);
        check(recSilent, "tras el conteo, con el metrónomo apagado, la grabación no tiene click");
        check(t.phase() == TransportPhase::Running, "la fase pasa a Running al empezar la grabación");
    }

    // ── 2. Metrónomo ENCENDIDO, modo Always: suena conteo y grabación ──
    {
        TransportEngine t; setup(t, true, true, 1, static_cast<int>(ClickMode::Always));
        t.setRunning(true);
        const Capture c = render(t, 8 * kBeat, 480);
        bool all = true;
        for (int b = 0; b < 8; ++b) all = all && loud(c.mono, b * kBeat);
        check(all, "Always + metrónomo on: los 8 pulsos (conteo + grabación) suenan");
    }

    // ── 3. CountInOnly: solo el conteo, aunque el metrónomo esté encendido ──
    {
        TransportEngine t; setup(t, true, true, 1, static_cast<int>(ClickMode::CountInOnly));
        t.setRunning(true);
        const Capture c = render(t, 8 * kBeat, 480);
        bool count = true, rec = true;
        for (int b = 0; b < 4; ++b) count = count && loud(c.mono, b * kBeat);
        for (int b = 4; b < 8; ++b) rec = rec && silent(c.mono, b * kBeat);
        check(count, "CountInOnly: el conteo suena");
        check(rec, "CountInOnly: la grabación (metrónomo on) queda en silencio");
    }

    // ── 4. RecordOnly: suena grabando, no reproduciendo ──
    {
        TransportEngine t; setup(t, true, true, 1, static_cast<int>(ClickMode::RecordOnly));
        t.setRunning(true);
        const Capture c = render(t, 8 * kBeat, 480);
        bool all = true;
        for (int b = 0; b < 8; ++b) all = all && loud(c.mono, b * kBeat);
        check(all, "RecordOnly con grabación armada: suenan conteo y grabación");

        TransportEngine p; setup(p, true, /*armed*/false, 1, static_cast<int>(ClickMode::RecordOnly));
        p.setRunning(true);
        const Capture q = render(p, 4 * kBeat, 480);
        bool quiet = true;
        for (int b = 0; b < 4; ++b) quiet = quiet && silent(q.mono, b * kBeat);
        check(quiet, "RecordOnly sin grabación armada (solo reproducir): silencio");
        check(p.phase() == TransportPhase::Running, "sin armar no hay conteo: fase Running desde el primer pulso");
    }

    // ── 5. Sin conteo: duración 0, play repetido y armar a mitad de reproducción ──
    {
        TransportEngine t; setup(t, false, /*armed*/true, /*bars*/0);
        t.setRunning(true);
        const Capture c = render(t, 4 * kBeat, 480);
        bool quiet = true;
        for (int b = 0; b < 4; ++b) quiet = quiet && silent(c.mono, b * kBeat);
        check(quiet, "conteo = 0: no hay conteo ni click con el metrónomo apagado");
        check(!c.beats.empty() && !c.beats.front().countIn, "conteo = 0: el pulso publicado nunca es de conteo");

        TransportEngine u; setup(u, false, /*armed*/false, 1);
        u.setRunning(true);
        (void)render(u, 2 * kBeat + 100, 480);          // reproduciendo, a mitad del tercer pulso
        u.setRecordArmed(true);                          // armar a mitad de reproducción
        u.setRunning(true);                              // play repetido
        const Capture d = render(u, 4 * kBeat, 480);
        bool noCount = true;
        for (const auto& e : d.beats) noCount = noCount && !e.countIn;
        check(noCount, "armar a mitad de reproducción y repetir play NO inicia un conteo");
        check(u.phase() == TransportPhase::Running, "la fase sigue en Running");
    }

    // ── 6. Pulso publicado: marca de conteo, compás del conteo y primer pulso de la grabación ──
    {
        TransportEngine t; setup(t, false, true, 2);
        t.setRunning(true);
        const Capture c = render(t, 10 * kBeat, kBeat);  // un bloque por pulso → un evento por pulso
        check(c.beats.size() == 10, "un bloque por pulso: 10 eventos publicados");
        bool flags = true, bars = true;
        for (size_t i = 0; i < c.beats.size() && i < 10; ++i) {
            const bool expectCount = i < 8;
            if (c.beats[i].countIn != expectCount) flags = false;
            if (expectCount && c.beats[i].countInBar != i / 4) bars = false;
            if (c.beats[i].beatInBar != i % 4) flags = false;
        }
        check(flags, "pulsos 0..7 marcados como conteo, 8 y 9 no; tiempo dentro del compás correcto");
        check(bars, "el compás del conteo publicado es 0 en los 4 primeros y 1 en los 4 siguientes");
        check(c.beats.size() > 8 && c.beats[8].beatInBar == 0 && !c.beats[8].countIn,
              "la grabación empieza en el tiempo 1 de su compás 1");
    }

    // ── 7. Precisión de muestra: la grabación empieza justo en el pulso 4·bars ──
    {
        TransportEngine t; setup(t, true, true, 1, static_cast<int>(ClickMode::CountInOnly));
        t.setRunning(true);
        const Capture c = render(t, 6 * kBeat, 480);
        // El último click del conteo es el del pulso 3; el pulso 4 (frame 96000) ya es de la grabación y no suena.
        check(loud(c.mono, 3 * kBeat, 2000), "el pulso 3 (último del conteo) suena");
        check(silent(c.mono, 4 * kBeat, 2000), "el pulso 4 (primero de la grabación) NO suena, en su frame exacto");
    }

    // ── 8. Independencia del tamaño de bloque (misma señal bit a bit con 64, 480 y 4800 frames) ──
    {
        auto run = [](int blk) {
            TransportEngine t; setup(t, true, true, 1, static_cast<int>(ClickMode::CountInOnly));
            t.setClickSubdivision(2);
            t.setRunning(true);
            return render(t, 8 * kBeat, blk).mono;
        };
        const auto a = run(64), b = run(480), c = run(4800);
        double d1 = 0.0, d2 = 0.0;
        for (size_t i = 0; i < a.size(); ++i) {
            d1 = std::max(d1, static_cast<double>(std::fabs(a[i] - b[i])));
            d2 = std::max(d2, static_cast<double>(std::fabs(a[i] - c[i])));
        }
        check(d1 == 0.0 && d2 == 0.0, "la señal es idéntica con bloques de 64, 480 y 4800 frames (sin deriva por bloque)");
    }

    // ── 9. Subdivisión del conteo con el metrónomo apagado, y sin subdivisiones fuera del conteo ──
    {
        TransportEngine t; setup(t, false, true, 1);
        t.setClickSubdivision(2);
        t.setRunning(true);
        const Capture c = render(t, 8 * kBeat, 480);
        check(loud(c.mono, kBeat / 2 - 200, 1500), "el click de subdivisión suena a mitad del pulso 0 del conteo");
        check(silent(c.mono, 4 * kBeat + kBeat / 2 - 200, 1500), "sin subdivisiones sueltas tras el conteo (metrónomo apagado)");
    }

    // ── 10. Parar cancela el conteo; el siguiente play sin armar es una reproducción normal ──
    {
        TransportEngine t; setup(t, false, true, 4);
        t.setRunning(true);
        (void)render(t, 2 * kBeat + 123, 480);
        check(t.phase() == TransportPhase::CountIn, "a mitad del conteo la fase es CountIn");
        t.setRunning(false);
        check(!t.isRunning() && t.phase() == TransportPhase::Stopped, "parar: reloj parado y fase Stopped");
        const auto p = t.pulseSnapshot();
        check(!pulse::runningOf(p) && !pulse::countInOf(p), "parar publica running=0 y sin marca de conteo");
        t.setRecordArmed(false);
        t.setRunning(true);
        const Capture c = render(t, 4 * kBeat, kBeat);
        bool noCount = true;
        for (const auto& e : c.beats) noCount = noCount && !e.countIn;
        check(noCount && t.phase() == TransportPhase::Running, "tras cancelar y desarmar, el play siguiente no cuenta");
    }

    // ── 11. Parar y volver a grabar: el conteo empieza de nuevo desde el compás 0 ──
    {
        TransportEngine t; setup(t, false, true, 2);
        t.setRunning(true);
        (void)render(t, 5 * kBeat + 10, 480);
        t.setRunning(false);
        t.setRunning(true);
        const Capture c = render(t, 3 * kBeat, kBeat);
        check(!c.beats.empty() && c.beats.front().countIn && c.beats.front().countInBar == 0 && c.beats.front().beatInBar == 0,
              "el conteo vuelve a empezar en el compás 0, tiempo 1");
    }

    // ── 12. Cambio de compás durante el conteo: la grabación empieza en el tiempo 1 ──
    {
        TransportEngine t; setup(t, false, true, 2);
        t.setRunning(true);
        (void)render(t, 5 * kBeat + 10, kBeat);          // 5 pulsos hechos: compás 1, tiempo 2
        t.setBeatsPerBar(3);                             // 4/4 → 3/4 a mitad del 2.º compás
        const Capture c = render(t, 8 * kBeat, kBeat);
        bool found = false;
        for (const auto& e : c.beats) {
            if (!e.countIn) { found = (e.beatInBar == 0); break; }
        }
        check(found, "tras cambiar de compás a mitad del conteo, la grabación arranca en el tiempo 1");
    }

    // ── 13. Valores inválidos: se ignoran sin tocar el estado ──
    {
        TransportEngine t; setup(t, true, true, 1, static_cast<int>(ClickMode::CountInOnly));
        t.setCountInBars(3); t.setCountInBars(-1); t.setCountInBars(8);   // todos inválidos → sigue en 1
        t.setClickMode(7);  t.setClickMode(-3);   t.setClickMode(3);       // 3 es el centinela → sigue CountInOnly
        t.setRunning(true);
        const Capture c = render(t, 8 * kBeat, 480);
        bool count = true, rec = true;
        for (int b = 0; b < 4; ++b) count = count && loud(c.mono, b * kBeat);
        for (int b = 4; b < 8; ++b) rec = rec && silent(c.mono, b * kBeat);
        check(count && rec, "conteo inválido (3, -1, 8) y modo inválido (7, -3, Count) se ignoran: sigue 1 compás + CountInOnly");
    }

    // ── 14. Contrato histórico: sin grabación armada todo se comporta como antes del ADR 0035 ──
    {
        TransportEngine t; setup(t, true, false, 0, static_cast<int>(ClickMode::Always));
        t.setRunning(true);
        const Capture c = render(t, 4 * kBeat, 480);
        bool all = true;
        for (int b = 0; b < 4; ++b) all = all && loud(c.mono, b * kBeat);
        check(all, "metrónomo on, sin grabar: suenan todos los pulsos, como siempre");
        TransportEngine o; setup(o, false, false, 0);
        o.setRunning(true);
        const Capture q = render(o, 4 * kBeat, 480);
        bool quiet = true;
        for (int b = 0; b < 4; ++b) quiet = quiet && silent(q.mono, b * kBeat);
        check(quiet, "metrónomo off, sin grabar: silencio total, como siempre");
    }

    // ── 15. Apagar el metrónomo durante el conteo no corta el conteo ──
    {
        TransportEngine t; setup(t, true, true, 1, static_cast<int>(ClickMode::CountInOnly));
        t.setRunning(true);
        (void)render(t, kBeat + 100, 480);
        t.setMetronomeEnabled(false);
        const Capture c = render(t, 3 * kBeat, 480);
        // Los pulsos 2 y 3 del conteo caen en los frames kBeat-100 y 2·kBeat-100 de esta captura.
        check(loud(c.mono, kBeat - 100 - 100) && loud(c.mono, 2 * kBeat - 100 - 100),
              "con el metrónomo apagado a mitad del conteo, los pulsos que faltan siguen sonando");
    }

    // ── 16. Desarmar durante el conteo lo cancela y el transporte sigue reproduciendo ──
    {
        TransportEngine t; setup(t, false, true, 4);
        t.setRunning(true);
        (void)render(t, 2 * kBeat + 123, 480);
        check(t.phase() == TransportPhase::CountIn, "a mitad del conteo la fase es CountIn");
        t.setRecordArmed(false);
        check(t.isRunning() && t.phase() == TransportPhase::Running,
              "desarmar durante el conteo: el reloj sigue en marcha y la fase pasa a Running");
        const Capture c = render(t, 4 * kBeat, kBeat);
        bool noCount = true;
        for (const auto& e : c.beats) noCount = noCount && !e.countIn;
        check(noCount, "ningún pulso posterior es de conteo");
        t.setRecordArmed(true);                          // volver a armar con el transporte en marcha
        const Capture d = render(t, 2 * kBeat, kBeat);
        bool still = true;
        for (const auto& e : d.beats) still = still && !e.countIn;
        check(still && t.phase() == TransportPhase::Running, "armar de nuevo en marcha NO inicia otro conteo");
    }

    // ── 17. Duración por defecto: 1 compás ──
    {
        TransportEngine t; t.prepare(kSR); t.setTempo(120.0); t.setBeatsPerBar(4);
        t.setRecordArmed(true); t.setRunning(true);       // sin setCountInBars: vale el valor por defecto
        const Capture c = render(t, 6 * kBeat, kBeat);
        int counted = 0;
        for (const auto& e : c.beats) if (e.countIn) ++counted;
        check(counted == 4 * kCountInBarsDefault && kCountInBarsDefault == 1, "por defecto el conteo dura 1 compás (4 pulsos en 4/4)");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
