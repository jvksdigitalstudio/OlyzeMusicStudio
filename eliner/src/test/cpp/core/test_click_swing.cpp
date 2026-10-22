// Swing de la subdivisión del click (ADR 0039): se mide DÓNDE cae cada click de subdivisión en el audio renderizado.
//   · con 2 por pulso el click de en medio cae al swing% del pulso; con 4, cada par se reparte swing% / (100-swing)%;
//   · el PULSO no se mueve nunca; con tresillos o sin subdivisión el swing no hace nada;
//   · 50% es idéntico, muestra a muestra, a no tener swing (contrato histórico);
//   · valores fuera de rango se ignoran; un cambio de swing se aplica en el pulso siguiente;
//   · independencia del tamaño de bloque.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "Metronome.h"
#include "TempoClock.h"

using namespace eliner;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;
constexpr int kBeat = 24000; // 120 BPM

// Renderiza [beats] pulsos y devuelve la señal mono. Los clicks se localizan después por su ataque.
std::vector<float> render(int subdivision, int swingPercent, int beats, int blk, int* setSwingAfterBeats = nullptr, int newSwing = 50) {
    TempoClock clock; clock.prepare(kSR); clock.setTempo(120.0); clock.setBeatsPerBar(8); clock.start();
    Metronome m; m.prepare(kSR); m.setEnabled(true); m.setVolume(1.0f);
    m.setSubdivision(subdivision); m.setSwing(swingPercent);
    std::vector<float> out; std::vector<float> buf(static_cast<size_t>(blk) * 2);
    const int total = beats * kBeat;
    for (int pos = 0; pos < total; pos += blk) {
        const int n = std::min(blk, total - pos);
        if (setSwingAfterBeats && pos >= *setSwingAfterBeats * kBeat) { m.setSwing(newSwing); setSwingAfterBeats = nullptr; }
        std::fill(buf.begin(), buf.begin() + static_cast<long>(n) * 2, 0.0f);
        TempoClock::Beat b[TempoClock::kMaxBeatsPerBlock];
        const int nb = clock.advance(n, b, TempoClock::kMaxBeatsPerBlock);
        m.render(buf.data(), n, b, nb, static_cast<double>(kBeat));
        for (int i = 0; i < n; ++i) out.push_back(buf[static_cast<size_t>(i) * 2]);
    }
    return out;
}

// Frames donde EMPIEZA un click. Un click es una oscilación amortiguada que cruza cualquier umbral varias veces,
// así que se mide la ENVOLVENTE: máximo |x| por ventanas de 96 frames; un click empieza cuando esa envolvente
// sube por encima del umbral viniendo de por debajo (una sola vez por click). Resolución = una ventana.
constexpr int kWin = 96;
std::vector<int> onsets(const std::vector<float>& x, int /*unused*/ = 0) {
    std::vector<int> r; bool above = false;
    for (size_t k = 0; k * kWin < x.size(); ++k) {
        float mx = 0.f;
        for (size_t i = k * kWin; i < std::min(x.size(), (k + 1) * kWin); ++i) mx = std::max(mx, std::fabs(x[i]));
        const bool now = mx > 2.0e-3f;
        if (now && !above) r.push_back(static_cast<int>(k * kWin));
        above = now;
    }
    return r;
}
// Tolerancia de la medida: una ventana más el ataque del click.
bool near(int a, int b, int tol = 130) { return std::abs(a - b) <= tol; }
} // namespace

