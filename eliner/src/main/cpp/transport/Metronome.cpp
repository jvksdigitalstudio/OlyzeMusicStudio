#include "Metronome.h"
#include "TempoSync.h"
#include <cmath>

namespace eliner {

namespace {
constexpr double kTwoPi        = 6.283185307179586476925286766559;
constexpr double kAttackSec    = 0.0005;
constexpr double kGainTauSec   = 0.005;
constexpr float  kAccentAmp    = 1.0f;
constexpr float  kBeatAmp      = 0.75f;
constexpr float  kSubAmp       = 0.4f;    // clicks de subdivisión: claramente por debajo del pulso
constexpr float  kPeak         = 0.8f;    // margen bajo 0 dBFS
constexpr float  kEnvFloor     = 1.0e-4f; // ≈ -80 dB: el click se da por terminado
constexpr float  kGainFloor    = 1.0e-7f; // evita la cola denormal al apagar

// Un preset describe UN sonido de click. "Clásico" reproduce exactamente el
// sonido que tenía el motor antes de los presets (1500/1000 Hz, τ = 12 ms, seno puro).
struct Preset {
    double accentHz;
    double beatHz;
    double tauSec;       // constante de caída de la envolvente
    double partialRatio; // 0 = sin parcial; si no, relación inarmónica respecto al tono
    float  toneAmp;
    float  partialAmp;
    float  noiseAmp;
};

constexpr Preset kPresets[] = {
    /* Classic */ {1500.0, 1000.0, 0.012, 0.0,   1.0f,  0.0f,  0.0f},
    /* Wood    */ {1900.0, 1300.0, 0.007, 2.76,  1.0f,  0.45f, 0.0f},
    /* Beep    */ {1760.0,  880.0, 0.035, 0.0,   1.0f,  0.0f,  0.0f},
    /* Cowbell */ { 845.0,  587.0, 0.050, 1.504, 1.0f,  0.9f,  0.0f},
    /* Hat     */ {6000.0, 4500.0, 0.006, 0.0,   0.12f, 0.0f,  1.0f},
};
static_assert(sizeof(kPresets) / sizeof(kPresets[0]) == static_cast<int>(ClickSound::Count),
              "cada ClickSound necesita su preset (y viceversa)");
}

void Metronome::prepare(int sampleRate) {
    if (sampleRate <= 0) return;
    mSampleRate = static_cast<double>(sampleRate);
    mGainCoef   = static_cast<float>(1.0 - std::exp(-1.0 / (kGainTauSec * mSampleRate)));
    mAttackLen  = static_cast<int>(kAttackSec * mSampleRate);
    if (mAttackLen < 1) mAttackLen = 1;
    mActive   = false;
    mGain     = 0.0f;
    mSubsLeft = 0;
}

void Metronome::setVolume(float volume) {
    if (!tempo::isFinite(volume)) return;
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    mVolume = volume;
}

void Metronome::setSound(int sound) {
    if (sound < 0 || sound >= static_cast<int>(ClickSound::Count)) return; // se ignora, no se recorta
    mSound = sound;
}

void Metronome::setSubdivision(int perBeat) {
    if (perBeat < kMinClickSubdivision || perBeat > kMaxClickSubdivision) return;
    mSubdivision = perBeat;
}

void Metronome::trigger(bool accent, float amp) {
    const Preset& p = kPresets[mSound];
    mActive    = true;
    mPhase     = 0.0;
    mPhase2    = 0.0;
    mPhaseInc  = (accent ? p.accentHz : p.beatHz) / mSampleRate;
    mPhase2Inc = mPhaseInc * p.partialRatio;
    mAmp       = amp;
    mToneAmp   = p.toneAmp;
    mPartialAmp = p.partialAmp;
    mNoiseAmp  = p.noiseAmp;
    // El ruido se realza restando la muestra anterior (hasta 2× de pico): se cuenta
    // para que sumar parciales o ruido nunca suba el nivel máximo del click.
    mNorm      = 1.0f / (p.toneAmp + p.partialAmp + 2.0f * p.noiseAmp);
    mEnvDecay  = static_cast<float>(std::exp(-1.0 / (p.tauSec * mSampleRate)));
    mEnv       = 1.0f;
    mAttackPos = 0;
}

void Metronome::render(float* inOut, int numFrames, const TempoClock::Beat* beats, int numBeats,
                       double framesPerBeat) {
    // Sin nada audible ni pendiente: coste cero (el caso habitual).
    if (isIdle()) return;

    const float target = mEnabled ? mVolume * mVolume : 0.0f;
    int next = 0;

    for (int i = 0; i < numFrames; ++i) {
        // 1) Subdivisión: primero la cuenta atrás de ESTE frame, después los pulsos. Así el
        //    click intermedio cae exactamente framesPerBeat/n frames después del pulso.
        if (mSubsLeft > 0) {
            mSubCountdown -= 1.0;
            if (mSubCountdown <= 0.0) {
                if (mEnabled) {
                    if (!mActive) mGain = target; // mismo criterio que el click de pulso
                    trigger(false, kSubAmp);
                }
                --mSubsLeft;
                mSubCountdown += mSubInterval; // la fracción de muestra se arrastra
            }
        }

        // 2) Pulsos del reloj en este frame.
        while (next < numBeats && beats[next].frameOffset <= i) {
            if (mEnabled) {
                // Un click que nace desde el SILENCIO nace ya a la ganancia
                // objetivo: no hay nada sonando que suavizar (el suavizado
                // evita zipper noise sobre audio vivo), y de lo contrario
                // activar el metrónomo a la vez que arranca el transporte
                // atenuaría justo el primer tiempo, el acentuado.
                if (!mActive) mGain = target;
                const bool accent = mAccentEnabled && beats[next].beatInBar == 0;
                trigger(accent, accent ? kAccentAmp : kBeatAmp);
            }
            // Cada pulso RESINCRONIZA la subdivisión con el tempo del momento.
            if (mSubdivision > 1 && framesPerBeat > 0.0) {
                mSubsLeft     = mSubdivision - 1;
                mSubInterval  = framesPerBeat / static_cast<double>(mSubdivision);
                mSubCountdown = mSubInterval;
            } else {
                mSubsLeft = 0;
            }
            ++next;
        }

        mGain += (target - mGain) * mGainCoef;
        if (target == 0.0f && mGain < kGainFloor) mGain = 0.0f;

        if (mActive) {
            const float attack = (mAttackPos < mAttackLen)
                ? static_cast<float>(mAttackPos++) / static_cast<float>(mAttackLen)
                : 1.0f;

            float voice = static_cast<float>(std::sin(kTwoPi * mPhase)) * mToneAmp;
            if (mPartialAmp > 0.0f) {
                voice += static_cast<float>(std::sin(kTwoPi * mPhase2)) * mPartialAmp;
                mPhase2 += mPhase2Inc;
                if (mPhase2 >= 1.0) mPhase2 -= 1.0;
            }
            if (mNoiseAmp > 0.0f) {
                mRng ^= mRng << 13; mRng ^= mRng >> 17; mRng ^= mRng << 5; // xorshift32
                const float r = static_cast<float>(static_cast<std::int32_t>(mRng)) * (1.0f / 2147483648.0f);
                voice += (r - mNoiseLast) * mNoiseAmp;
                mNoiseLast = r;
            }

            const float s = voice * mNorm * mEnv * attack * mAmp * kPeak * mGain;
            inOut[2 * i]     += s;
            inOut[2 * i + 1] += s;

            mPhase += mPhaseInc;
            if (mPhase >= 1.0) mPhase -= 1.0;
            mEnv   *= mEnvDecay;
            if (mEnv < kEnvFloor && mAttackPos >= mAttackLen) mActive = false;
        }
    }
}

} // namespace eliner
