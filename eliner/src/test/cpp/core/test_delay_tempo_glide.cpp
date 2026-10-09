// ADR 0028 — Delay: cambio de tiempo sin clicks + equivalencia con el delay previo.
//
// Prueba el código de PRODUCCIÓN (Delay.cpp). Incluye una réplica LITERAL del
// delay anterior a ADR 0028 (LegacyDelay) para dos demostraciones:
//   A. Con tiempo estático, la salida del nuevo Delay es IDÉNTICA bit a bit.
//   B. El delay anterior SÍ producía un salto (click) al cambiar el tiempo; el
//      nuevo no. El test discrimina: falla si se elimina el deslizamiento.
#include "Delay.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <vector>

using eliner::Delay;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
constexpr int kSR = 48000;

// Réplica literal del Delay previo a ADR 0028 (Delay.cpp original).
class LegacyDelay {
public:
    explicit LegacyDelay(int sr) : mSR(sr) { int m = sr * 2; mBufL.assign(m, 0.f); mBufR.assign(m, 0.f); setTime(0.375f); }
    void setTime(float s) { mDelayLen = (int)(s * mSR); mDelayLen = std::max(1, std::min(mDelayLen, (int)mBufL.size() - 1)); }
    void setFeedback(float fb) { mFeedback = std::max(0.f, std::min(fb, 0.95f)); }
    void setMix(float m) { mMix = std::max(0.f, std::min(m, 1.f)); }
    void process(float* io, int n) {
        if (mMix < 0.001f) return;
        float wet = mMix * 0.7f, dry = 1.0f;
        for (int i = 0; i < n; i++) {
            float inL = io[i*2], inR = io[i*2+1];
            int rL = (mWL - mDelayLen + (int)mBufL.size()) % (int)mBufL.size();
            int rR = (mWR - mDelayLen + (int)mBufR.size()) % (int)mBufR.size();
            float dL = mBufR[rR], dR = mBufL[rL];
            mBufL[mWL] = inL + dL * mFeedback; mBufR[mWR] = inR + dR * mFeedback;
            mWL = (mWL + 1) % (int)mBufL.size(); mWR = (mWR + 1) % (int)mBufR.size();
            io[i*2] = inL * dry + dL * wet; io[i*2+1] = inR * dry + dR * wet;
        }
    }
private:
    int mSR; float mMix = 0.f, mFeedback = 0.4f; int mDelayLen = 0;
    std::vector<float> mBufL, mBufR; int mWL = 0, mWR = 0;
};

std::vector<float> noise(int frames, unsigned seed) {
    std::srand(seed);
    std::vector<float> v(2 * frames);
    for (auto& x : v) x = (static_cast<float>(std::rand()) / RAND_MAX - 0.5f) * 0.8f;
    return v;
}
std::vector<float> stereoSine(int frames, float hz, float amp) {
    std::vector<float> v(2 * frames);
    for (int i = 0; i < frames; ++i) v[2*i] = v[2*i+1] = amp * std::sin(6.283185307f * hz * static_cast<float>(i) / kSR);
    return v;
}
float maxStep(const std::vector<float>& v, int fromFrame) {
    float m = 0.f;
    for (std::size_t i = 2 * (fromFrame + 1); i < v.size(); i += 2) m = std::fmax(m, std::fabs(v[i] - v[i - 2]));
    return m;
}
}

