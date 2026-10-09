// ADR 0031 — Click profesional del metrónomo: sonidos, acento y subdivisión.
//
// Prueba el código de PRODUCCIÓN (Metronome.cpp). Todo se mide sobre la señal
// renderizada, no sobre estado interno:
//   · "Clásico" es el sonido histórico (compatibilidad): lo fija test_metronome; aquí se
//     comprueba que los demás presets son DISTINTOS y que ninguno supera el techo de nivel
//   · sin NaN/Inf, determinista, y el pico no crece con parciales ni ruido
//   · acento desactivado: el primer tiempo suena igual que el resto
//   · subdivisión: n clicks por pulso, en el frame exacto, más suaves que el pulso
//   · independencia del tamaño de bloque (muestra a muestra) también con subdivisión
//   · cada pulso resincroniza: un cambio de tempo no acumula error
//   · valores inválidos se ignoran (no se recortan a otro valor)
//   · parada: resetSubdivision() no deja clicks sueltos
#include "Metronome.h"
#include "TempoClock.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>

using eliner::ClickSound;
using eliner::Metronome;
using eliner::TempoClock;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;
constexpr double kFPB = 24000.0; // 120 BPM

Metronome ready(int sound = 0, float volume = 1.0f) {
    Metronome m; m.prepare(kSR); m.setVolume(volume); m.setEnabled(true); m.setSound(sound);
    std::vector<float> warm(2 * 4800, 0.0f);
    m.render(warm.data(), 4800, nullptr, 0);
    return m;
}

// Renderiza [frames] con pulsos en [beatFrames] (beatInBar = índice mod 4), en bloques de [blk].
std::vector<float> run(Metronome& m, int frames, const std::vector<int>& beatFrames,
                       double fpb = kFPB, int blk = 480) {
    std::vector<float> out(2 * frames, 0.0f);
    size_t nextBeat = 0;
    for (int pos = 0; pos < frames; pos += blk) {
        const int n = std::min(blk, frames - pos);
        std::vector<TempoClock::Beat> beats;
        while (nextBeat < beatFrames.size() && beatFrames[nextBeat] < pos + n) {
            beats.push_back({beatFrames[nextBeat] - pos, static_cast<std::uint32_t>(nextBeat % 4)});
            ++nextBeat;
        }
        m.render(out.data() + 2 * pos, n, beats.data(), static_cast<int>(beats.size()), fpb);
    }
    return out;
}

float peak(const std::vector<float>& b, int from, int to) {
    float p = 0.0f;
    for (int i = from; i < to; ++i) p = std::fmax(p, std::fabs(b[2 * i]));
    return p;
}
// Frames donde empieza un click: la señal pasa de silencio (< 1e-4) a sonido tras ≥ [gap] frames mudos.
std::vector<int> onsets(const std::vector<float>& b, int gap = 600) {
    std::vector<int> v; int quiet = gap + 1;
    const int n = static_cast<int>(b.size() / 2);
    for (int i = 0; i < n; ++i) {
        if (std::fabs(b[2 * i]) > 1e-4f) { if (quiet > gap) v.push_back(i); quiet = 0; }
        else ++quiet;
    }
    return v;
}
bool finite(const std::vector<float>& b) {
    for (float x : b) if (!std::isfinite(x)) return false;
    return true;
}
bool identical(const std::vector<float>& a, const std::vector<float>& b) { return a == b; }
} // namespace

