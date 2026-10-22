#include "TransportEngine.h"
#include "BeatUnit.h"
#include "TempoSync.h"

namespace eliner {

void TransportEngine::prepare(int sampleRate) {
    if (sampleRate > 0) mSampleRate = static_cast<double>(sampleRate);
    mClock.prepare(sampleRate);
    mMetronome.prepare(sampleRate);
}

void TransportEngine::setBeatUnit(int denominator) {
    if (!isValidBeatUnit(denominator)) return;
    mClock.setQuartersPerPulse(quartersPerPulse(denominator));
}

void TransportEngine::setRecordArmed(bool armed) {
    mRecordArmed = armed;
    if (!armed && mPhase == TransportPhase::CountIn) {
        mCountIn.cancel();
        mPhase = TransportPhase::Running;
    }
}

void TransportEngine::setCountInBars(int bars) {
    if (!isValidCountInBars(bars)) return; // se ignora, no se recorta
    mCountInBars = bars;
}

void TransportEngine::setClickMode(int mode) {
    if (!isValidClickMode(mode)) return;   // se ignora, no se recorta
    mClickMode = static_cast<ClickMode>(mode);
}

std::int64_t TransportEngine::songPosition() const {
    if (mPhase == TransportPhase::CountIn) return mCountInOrigin; // retenida: el conteo no mueve la canción
    return mClock.positionTicks() + mPositionOffset;
}

void TransportEngine::publishPosition() {
    mPosition.store(songPosition(), std::memory_order_release);
}

void TransportEngine::locate(std::int64_t ticks) {
    if (ticks < 0) ticks = 0;
    mCountIn.cancel();
    if (mPhase == TransportPhase::CountIn) mPhase = TransportPhase::Running; // el reloj sigue en marcha
    mClock.locate(ticks);
    mPositionOffset = 0;
    mBeatShift      = 0;
    mMetronome.resetSubdivision(); // ningún click intermedio de la posición anterior
    publishPosition();
}

void TransportEngine::setRunning(bool running) {
    if (running) {
        if (mClock.isRunning()) return; // «play» repetido: ni reinicia el compás ni inicia un conteo
        // El conteo solo precede a una GRABACIÓN que arranca desde parado (ADR 0035). Ocupa los compases que
        // PRECEDEN a la posición de grabación: el reloj se coloca N compases antes (posiciones negativas si se
        // graba desde el origen) y la canción queda retenida en esa posición hasta que acabe el conteo.
        if (mRecordArmed && mCountInBars > kCountInBarsOff) {
            const std::int64_t pt       = ticksPerPulse(mClock.quartersPerPulse());
            const std::int64_t barTicks = static_cast<std::int64_t>(mClock.beatsPerBar()) * pt;
            mCountInOrigin = floorDiv(songPosition(), pt) * pt;   // la grabación empieza en la rejilla de pulsos
            // El conteo corre en el reloj ALINEADO AL COMPÁS (empieza en el tiempo 1), no en la posición de la
            // canción: esta queda retenida en mCountInOrigin hasta el primer pulso de la grabación.
            mClock.locate(-static_cast<std::int64_t>(mCountInBars) * barTicks);
            mPositionOffset = 0;
            mBeatShift      = 0;
            mCountIn.arm(mCountInBars);
            mPhase = TransportPhase::CountIn;
        } else {
            mCountIn.cancel();
            mPhase = TransportPhase::Running;
        }
        mClock.start();
    } else {
        // PAUSA (ADR 0036): se conserva la posición. Si la parada llega durante el conteo, la canción no se
        // había movido: el reloj vuelve a la posición de grabación retenida.
        mClock.pause();
        if (mPhase == TransportPhase::CountIn) {
            mClock.locate(mCountInOrigin);
            mPositionOffset = 0;
            mBeatShift      = 0;
        }
        mCountIn.cancel();
        mPhase = TransportPhase::Stopped;
        mMetronome.resetSubdivision(); // ningún click intermedio suelto tras parar
        publishPosition();
        // ADR 0029: la UI debe ver que se paró aunque no llegue otro pulso.
        mPulse.store(pulse::pack(mPulseSeq, 0, false), std::memory_order_release);
    }
}

double TransportEngine::delaySyncSeconds(double minSeconds, double maxSeconds) const {
    return tempo::syncedDelaySeconds(mClock.tempo(), static_cast<double>(mDelaySyncBeats),
                                     minSeconds, maxSeconds);
}

int TransportEngine::advance(int numFrames) {
    // Lo que la ventana de grabación necesita saber del instante ANTERIOR al avance (ADR 0038).
    const TransportPhase phaseAtStart = mPhase;
    const std::int64_t   posAtStart   = songPosition();
    int                  countInEndFrame = -1; // frame donde acaba el conteo dentro de este bloque, o -1
    mNumBeats = mClock.advance(numFrames, mBeats, TempoClock::kMaxBeatsPerBlock);
    if (mNumBeats > 0) {
        // ADR 0035: cada pulso se clasifica EN ORDEN (conteo o grabación) y de esa fase sale su gate de
        // click. Se usa el compás vigente del reloj; CountIn cuenta compases completos, así que un
        // cambio de compás a mitad de un conteo no desalinea el primer pulso de la grabación.
        const int beatsPerBar = mClock.beatsPerBar();
        CountIn::Step last{false, 0};
        for (int i = 0; i < mNumBeats; ++i) {
            const TransportPhase before = mPhase;
            last = mCountIn.onBeat(mBeats[i].beatInBar, beatsPerBar);
            mPhase = last.countIn ? TransportPhase::CountIn : TransportPhase::Running;
            if (before == TransportPhase::CountIn && mPhase == TransportPhase::Running) {
                // Primer pulso de la grabación: la canción pasa a ser una proyección del reloj tal que ESTE pulso
                // vale justo la posición de grabación y su tiempo del compás (ver mPositionOffset/mBeatShift).
                const std::int64_t pt = ticksPerPulse(mClock.quartersPerPulse());
                mPositionOffset = mCountInOrigin - mBeats[i].positionTicks;
                mBeatShift      = static_cast<int>(floorMod(floorDiv(mCountInOrigin, pt), beatsPerBar));
                countInEndFrame = mBeats[i].frameOffset;
            }
            if (!last.countIn && mBeatShift != 0) {
                mBeats[i].beatInBar = (mBeats[i].beatInBar + static_cast<std::uint32_t>(mBeatShift))
                                      % static_cast<std::uint32_t>(beatsPerBar);
            }
            mGates[i] = gateFor(mClickMode, mPhase, mRecordArmed);
        }
        // ADR 0029: publicar el último pulso del bloque para el indicador de la UI.
        // Un solo store atómico; sin locks ni asignación (seguro en tiempo real).
        mPulseSeq += static_cast<std::uint64_t>(mNumBeats);
        mPulse.store(pulse::pack(mPulseSeq, mBeats[mNumBeats - 1].beatInBar, true,
                                 last.countIn, static_cast<std::uint32_t>(last.barIndex)),
                     std::memory_order_release);
    }
    if (mClock.isRunning()) publishPosition();

    // ── Ventana de grabación (ADR 0038). Hay grabación con la grabación armada, el transporte en marcha y el
    // conteo ya pasado. Cada inicio es una toma NUEVA: pausar y reanudar con la grabación aún armada cierra la
    // toma (stopped) y abre otra (started). Un locate() durante la grabación NO parte la toma.
    RecordingBlock rb;
    const bool armedRunning = mRecordArmed && mClock.isRunning();
    if (mRecActive && !armedRunning) {
        rb.stopped = true;                      // terminó antes de este bloque: cerrar la toma
        mRecActive = false;
    } else if (!mRecActive && armedRunning) {
        if (phaseAtStart == TransportPhase::Running) {
            // Armar a mitad de reproducción, o arrancar sin conteo: empieza con el bloque.
            rb.started = true; rb.firstFrame = 0; rb.startPosition = posAtStart;
        } else if (countInEndFrame >= 0) {
            // El conteo acabó dentro de este bloque: empieza en el frame exacto del primer pulso de la grabación.
            rb.started = true; rb.firstFrame = countInEndFrame; rb.startPosition = mCountInOrigin;
        }
        if (rb.started) mRecActive = true;
    }
    rb.active = mRecActive;
    mRecBlock = rb;
    return mNumBeats;
}

void TransportEngine::renderClick(float* inOut, int numFrames) {
    // Duración de un pulso (frames) al tempo del momento: la subdivisión del click la usa
    // y se resincroniza en cada pulso (ver Metronome.h).
    const double framesPerBeat =
        tempo::secondsPerBeat(mClock.tempo()) * mClock.quartersPerPulse() * mSampleRate;
    mMetronome.render(inOut, numFrames, mBeats, mNumBeats, framesPerBeat, mGates);
}

} // namespace eliner
