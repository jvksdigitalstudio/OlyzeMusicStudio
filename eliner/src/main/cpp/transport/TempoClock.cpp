#include "TempoClock.h"
#include "TempoSync.h"

namespace eliner {

void TempoClock::prepare(int sampleRate) {
    if (sampleRate <= 0) return;
    const double newRate = static_cast<double>(sampleRate);
    if (mRunning) mFramesToNextBeat *= newRate / mSampleRate; // misma fase musical
    mSampleRate = newRate;
}

void TempoClock::setTempo(double bpm) {
    if (!tempo::isFinite(bpm)) return;
    const double newBpm = tempo::clampBpm(bpm);
    // Frames por pulso = sampleRate*60/bpm  →  el restante escala con
    // oldBpm/newBpm, lo que mantiene continua la fase dentro del pulso.
    if (mRunning) mFramesToNextBeat *= mBpm / newBpm;
    mBpm = newBpm;
}

void TempoClock::setBeatsPerBar(int beats) {
    if (beats < kMinBeatsPerBar) beats = kMinBeatsPerBar;
    if (beats > kMaxBeatsPerBar) beats = kMaxBeatsPerBar;
    mBeatsPerBar = beats;
    if (mBeatInBar >= static_cast<std::uint32_t>(mBeatsPerBar)) mBeatInBar = 0;
}

void TempoClock::start() {
    if (mRunning) return;
    mRunning          = true;
    mFramesToNextBeat = 0.0;  // pulso 0 en el primer frame
    mBeatInBar        = 0;
}

void TempoClock::stop() {
    mRunning = false;
}

int TempoClock::advance(int numFrames, Beat* out, int maxOut) {
    if (!mRunning || numFrames <= 0) return 0;

    const double framesPerBeat = mSampleRate * 60.0 / mBpm;
    const double blockEnd      = static_cast<double>(numFrames);
    const auto   bar           = static_cast<std::uint32_t>(mBeatsPerBar);

    double t = mFramesToNextBeat;
    int    n = 0;
    while (t < blockEnd) {
        if (n < maxOut) {
            out[n].frameOffset = static_cast<int>(t); // t ∈ [0, numFrames)
            out[n].beatInBar   = mBeatInBar;
            ++n;
        }
        mBeatInBar = (mBeatInBar + 1) % bar;
        t += framesPerBeat;
    }
    mFramesToNextBeat = t - blockEnd; // ≥ 0: la fracción de muestra se arrastra
    return n;
}

} // namespace eliner
