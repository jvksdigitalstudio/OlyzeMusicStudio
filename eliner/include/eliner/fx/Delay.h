#pragma once
#include <vector>
#include <cmath>
#include "DspModule.h"

namespace eliner {

// ── Stereo Ping-Pong Delay ────────────────────────────────────────────────────
// Fase 7: now also a DspModule, so it can be loaded into a DspChain slot
// dynamically. The original public API (setTime/setFeedback/setMix/process)
// is unchanged — setParameter()/type() are additive, not a replacement.
//
// ── Cambio de tiempo sin clicks (ADR 0028) ────────────────────────────────
// setTime() NO mueve el puntero de lectura de golpe (eso produce un salto
// en la señal = click, y con un delay sincronizado al tempo ocurre cada vez
// que cambia el BPM). El tiempo objetivo se alcanza DESLIZÁNDOSE: la
// longitud efectiva sigue al objetivo con un filtro de un polo (τ = 40 ms)
// limitado a kMaxSlewSamplesPerSample, y la lectura se interpola
// linealmente entre muestras mientras se desliza. Consecuencia sonora: un
// cambio de tiempo produce un breve glissando tipo cinta, nunca un corte.
//
// En régimen estático (longitud ya alcanzada, siempre entera) la lectura es
// directa, sin interpolación: la salida es idéntica bit a bit a la del
// delay previo a ADR 0028 y no hay pérdida de agudos por interpolar.
class Delay : public DspModule {
public:
    // Rango útil de tiempo, en segundos (el buffer guarda 2 s; la longitud
    // máxima real es 2 s - 2 muestras, de ahí el techo de 1.99).
    static constexpr float kMinTimeSeconds = 0.01f;
    static constexpr float kMaxTimeSeconds = 1.99f;

    // Techo de la velocidad de deslizamiento: 0.2 muestras de retardo por
    // muestra ⇒ como máximo ±20 % de cambio de afinación (≈ 3 semitonos)
    // durante un salto grande. Sin este límite un salto de tempo grande
    // movería el puntero de lectura a decenas de muestras por muestra.
    static constexpr float kMaxSlewSamplesPerSample = 0.2f;

    explicit Delay(int sampleRate);

    // Fija el tiempo OBJETIVO (se alcanza deslizando). Fuera de rango se
    // limita; valores no finitos se ignoran.
    void setTime    (float seconds);      // 0.01 – 1.99
    void setFeedback(float fb);           // 0.0 – 0.95
    void setMix     (float mix);          // 0.0 – 1.0

    void process(float* inout, int numFrames) override;

    // ── DspModule interface (Fase 7) ──
    // Maps generic paramId -> the setters above. Values map 1:1 to
    // DspParameterId::Delay{Mix,Time,Feedback} used by AudioEngine's
    // legacy (pre-Fase-7) setDelayMix/Time/Feedback API, so both call
    // paths stay in sync regardless of which one a caller uses.
    enum Param : uint8_t { Mix = 0, Time = 1, Feedback = 2 };
    void setParameter(uint8_t paramId, float value) override;
    DspModuleType type() const override { return DspModuleType::Delay; }

    // Longitud objetivo y efectiva en muestras (observabilidad / tests).
    double targetLengthSamples()  const { return mTargetLen; }
    double currentLengthSamples() const { return mCurLen; }

private:
    int   mSR;
    float mMix      = 0.0f;
    float mFeedback = 0.4f;

    // `double` a propósito: con `float` (24 bits de mantisa) una longitud de
    // ~70 000 muestras tiene una resolución de ≈0.0078; el paso de un polo
    // `diff * coef` (coef ≈ 5e-4) para diff pequeño cae por debajo de media
    // unidad de resolución, se redondea a cero y la longitud efectiva se
    // quedaría ATASCADA a unas 7 muestras del objetivo para siempre.
    double mTargetLen = 1.0; // siempre entera (truncada, como antes de ADR 0028)
    double mCurLen    = 1.0; // longitud efectiva; converge a mTargetLen
    double mSmoothCoef = 0.0;

    std::vector<float> mBufL, mBufR;
    int mWrite = 0; // L y R avanzan siempre juntos: un único índice
};

} // namespace eliner