int main() {
    const std::vector<int> beats4 = {0, 24000, 48000, 72000};

    // ── Presets: distintos entre sí, acotados, deterministas, finitos ──
    {
        std::vector<std::vector<float>> outs;
        for (int s = 0; s < static_cast<int>(ClickSound::Count); ++s) {
            Metronome m = ready(s);
            outs.push_back(run(m, 4800, {0}));
        }
        bool distinct = true, bounded = true, fin = true, audible = true;
        for (size_t i = 0; i < outs.size(); ++i) {
            if (peak(outs[i], 0, 4800) > 0.8f + 1e-4f) bounded = false;
            if (peak(outs[i], 0, 4800) < 0.05f) audible = false;
            fin = fin && finite(outs[i]);
            for (size_t j = i + 1; j < outs.size(); ++j) if (identical(outs[i], outs[j])) distinct = false;
        }
        check(distinct, "los 5 sonidos producen señales distintas");
        check(bounded, "ningún preset supera el techo de nivel (0,8): parciales y ruido no suben el pico");
        check(fin, "ningún preset produce NaN/Inf");
        check(audible, "todos los presets son audibles a volumen 1");
        Metronome a = ready(4), b = ready(4);
        check(identical(run(a, 4800, {0}), run(b, 4800, {0})), "el Hi-hat (ruido) es DETERMINISTA: misma señal en cada ejecución");
    }

    // ── Cowbell dura más que Wood (las caídas son distintas de verdad) ──
    {
        Metronome w = ready(1), c = ready(3);
        auto ow = run(w, 9600, {0}), oc = run(c, 9600, {0});
        check(peak(oc, 2400, 4800) > peak(ow, 2400, 4800) * 3.0f, "a los 50–100 ms el cencerro aún suena y la madera ya se apagó");
    }

    // ── Acento ──
    {
        Metronome on = ready(0), off = ready(0);
        off.setAccentEnabled(false);
        auto a = run(on, 96000, beats4), b = run(off, 96000, beats4);
        const float accentOn  = peak(a, 0, 2400),     beatOn  = peak(a, 24000, 26400);
        const float accentOff = peak(b, 0, 2400),     beatOff = peak(b, 24000, 26400);
        check(accentOn > beatOn * 1.2f, "con acento: el primer tiempo es claramente más fuerte");
        check(std::fabs(accentOff - beatOff) < 1e-3f, "sin acento: el primer tiempo suena igual que el resto");
    }

    // ── Subdivisión ──
    {
        for (int n : {1, 2, 3, 4}) {
            Metronome m = ready(0); m.setSubdivision(n);
            auto o = run(m, 24000, {0});                 // un solo pulso
            const auto on = onsets(o, 300);
            char msg[96]; std::snprintf(msg, sizeof msg, "subdivisión %d: %d click(s) por pulso", n, n);
            check(static_cast<int>(on.size()) == n, msg);
        }
        Metronome m = ready(0); m.setSubdivision(2);
        auto o = run(m, 48000, {0});
        const auto on = onsets(o, 300);
        check(on.size() == 2 && std::abs(on[1] - 12000) <= 1, "corcheas: el click intermedio cae a media negra (frame 12000 ±1)");
        check(peak(o, on[1], on[1] + 600) < peak(o, 0, 600) * 0.7f, "el click de subdivisión es más suave que el del pulso");
        Metronome t = ready(0); t.setSubdivision(3);
        auto ot = run(t, 24000, {0});
        const auto ont = onsets(ot, 300);
        check(ont.size() == 3 && std::abs(ont[1] - 8000) <= 1 && std::abs(ont[2] - 16000) <= 1,
              "tresillos: clicks en 1/3 y 2/3 del pulso (±1 frame)");
    }

    // ── Independencia del tamaño de bloque, con subdivisión y sonidos con ruido/parciales ──
    {
        for (int s : {0, 3, 4}) {
            Metronome a = ready(s), b = ready(s);
            a.setSubdivision(4); b.setSubdivision(4);
            check(identical(run(a, 72000, beats4, kFPB, 480), run(b, 72000, beats4, kFPB, 97)),
                  "salida idéntica con bloques de 480 y de 97 frames (subdivisión 4)");
        }
    }

    // ── Resincronización por pulso: con tempo más rápido en el 2.º pulso la subdivisión lo sigue ──
    {
        Metronome m = ready(0); m.setSubdivision(2);
        // pulso 0 a 120 BPM (24000 fr), pulso 1 llega a 24000 y a partir de ahí 240 BPM (12000 fr)
        std::vector<float> out(2 * 36000, 0.0f);
        TempoClock::Beat b0{0, 0};
        m.render(out.data(), 24000, &b0, 1, 24000.0);
        TempoClock::Beat b1{0, 1};
        m.render(out.data() + 2 * 24000, 12000, &b1, 1, 12000.0);
        const auto on = onsets(out, 300);
        check(on.size() == 4 && std::abs(on[1] - 12000) <= 1 && std::abs(on[3] - 30000) <= 1,
              "tras un cambio de tempo la subdivisión se recoloca en el siguiente pulso (sin acumular error)");
    }

    // ── Valores inválidos: se ignoran ──
    {
        Metronome m = ready(2); m.setSubdivision(3);
        m.setSound(-1); m.setSound(99); m.setSubdivision(0); m.setSubdivision(5); m.setSubdivision(-3);
        check(m.sound() == 2 && m.subdivision() == 3, "sonido o subdivisión fuera de rango se IGNORAN (no se recortan a otro valor)");
    }

    // ── Parada: sin clicks sueltos ──
    {
        Metronome m = ready(0); m.setSubdivision(4);
        std::vector<float> out(2 * 2000, 0.0f);
        TempoClock::Beat b0{0, 0};
        m.render(out.data(), 2000, &b0, 1, kFPB);       // pulso + (aún) sin llegar al primer sub (6000)
        m.resetSubdivision();
        std::vector<float> after(2 * 30000, 0.0f);
        m.render(after.data(), 30000, nullptr, 0, kFPB);
        // La cola del click del pulso (≈ 110 ms) puede seguir sonando; a partir de 8000 frames ya no
        // queda nada y el primer click intermedio habría caído en el frame 6000.
        check(peak(after, 8000, 30000) == 0.0f, "tras resetSubdivision() no suena ningún click intermedio");
    }

    // ── Desactivado: coste cero y silencio, aunque haya subdivisión ──
    {
        Metronome m; m.prepare(kSR); m.setSubdivision(4);
        auto o = run(m, 24000, {0});
        check(peak(o, 0, 24000) == 0.0f, "metrónomo desactivado: silencio absoluto, con subdivisión incluida");
    }

    std::fprintf(stdout, gFailures ? "== %d FALLO(S) ==\n" : "== TODO OK ==\n", gFailures);
    return gFailures ? 1 : 0;
}
