// ADR 0028 — Metronome (click sintetizado sobre los pulsos del TempoClock).
//
// Prueba el código de PRODUCCIÓN (Metronome.cpp + TempoClock.cpp). Propiedades:
//   · el click arranca en el frame exacto del pulso
//   · independencia del tamaño de bloque (salida idéntica muestra a muestra)
//   · acento: primer tiempo más agudo y más fuerte
//   · volumen con curva cuadrática; volumen 0 = silencio
//   · desactivado: no suena y no cuesta nada (isIdle); suma, no sobrescribe
//   · activar/desactivar y mover el volumen no producen saltos bruscos
#include "Metronome.h"
#include "TempoClock.h"
#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>

using eliner::Metronome;
using eliner::TempoClock;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;

// Metrónomo activado, con la ganancia ya asentada en 1.0 (volumen 1).
Metronome ready(float volume = 1.0f) {
    Metronome m; m.prepare(kSR); m.setVolume(volume); m.setEnabled(true);
    std::vector<float> warm(2 * 4800, 0.0f);                 // 100 ms: la ganancia suavizada llega a su valor
    m.render(warm.data(), 4800, nullptr, 0);
    return m;
}
float peak(const std::vector<float>& b, int fromFrame, int toFrame) {
    float p = 0.0f;
    for (int i = fromFrame; i < toFrame; ++i) p = std::fmax(p, std::fabs(b[2 * i]));
    return p;
}
int zeroCrossings(const std::vector<float>& b, int fromFrame, int toFrame) {
    int z = 0;
    for (int i = fromFrame + 1; i < toFrame; ++i) if ((b[2 * (i - 1)] < 0) != (b[2 * i] < 0)) ++z;
    return z;
}
}