int main() {
    // ── A. Equivalencia bit a bit en régimen estático ──
    {
        struct Cfg { float time, fb, mix; };
        bool allIdentical = true;
        // Igualdad estricta: ambos con el tiempo de construcción (0.375 s por defecto).
        Delay nu(kSR); LegacyDelay old(kSR);
        nu.setFeedback(0.7f); old.setFeedback(0.7f); nu.setMix(0.9f); old.setMix(0.9f);
        const int frames = kSR * 8;
        auto in = noise(frames, 99u);
        auto a = in, b = in;
        for (int pos = 0; pos < frames; pos += 480) { nu.process(a.data() + 2 * pos, 480); old.process(b.data() + 2 * pos, 480); }
        for (std::size_t i = 0; i < a.size(); ++i) if (a[i] != b[i]) { allIdentical = false; break; }
        check(allIdentical, "A1. tiempo por defecto, 8 s de ruido: salida IDÉNTICA bit a bit al delay anterior");
    }
    {
        // Tiempo cambiado ANTES de procesar y dejado asentar (mix=0 hace snap): luego idéntico.
        bool identical = true;
        for (float t : {0.1234f, 0.7f, 1.5f, 0.0101f}) {
            Delay nu(kSR); LegacyDelay old(kSR);
            nu.setMix(0.0f); nu.setTime(t); { std::vector<float> d(2 * 16, 0.f); nu.process(d.data(), 16); } // bypass → snap
            old.setTime(t);
            nu.setMix(0.8f); old.setMix(0.8f); nu.setFeedback(0.6f); old.setFeedback(0.6f);
            const int frames = kSR * 4; auto in = noise(frames, 7u); auto a = in, b = in;
            nu.process(a.data(), frames); old.process(b.data(), frames);
            for (std::size_t i = 0; i < a.size(); ++i) if (a[i] != b[i]) { identical = false; break; }
        }
        check(identical, "A2. tiempos 0.0101/0.1234/0.7/1.5 s asentados: salida IDÉNTICA bit a bit al delay anterior");
    }

    // ── B. Sin clicks al cambiar el tiempo (y el delay anterior SÍ los tenía) ──
    {
        const int frames = kSR * 3, change = kSR;       // cambio a 1 s
        // 50 Hz: señal grave ⇒ sus pasos naturales son diminutos (≈0.003) y cualquier salto destaca.
        // (A 440 Hz los tiempos 0.375 s y 0.75 s son múltiplos exactos del periodo y el salto del
        // delay anterior sería invisible; a 441.7 Hz las fases de los dos puntos de lectura casualmente
        // se parecen. Medido: a 50 Hz natural=0.0031, nuevo=0.0060, anterior=0.1765.)
        auto sine = stereoSine(frames, 50.f, 0.5f);
        Delay nu(kSR); nu.setMix(1.0f); nu.setFeedback(0.5f);
        LegacyDelay old(kSR); old.setMix(1.0f); old.setFeedback(0.5f);
        auto a = sine, b = sine;
        nu.process(a.data(), change); old.process(b.data(), change);
        nu.setTime(0.75f); old.setTime(0.75f);          // 120 → 60 BPM (dotted 8th)
        nu.process(a.data() + 2 * change, frames - change);
        old.process(b.data() + 2 * change, frames - change);
        const float stepNew = maxStep(a, change), stepOld = maxStep(b, change);
        std::fprintf(stdout, "       (paso máximo tras el cambio — nuevo: %.4f, anterior: %.4f)\n", stepNew, stepOld);
        check(stepNew < 0.02f, "B1. nuevo: cambiar el tiempo en pleno audio NO produce saltos (paso máximo < 0.02)");
        check(stepOld > 0.10f, "B2. el delay ANTERIOR producía un salto audible en ese cambio (el test discrimina)");
        check(stepOld > 10.0f * stepNew, "B2b. el salto del delay anterior era >10 veces mayor que el del nuevo");
    }
    {
        // Cambios repetidos (mantener pulsado "+" del BPM): 120→140 en pasos de 1 BPM cada 80 ms
        Delay nu(kSR); nu.setMix(1.0f); nu.setFeedback(0.6f);
        LegacyDelay old(kSR); old.setMix(1.0f); old.setFeedback(0.6f);
        const int stepFrames = kSR * 80 / 1000, steps = 40;
        auto a = stereoSine(stepFrames * steps, 50.f, 0.5f), b = a;
        for (int s = 0; s < steps; ++s) {
            const float t = static_cast<float>(60.0 / (120.0 + s) * 0.75);
            nu.setTime(t);  nu.process(a.data() + 2 * s * stepFrames, stepFrames);
            old.setTime(t); old.process(b.data() + 2 * s * stepFrames, stepFrames);
        }
        const float sn = maxStep(a, kSR / 4), so = maxStep(b, kSR / 4);
        std::fprintf(stdout, "       (40 pasos de 1 BPM — paso máximo nuevo: %.4f, anterior: %.4f)\n", sn, so);
        check(sn < 0.02f, "B3. 40 cambios de +1 BPM cada 80 ms (mantener pulsado): sin clicks");
        check(so > 3.0f * sn, "B3b. el delay anterior producía clicks claramente mayores en ese mismo gesto");
    }

    // ── C. Velocidad de deslizamiento acotada ──
    {
        Delay nu(kSR); nu.setMix(1.0f);
        nu.setTime(1.5f);                                   // salto grande: 0.375 → 1.5 s
        double prev = nu.currentLengthSamples(), worst = 0.0; int n = 0;
        std::vector<float> one(2, 0.1f);
        while (nu.currentLengthSamples() != nu.targetLengthSamples() && n < kSR * 20) {
            nu.process(one.data(), 1);
            worst = std::fmax(worst, std::fabs(nu.currentLengthSamples() - prev));
            prev = nu.currentLengthSamples(); ++n;
        }
        check(worst <= static_cast<double>(Delay::kMaxSlewSamplesPerSample) + 1e-9, "C1. el deslizamiento nunca supera 0.2 muestras de retardo por muestra");
        check(nu.currentLengthSamples() == nu.targetLengthSamples(), "C2. el deslizamiento termina EXACTAMENTE en el objetivo");
        const double seconds = static_cast<double>(n) / kSR;
        std::fprintf(stdout, "       (salto 0.375→1.5 s: %.2f s de glissando)\n", seconds);
        // Modelo analítico: tramo con velocidad limitada hasta que diff·coef < techo (diff = techo/coef),
        // luego cola exponencial hasta el umbral de encaje (0.01 muestras).
        const double coef = 1.0 - std::exp(-1.0 / (0.040 * kSR)), slew = Delay::kMaxSlewSamplesPerSample;
        const double knee = slew / coef, D = 1.5 * kSR - 0.375 * kSR;
        const double expected = (D - knee) / slew / kSR + 0.040 * std::log(knee / 0.01);
        std::fprintf(stdout, "       (modelo analítico: %.2f s)\n", expected);
        check(std::fabs(seconds - expected) < 0.03 * expected, "C3. la duración del glissando coincide con el modelo analítico (±3 %)");
    }
    {
        // un cambio pequeño (1 BPM) se completa rápido: dominado por el polo de 40 ms
        Delay nu(kSR); nu.setMix(1.0f); nu.setTime(static_cast<float>(60.0 / 121.0 * 0.75));
        int n = 0; std::vector<float> one(2, 0.1f);
        while (nu.currentLengthSamples() != nu.targetLengthSamples() && n < kSR) { nu.process(one.data(), 1); ++n; }
        check(n < kSR * 4 / 10, "C4. un paso de 1 BPM se asienta en menos de 400 ms");
    }

    // ── D. Bypass: encaja de inmediato ──
    {
        Delay nu(kSR); nu.setMix(0.0f); nu.setTime(1.0f);
        std::vector<float> d(2 * 8, 0.f); nu.process(d.data(), 8);
        check(nu.currentLengthSamples() == nu.targetLengthSamples() && nu.targetLengthSamples() == static_cast<double>(kSR),
              "D1. con mix=0 el tiempo nuevo se aplica al instante (nada audible que deslizar)");
    }

    // ── E. Temporización exacta de un impulso (estático) ──
    {
        Delay nu(kSR); nu.setMix(1.0f); nu.setFeedback(0.0f);
        const int len = static_cast<int>(nu.targetLengthSamples());   // 18000
        std::vector<float> io(2 * (len + 100), 0.0f);
        io[1] = 1.0f;                                                 // impulso en R, frame 0
        nu.process(io.data(), len + 100);
        // ping-pong: el impulso de R aparece en L a los `len` frames, con ganancia wet = 0.7
        check(std::fabs(io[2 * len] - 0.7f) < 1e-6f, "E1. impulso en R → aparece en L exactamente a los 18000 frames (0.375 s) con wet 0.7");
        check(io[2 * (len - 1)] == 0.0f && io[2 * (len + 1)] == 0.0f, "E2. y en ningún frame vecino");
    }

    // ── F. Entradas inválidas y límites ──
    {
        Delay nu(kSR); const double before = nu.targetLengthSamples();
        nu.setTime(std::numeric_limits<float>::quiet_NaN());
        nu.setTime(std::numeric_limits<float>::infinity());
        check(nu.targetLengthSamples() == before, "F1. setTime(NaN/Inf) se ignora");
        nu.setTime(0.0f);   check(nu.targetLengthSamples() == 1.0, "F2. setTime(0) → 1 muestra (mínimo)");
        nu.setTime(99.0f);  check(nu.targetLengthSamples() == static_cast<double>(2 * kSR - 2), "F3. setTime(99) → 2 s - 2 muestras (máximo)");
        // Lectura en el máximo: no debe salirse del buffer (ASan/UBSan lo vigilan). El glissando
        // hasta el extremo (0.375 s → 2 s) tarda ~8 s a 0.2 muestras/muestra, así que se procesan
        // 14 s para ALCANZARLO de verdad y recorrer el buffer circular completo en esa posición.
        nu.setMix(1.0f); nu.setFeedback(0.9f);
        const int frames = kSR * 14;
        auto in = noise(frames, 5u);
        for (int pos = 0; pos < frames; pos += 480) nu.process(in.data() + 2 * pos, std::min(480, frames - pos));
        check(nu.currentLengthSamples() == static_cast<double>(2 * kSR - 2),
              "F4. el glissando ALCANZA el extremo (2 s − 2 muestras) y se sigue procesando sobre todo el buffer sin accesos fuera de rango");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