int main() {
    // ── Validación ──
    {
        Metronome m; m.prepare(kSR);
        check(m.swingPercent() == kDefaultClickSwingPercent && kDefaultClickSwingPercent == 50, "por defecto: 50% (recto)");
        m.setSwing(67); check(m.swingPercent() == 67, "67 se acepta");
        m.setSwing(49); m.setSwing(76); m.setSwing(-5); m.setSwing(1000);
        check(m.swingPercent() == 67, "49, 76, negativos y enormes se IGNORAN (no se recortan)");
        m.setSwing(50); m.setSwing(75);
        check(m.swingPercent() == 75, "los extremos 50 y 75 se aceptan");
    }
    // ── 2 por pulso (corcheas): el click de en medio cae al swing% del pulso ──
    for (int sw : {50, 58, 62, 67, 75}) {
        const auto x = render(2, sw, 3, 480);
        const auto o = onsets(x);
        const int expectedOff = static_cast<int>(std::lround(kBeat * sw / 100.0));
        char msg[96];
        std::snprintf(msg, sizeof msg, "2 por pulso, swing %d%%: pulso en 0 y click intermedio al %d%% (frame %d)", sw, sw, expectedOff);
        check(o.size() >= 6 && near(o[0], 0) && near(o[1], expectedOff) && near(o[2], kBeat) && near(o[3], kBeat + expectedOff), msg);
    }
    // ── 4 por pulso (semicorcheas): cada par se reparte swing% / (100 − swing)% de media negra ──
    {
        const int sw = 67, half = kBeat / 2;
        const auto o = onsets(render(4, sw, 2, 480), 1000);
        const int e1 = static_cast<int>(std::lround(half * sw / 100.0));
        check(o.size() >= 8 && near(o[0], 0) && near(o[1], e1) && near(o[2], half) && near(o[3], half + e1) && near(o[4], kBeat),
              "4 por pulso, swing 67%: clicks en 0, 0,67·h, h, h+0,67·h y el siguiente pulso en el frame exacto");
    }
    // ── El pulso nunca se mueve ──
    {
        bool ok = true;
        for (int sw : {50, 58, 67, 75}) for (int sub : {1, 2, 3, 4}) {
            const auto o = onsets(render(sub, sw, 4, 480), 1000);
            for (int b = 0; b < 4; ++b) {
                bool found = false; for (int v : o) found = found || near(v, b * kBeat);
                ok = ok && found;
            }
        }
        check(ok, "con cualquier swing y subdivisión, los 4 pulsos caen en 0, 24000, 48000 y 72000");
    }
    // ── Sin efecto con tresillos y sin subdivisión ──
    {
        const auto straight = render(3, 50, 3, 480), swung = render(3, 75, 3, 480);
        check(straight == swung, "tresillos (3 por pulso): el swing no cambia ni una muestra");
        const auto s1 = render(1, 50, 3, 480), s2 = render(1, 75, 3, 480);
        check(s1 == s2, "sin subdivisión: el swing no cambia ni una muestra");
    }
    // ── 50% idéntico a no tener swing: contrato histórico ──
    {
        const auto a = render(2, 50, 4, 480), b = render(4, 50, 4, 480);
        // Sin swing los huecos son iguales: la separación entre clicks sucesivos es framesPerBeat / n.
        const auto o2 = onsets(a), o4 = onsets(b, 1000);
        bool even2 = o2.size() >= 8, even4 = o4.size() >= 16;
        for (size_t i = 1; even2 && i < 8; ++i) even2 = near(o2[i] - o2[i - 1], kBeat / 2);
        for (size_t i = 1; even4 && i < 16; ++i) even4 = near(o4[i] - o4[i - 1], kBeat / 4);
        check(even2 && even4, "50%: los clicks equidistan exactamente (kBeat/2 y kBeat/4)");
    }
    // ── Cambiar el swing: se aplica en el pulso siguiente, no a mitad del actual ──
    {
        int after = 2;                                     // a partir del pulso 2 (frame 48000) pasa de recto a 67%
        const auto o = onsets(render(2, 50, 4, 480, &after, 67));
        check(o.size() >= 8 && near(o[1], kBeat / 2) && near(o[3], kBeat + kBeat / 2),
              "los pulsos 0 y 1 siguen rectos");
        const int off = static_cast<int>(std::lround(kBeat * 0.67));
        check(near(o[5], 2 * kBeat + off) && near(o[7], 3 * kBeat + off), "desde el pulso 2 el click intermedio cae al 67%");
    }
    // ── Independencia del tamaño de bloque, con swing ──
    {
        const auto a = render(4, 62, 4, 64), b = render(4, 62, 4, 480), c = render(4, 62, 4, 4800);
        double d1 = 0, d2 = 0;
        for (size_t i = 0; i < a.size(); ++i) { d1 = std::max(d1, static_cast<double>(std::fabs(a[i] - b[i]))); d2 = std::max(d2, static_cast<double>(std::fabs(a[i] - c[i]))); }
        check(d1 == 0.0 && d2 == 0.0, "con swing, la señal es idéntica con bloques de 64, 480 y 4800 frames");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