int main() {
    // ── 1. El click arranca en el frame exacto ──
    {
        Metronome m = ready();
        std::vector<float> out(2 * 2000, 0.0f);
        const TempoClock::Beat b{700, 1};
        m.render(out.data(), 2000, &b, 1);
        bool silentBefore = true; for (int i = 0; i < 700; ++i) silentBefore = silentBefore && out[2*i] == 0.0f && out[2*i+1] == 0.0f;
        check(silentBefore, "antes del frame del pulso la salida es exactamente 0");
        check(out[2 * 700] == 0.0f, "en el frame del pulso la fase del seno es 0 (arranque sin pop)");
        check(std::fabs(out[2 * 705]) > 0.0f, "5 frames después el click ya suena");
        check(out[2 * 705] == out[2 * 705 + 1], "el click es idéntico en L y R (centrado)");
    }

    // ── 2. Independencia del tamaño de bloque (salida idéntica muestra a muestra) ──
    {
        const int total = 6000;
        const TempoClock::Beat beats[] = {{100, 0}, {2500, 1}, {4000, 2}};
        Metronome ref = ready(0.8f);
        std::vector<float> a(2 * total, 0.0f);
        ref.render(a.data(), total, beats, 3);

        bool identical = true;
        for (int blk : {1, 7, 64, 333, 1024}) {
            Metronome m = ready(0.8f);
            std::vector<float> b(2 * total, 0.0f);
            for (int pos = 0; pos < total; pos += blk) {
                const int n = std::min(blk, total - pos);
                TempoClock::Beat local[3]; int cnt = 0;
                for (auto& e : beats) if (e.frameOffset >= pos && e.frameOffset < pos + n) local[cnt++] = {e.frameOffset - pos, e.beatInBar};
                m.render(b.data() + 2 * pos, n, local, cnt);
            }
            for (std::size_t i = 0; i < a.size(); ++i) if (a[i] != b[i]) { identical = false; break; }
        }
        check(identical, "bloques de 1, 7, 64, 333 y 1024 frames producen una salida IDÉNTICA bit a bit");
    }

    // ── 3. Acento: más agudo y más fuerte en el primer tiempo ──
    {
        Metronome m = ready();
        std::vector<float> acc(2 * 2000, 0.0f), nor(2 * 2000, 0.0f);
        const TempoClock::Beat bA{0, 0}, bN{0, 2};
        m.render(acc.data(), 2000, &bA, 1);
        Metronome m2 = ready();
        m2.render(nor.data(), 2000, &bN, 1);
        const int zA = zeroCrossings(acc, 0, 480), zN = zeroCrossings(nor, 0, 480);   // 10 ms
        // 1500 Hz → ~30 cruces en 10 ms; 1000 Hz → ~20
        check(zA >= 28 && zA <= 31, "acento ≈ 1500 Hz (≈30 cruces por cero en 10 ms)");
        check(zN >= 18 && zN <= 21, "pulso normal ≈ 1000 Hz (≈20 cruces por cero en 10 ms)");
        check(peak(acc, 0, 2000) > peak(nor, 0, 2000), "el acento suena más fuerte que el pulso normal");
        check(peak(acc, 0, 2000) <= 0.8f, "el pico no supera 0.8 (margen bajo 0 dBFS)");
    }

    // ── 4. Volumen: curva cuadrática y silencio a 0 ──
    {
        const TempoClock::Beat b{0, 0};
        Metronome full = ready(1.0f), half = ready(0.5f), zero = ready(0.0f);
        std::vector<float> a(2 * 2000, 0.0f), h(2 * 2000, 0.0f), z(2 * 2000, 0.0f);
        full.render(a.data(), 2000, &b, 1);
        half.render(h.data(), 2000, &b, 1);
        zero.render(z.data(), 2000, &b, 1);
        const float ratio = peak(h, 0, 2000) / peak(a, 0, 2000);
        check(std::fabs(ratio - 0.25f) < 0.01f, "volumen 0.5 → ganancia 0.25 (curva cuadrática)");
        check(peak(z, 0, 2000) == 0.0f, "volumen 0 → silencio absoluto");
    }

    // ── 5. El click termina por sí solo y deja de costar ──
    {
        Metronome m = ready();
        std::vector<float> out(2 * 24000, 0.0f);   // 500 ms
        const TempoClock::Beat b{0, 0};
        m.render(out.data(), 24000, &b, 1);
        check(peak(out, 0, 2000) > 0.1f, "el click suena al inicio");
        check(peak(out, 12000, 24000) == 0.0f, "tras ~250 ms la cola ha terminado: exactamente 0");
    }

    // ── 6. Desactivado ──
    {
        Metronome m; m.prepare(kSR);
        check(m.isIdle(), "recién creado y desactivado: isIdle");
        std::vector<float> out(2 * 1000, 0.25f);
        const TempoClock::Beat b{10, 0};
        m.render(out.data(), 1000, &b, 1);
        bool untouched = true; for (float v : out) untouched = untouched && v == 0.25f;
        check(untouched, "desactivado: ignora los pulsos y no toca el buffer");
    }

    // ── 7. Suma, no sobrescribe (bus aditivo) ──
    {
        Metronome m = ready();
        std::vector<float> out(2 * 1000, 0.25f);
        const TempoClock::Beat b{0, 0};
        m.render(out.data(), 1000, &b, 1);
        check(out[0] == 0.25f && std::fabs(out[2 * 20] - 0.25f) > 0.01f, "el click se SUMA al contenido existente");
    }

    // ── 8. Desactivar en pleno click: sin saltos bruscos, termina en idle ──
    {
        Metronome m = ready();
        std::vector<float> out(2 * 9600, 0.0f);
        const TempoClock::Beat b{0, 0};
        m.render(out.data(), 200, &b, 1);                         // click en marcha
        m.setEnabled(false);
        std::vector<float> fade(2 * 9600, 0.0f);
        m.render(fade.data(), 9600, nullptr, 0);
        float maxStep = 0.0f;
        for (int i = 1; i < 9600; ++i) maxStep = std::fmax(maxStep, std::fabs(fade[2 * i] - fade[2 * (i - 1)]));
        // un seno de 1.5 kHz a amplitud ≤0.8 tiene un paso máximo natural de 2π·1500/48000·0.8 ≈ 0.157
        check(maxStep < 0.2f, "desactivar en pleno click: sin discontinuidades (paso máximo < 0.2)");
        check(m.isIdle(), "tras desactivar y vaciar el bloque: isIdle (coste cero)");
    }

    // ── 8b. Un click que nace desde el silencio suena a nivel COMPLETO (sin rampa de entrada) ──
    {
        // Activar y disparar el primer click en el MISMO bloque (play + metrónomo a la vez)
        Metronome cold; cold.prepare(kSR); cold.setVolume(1.0f); cold.setEnabled(true);
        Metronome warm = ready(1.0f);
        std::vector<float> a(2 * 2000, 0.0f), b(2 * 2000, 0.0f);
        const TempoClock::Beat first{0, 0};
        cold.render(a.data(), 2000, &first, 1);
        warm.render(b.data(), 2000, &first, 1);
        check(a == b, "8b. activado en el mismo bloque que el primer pulso: idéntico a uno ya asentado (el primer tiempo no se atenúa)");
        // y el click sigue empezando sin pop: la primera muestra es 0
        check(a[0] == 0.0f, "8b'. el click que nace del silencio sigue arrancando en fase 0 (sin pop)");
    }

    // ── 9. Entradas inválidas ──
    {
        Metronome m; m.prepare(kSR); m.setVolume(0.6f);
        m.setVolume(std::numeric_limits<float>::quiet_NaN());
        m.setVolume(std::numeric_limits<float>::infinity());
        check(m.volume() == 0.6f, "setVolume(NaN/Inf) se ignora");
        m.setVolume(7.0f);  check(m.volume() == 1.0f, "setVolume(7) → 1");
        m.setVolume(-3.0f); check(m.volume() == 0.0f, "setVolume(-3) → 0");
        m.prepare(0);       // ignorado, no rompe
        std::vector<float> out(2 * 64, 0.0f);
        m.render(out.data(), 64, nullptr, 0);
        check(true, "prepare(0) y render sin pulsos no fallan");
    }

    // ── 10. Integración reloj + metrónomo: 120 BPM, 5 s → 10 clicks, en su sitio ──
    {
        TempoClock c; c.prepare(kSR); c.setTempo(120.0); c.start();
        Metronome m = ready();
        const int total = kSR * 5, blk = 480;
        std::vector<float> out(2 * total, 0.0f);
        std::vector<int> onsets;
        TempoClock::Beat ev[TempoClock::kMaxBeatsPerBlock];
        for (int pos = 0; pos < total; pos += blk) {
            const int n = c.advance(blk, ev, TempoClock::kMaxBeatsPerBlock);
            m.render(out.data() + 2 * pos, blk, ev, n);
            for (int i = 0; i < n; ++i) onsets.push_back(pos + ev[i].frameOffset);
        }
        bool exact = onsets.size() == 10;
        for (std::size_t k = 0; k < onsets.size(); ++k) exact = exact && onsets[k] == static_cast<int>(k) * 24000;
        check(exact, "120 BPM durante 5 s: 10 clicks, cada uno en el frame k*24000");
        bool audible = true;
        for (int k = 0; k < 10; ++k) audible = audible && peak(out, k * 24000, k * 24000 + 480) > 0.1f;
        check(audible, "cada uno de los 10 clicks es audible en su ventana");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
