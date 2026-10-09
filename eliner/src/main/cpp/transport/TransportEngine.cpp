#include "TransportEngine.h"
#include "TempoSync.h"

namespace eliner {

void TransportEngine::prepare(int sampleRate) {
    if (sampleRate > 0) mSampleRate = static_cast<double>(sampleRate);
    mClock.prepare(sampleRate);
    mMetronome.prepare(sampleRate);
}

void TransportEngine::setRunning(bool running) {
    if (running) {
        mClock.start();
    } else {
        mClock.stop();
        mMetronome.resetSubdivision(); // ningún click intermedio suelto tras parar
        // ADR 0029: la UI debe ver que se paró aunque no llegue otro pulso.
        mPulse.store(pulse::pack(mPulseSeq, 0, false), std::memory_order_release);
    }
}

double TransportEngine::delaySyncSeconds(double minSeconds, double maxSeconds) const {
    return tempo::syncedDelaySeconds(mClock.tempo(), static_cast<double>(mDelaySyncBeats),
                                     minSeconds, maxSeconds);
}

int TransportEngine::advance(int numFrames) {
    mNumBeats = mClock.advance(numFrames, mBeats, TempoClock::kMaxBeatsPerBlock);
    if (mNumBeats > 0) {
        // ADR 0029: publicar el último pulso del bloque para el indicador de la UI.
        // Un solo store atómico; sin locks ni asignación (seguro en tiempo real).
        mPulseSeq += static_cast<std::uint64_t>(mNumBeats);
        mPulse.store(pulse::pack(mPulseSeq, mBeats[mNumBeats - 1].beatInBar, true),
                     std::memory_order_release);
    }
    return mNumBeats;
}

void TransportEngine::renderClick(float* inOut, int numFrames) {
    // Duración de un pulso (frames) al tempo del momento: la subdivisión del click la usa
    // y se resincroniza en cada pulso (ver Metronome.h).
    const double framesPerBeat = tempo::secondsPerBeat(mClock.tempo()) * mSampleRate;
    mMetronome.render(inOut, numFrames, mBeats, mNumBeats, framesPerBeat);
}

} // namespace eliner
