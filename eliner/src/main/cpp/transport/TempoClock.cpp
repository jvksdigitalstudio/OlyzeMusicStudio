#include "TempoClock.h"
#include "TempoSync.h"

#include <cmath>

namespace eliner {

double TempoClock::pulseRemainder() const {
    const double fpp = framesPerPulse();
    if (!(fpp > 0.0)) return 0.0;
    const double r = mFramesToNextBeat / fpp;
    return r < 0.0 ? 0.0 : (r > 1.0 ? 1.0 : r);
}

// Cambiar la frecuencia de muestreo o el tempo reescala el tiempo restante del pulso AUNQUE el reloj esté
// pausado: una pausa a mitad de pulso conserva la fase, y debe seguir siendo la misma al reanudar con otro
// tempo (ADR 0036). Con el reloj parado el restante es 0 y el reescalado no tiene efecto.
void TempoClock::prepare(int sampleRate) {
    if (sampleRate <= 0) return;
    const double newRate = static_cast<double>(sampleRate);
    mFramesToNextBeat *= newRate / mSampleRate; // misma fase musical
    mSampleRate = newRate;
}

void TempoClock::setTempo(double bpm) {
    if (!tempo::isFinite(bpm)) return;
    const double newBpm = tempo::clampBpm(bpm);
    // Frames por pulso = sampleRate*60/bpm  →  el restante escala con
    // oldBpm/newBpm, lo que mantiene continua la fase dentro del pulso.
    mFramesToNextBeat *= mBpm / newBpm;
    mBpm = newBpm;
}

void TempoClock::setBeatsPerBar(int beats) {
    if (beats < kMinBeatsPerBar) beats = kMinBeatsPerBar;
    if (beats > kMaxBeatsPerBar) beats = kMaxBeatsPerBar;
    mBeatsPerBar = beats;
    if (mBeatInBar >= static_cast<std::uint32_t>(mBeatsPerBar)) mBeatInBar = 0;
}

void TempoClock::setQuartersPerPulse(double quarters) {
    if (!tempo::isFinite(quarters)) return;
    if (quarters < kMinQuartersPerPulse || quarters > kMaxQuartersPerPulse) return; // se ignora, no se recorta
    // La posición actual (ticks) se conserva: el pulso en curso cambia de duración en ticks y la fracción que
    // falta por sonar es la misma, así que el inicio del próximo pulso se recalcula con la duración nueva.
    const double remainder = pulseRemainder();
    const double oldTicks  = static_cast<double>(pulseTicks());
    const double newTicks  = static_cast<double>(ticksPerPulse(quarters));
    const double nowTicks  = static_cast<double>(mNextPulseTicks) - remainder * oldTicks;
    mNextPulseTicks = static_cast<std::int64_t>(std::llround(nowTicks + remainder * newTicks));
    // Frames por pulso ∝ quarters → el restante escala con new/old (fase continua dentro del pulso).
    mFramesToNextBeat *= quarters / mQuartersPerPulse;
    mQuartersPerPulse = quarters;
}

void TempoClock::start() {
    mRunning = true; // continúa desde la posición y la fase retenidas (primer tiempo si se acaba de parar/crear)
}

void TempoClock::restartBar() {
    if (!mRunning) return;
    // El pulso que sigue es el tiempo 1 del compás EN CURSO: la posición vuelve al inicio de ese compás.
    mNextPulseTicks  -= static_cast<std::int64_t>(mBeatInBar) * pulseTicks();
    mFramesToNextBeat = 0.0;  // pulso en el primer frame del siguiente bloque
    mBeatInBar        = 0;    // y es el primer tiempo del compás
}

void TempoClock::pause() {
    mRunning = false;
}

void TempoClock::stop() {
    mRunning = false;
    locate(0);
}

void TempoClock::locate(std::int64_t ticks) {
    ticks = clampPositionTicks(ticks);
    const std::int64_t pt    = pulseTicks();
    const std::int64_t bar   = static_cast<std::int64_t>(mBeatsPerBar);
    const std::int64_t index = floorDiv(ticks, pt);   // pulso que CONTIENE la posición
    const std::int64_t into  = floorMod(ticks, pt);   // ticks transcurridos dentro de ese pulso
    if (into == 0) {
        // En un límite de pulso: ese pulso es el siguiente y cae en el primer frame.
        mNextPulseTicks   = ticks;
        mBeatInBar        = static_cast<std::uint32_t>(floorMod(index, bar));
        mFramesToNextBeat = 0.0;
    } else {
        // A mitad de pulso: el siguiente es el pulso posterior y llega cuando acabe el actual (fase continua).
        mNextPulseTicks   = (index + 1) * pt;
        mBeatInBar        = static_cast<std::uint32_t>(floorMod(index + 1, bar));
        mFramesToNextBeat = (1.0 - static_cast<double>(into) / static_cast<double>(pt)) * framesPerPulse();
    }
}

std::int64_t TempoClock::positionTicks() const {
    const double pos = static_cast<double>(mNextPulseTicks) - pulseRemainder() * static_cast<double>(pulseTicks());
    return static_cast<std::int64_t>(std::floor(pos));
}

int TempoClock::advance(int numFrames, Beat* out, int maxOut) {
    if (!mRunning || numFrames <= 0) return 0;

    const double framesPerBeat = mSampleRate * 60.0 / mBpm * mQuartersPerPulse;
    const double blockEnd      = static_cast<double>(numFrames);
    const auto   bar           = static_cast<std::uint32_t>(mBeatsPerBar);

    const std::int64_t pt = pulseTicks();
    double t = mFramesToNextBeat;
    int    n = 0;
    while (t < blockEnd) {
        if (n < maxOut) {
            out[n].frameOffset   = static_cast<int>(t); // t ∈ [0, numFrames)
            out[n].beatInBar     = mBeatInBar;
            out[n].positionTicks = mNextPulseTicks;
            ++n;
        }
        mBeatInBar = (mBeatInBar + 1) % bar;
        mNextPulseTicks += pt; // también para los pulsos que no caben en [out]: la posición sigue siendo exacta
        t += framesPerBeat;
    }
    mFramesToNextBeat = t - blockEnd; // ≥ 0: la fracción de muestra se arrastra
    return n;
}

} // namespace eliner
