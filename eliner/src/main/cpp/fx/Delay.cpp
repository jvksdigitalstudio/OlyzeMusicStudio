#include "Delay.h"
#include "TempoSync.h"
#include <algorithm>
#include <cmath>

namespace eliner {

namespace {
constexpr double kGlideTauSeconds = 0.040;
constexpr double kSnapSamples     = 0.01;  // por debajo de esto se considera alcanzado
}

void Delay::setParameter(uint8_t paramId, float value) {
    switch (static_cast<Param>(paramId)) {
        case Param::Mix:      setMix(value);      break;
        case Param::Time:     setTime(value);     break;
        case Param::Feedback: setFeedback(value); break;
    }
}

Delay::Delay(int sampleRate) : mSR(sampleRate) {
    int maxLen = sampleRate * 2; // 2 sec max
    mBufL.assign(maxLen, 0.0f);
    mBufR.assign(maxLen, 0.0f);
    mSmoothCoef = 1.0 - std::exp(-1.0 / (kGlideTauSeconds * sampleRate));
    setTime(0.375f); // 3/8 sec default (synced to 120 BPM at 1/4 note)
    mCurLen = mTargetLen; // el tiempo inicial no se desliza desde 1 muestra
}

void Delay::setTime(float seconds) {
    if (!tempo::isFinite(seconds)) return;
    // Truncado a muestras enteras, como antes de ADR 0028: en régimen
    // estático la salida es idéntica a la del delay anterior.
    int len = static_cast<int>(seconds * static_cast<float>(mSR));
    len = std::max(1, std::min(len, static_cast<int>(mBufL.size()) - 2));
    mTargetLen = static_cast<double>(len);
}

void Delay::setFeedback(float fb) {
    mFeedback = std::max(0.0f, std::min(fb, 0.95f));
}

void Delay::setMix(float mix) {
    mMix = std::max(0.0f, std::min(mix, 1.0f));
}

void Delay::process(float* inout, int numFrames) {
    if (mMix < 0.001f) {
        // Bypass: nada audible que deslizar. Se encaja el tiempo objetivo
        // para que al volver a activar el delay suene ya al tempo actual.
        mCurLen = mTargetLen;
        return;
    }
    float wet = mMix * 0.7f;
    float dry = 1.0f;
    const int size = static_cast<int>(mBufL.size());

    for (int n = 0; n < numFrames; n++) {
        float inL = inout[n * 2];
        float inR = inout[n * 2 + 1];

        // Deslizamiento hacia el tiempo objetivo (un polo + techo de velocidad).
        const double diff = mTargetLen - mCurLen;
        if (diff != 0.0) {
            if (std::fabs(diff) < kSnapSamples) {
                mCurLen = mTargetLen;
            } else {
                const double step = std::clamp(diff * mSmoothCoef,
                                               -static_cast<double>(kMaxSlewSamplesPerSample),
                                                static_cast<double>(kMaxSlewSamplesPerSample));
                mCurLen += step;
            }
        }

        // Lectura (ping-pong: L lee el buffer R, R lee el buffer L).
        const int   whole = static_cast<int>(mCurLen);
        const float frac  = static_cast<float>(mCurLen - static_cast<double>(whole));
        int i0 = mWrite - whole;      if (i0 < 0) i0 += size;
        float delayL, delayR;
        if (frac == 0.0f) {           // régimen estático: lectura directa
            delayL = mBufR[i0];
            delayR = mBufL[i0];
        } else {                      // deslizando: interpolación lineal
            int i1 = i0 - 1;          if (i1 < 0) i1 += size;
            delayL = mBufR[i0] * (1.0f - frac) + mBufR[i1] * frac;
            delayR = mBufL[i0] * (1.0f - frac) + mBufL[i1] * frac;
        }

        mBufL[mWrite] = inL + delayL * mFeedback;
        mBufR[mWrite] = inR + delayR * mFeedback;

        if (++mWrite == size) mWrite = 0;

        inout[n * 2]     = inL * dry + delayL * wet;
        inout[n * 2 + 1] = inR * dry + delayR * wet;
    }
}

} // namespace eliner
