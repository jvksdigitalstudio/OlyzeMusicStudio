#pragma once
// Subsistema de transporte del motor: UNA sola pieza que posee el tiempo musical.
//
// ── Responsabilidad ───────────────────────────────────────────────────────
// Reúne lo que antes estaba disperso dentro de AudioEngine:
//   - el reloj de transporte con precisión de muestra (TempoClock),
//   - el metrónomo (Metronome), consumidor del reloj en un bus propio,
//   - la publicación del pulso para la UI (BeatPulse.h, ADR 0029),
//   - la FASE del transporte (parado / conteo previo / en marcha, ADR 0035): el conteo de N compases que
//     precede a una grabación (CountIn.h) y la política que decide, pulso a pulso, si el click suena
//     (ClickPolicy.h). El metrónomo solo recibe el resultado (ClickGate);
//   - el ESTADO del delay sincronizado al tempo (activo + duración en pulsos).
//
// NO sabe de colas de comandos, de Oboe, de voces ni de la cadena de FX. En
// particular no toca el Delay: solo calcula cuántos segundos debe durar según el
// tempo (delaySyncSeconds); aplicárselo al módulo concreto es cosa del dueño
// (AudioEngine), que es quien conoce la cadena de FX.
//
// ── Hilos ─────────────────────────────────────────────────────────────────
//   prepare():            hilo de control, con el audio parado.
//   setters + advance() + renderClick():  hilo de audio ÚNICAMENTE.
//   pulseSnapshot():      CUALQUIER hilo (lectura atómica acquire).
//   positionSnapshot():   CUALQUIER hilo (lectura atómica acquire).
// Sin locks, sin asignación, sin E/S: seguro en tiempo real.

#include <atomic>
#include <cstdint>
#include "BeatPulse.h"
#include "BeatUnit.h"
#include "ClickPolicy.h"
#include "CountIn.h"
#include "Metronome.h"
#include "TempoClock.h"

namespace eliner {

class TransportEngine {
public:
    // Frecuencia real del stream (puede cambiar al reabrirlo). Si el reloj está
    // en marcha reescala el tiempo restante del pulso: la fase musical no se pierde.
    void prepare(int sampleRate);

    // ── Estado (hilo de audio) ──────────────────────────────────────────
    void setTempo(double bpm)            { mClock.setTempo(bpm); }
    void setBeatsPerBar(int beats)       { mClock.setBeatsPerBar(beats); }
    // Unidad de pulso = denominador del compás (BeatUnit.h): 2, 4, 8 o 16. Otro valor se IGNORA.
    // El tempo sigue en negras por minuto; solo cambia la duración de cada pulso.
    void setBeatUnit(int denominator);
    void setMetronomeEnabled(bool on)    { mMetronome.setEnabled(on); }
    void setMetronomeVolume(float v)     { mMetronome.setVolume(v); }
    // Click del metrónomo (ver ClickSound.h). Valores fuera de rango se ignoran.
    void setClickSound(int sound)        { mMetronome.setSound(sound); }
    void setClickBeatLevels(unsigned lo, unsigned hi) { mMetronome.setBeatLevels(lo, hi); }
    // Atenuación temporal del click (0–1), p. ej. el "Mute" del tap tempo. Ver Metronome::setDuck.
    void setClickDuck(float duck)        { mMetronome.setDuck(duck); }
    // "Sync" del tapper: reinicia el compás (el siguiente bloque empieza en el tiempo 1). Parado: no hace nada.
    void resyncBar() {
        mClock.restartBar();
        mMetronome.resetSubdivision(); // ningún click intermedio del compás anterior
    }
    void setClickSubdivision(int perBeat){ mMetronome.setSubdivision(perBeat); }
    void setClickSwing(int percent)      { mMetronome.setSwing(percent); } // ADR 0039

    // Arranca DESDE LA POSICIÓN ACTUAL (el primer tiempo si se acaba de crear o rebobinar; en el primer frame
    // del siguiente bloque; un arranque repetido no reinicia) o PAUSA (ADR 0036): detener conserva la posición,
    // así que el siguiente arranque continúa donde se paró. Para volver al principio, locate(0). Al detener
    // publica "no running" para que la UI vea la parada aunque no llegue otro pulso.
    //
    // ADR 0035/0036: si al ARRANCAR desde parado hay grabación armada (setRecordArmed) y un conteo configurado
    // (setCountInBars > 0), el transporte empieza en la fase CountIn: el reloj corre y el click suena
    // (Force) durante esos compases, y la POSICIÓN QUEDA RETENIDA donde se va a grabar; el pulso siguiente
    // es la posición de grabación (el tiempo que le toque, el 1 si es el origen) sin reiniciar el reloj. El
    // conteo es de compases COMPLETOS aunque la grabación no empiece en un compás («1-2-3-4» y entra en su
    // pulso), y la grabación empieza en la rejilla de pulsos: una posición a mitad de pulso se redondea hacia abajo. Arrancar con el transporte ya en marcha (p. ej. armar la grabación a mitad de reproducción)
    // NO inicia un conteo. Parar cancela el conteo.
    void setRunning(bool running);

    // ── Grabación y conteo previo (ADR 0035) ────────────────────────────
    // Grabación armada: afecta a si arrancar inicia un conteo y a qué pulsos suenan con ClickMode::RecordOnly.
    // No hay todavía captura de audio/MIDI: el motor solo conoce el ESTADO (la captura es de una fase posterior).
    // DESARMAR durante el conteo lo cancela y el transporte SIGUE como una reproducción normal (el reloj no se
    // toca: sin grabación no hay nada que esperar, y parar es decisión del usuario). Armar durante el conteo
    // o a mitad de reproducción no inicia ningún conteo.
    void setRecordArmed(bool armed);
    // Compases de conteo: 0, 1, 2 o 4 (CountIn.h). Otro valor se IGNORA. No afecta a un conteo ya en curso.
    void setCountInBars(int bars);
    // Cuándo suena el click (ClickMode en ClickPolicy.h). Fuera de rango se IGNORA.
    void setClickMode(int mode);
    // ── Ventana de grabación (ADR 0038) ─────────────────────────────────
    // Qué parte del ÚLTIMO bloque procesado por advance() está dentro de una grabación. Es lo que consume la
    // captura: sabe cuándo empezar y parar con precisión de muestra sin conocer conteos, pausas ni posiciones.
    // Hay grabación mientras la grabación está ARMADA, el transporte EN MARCHA y ya pasó el conteo previo; el
    // conteo, por tanto, NO se graba.
    struct RecordingBlock {
        bool         active = false;   // hay grabación en (parte de) este bloque
        bool         started = false;  // la grabación EMPIEZA en este bloque, en el frame [firstFrame]
        bool         stopped = false;  // la grabación TERMINÓ antes de este bloque (en su frame 0): cerrar la toma
        int          firstFrame = 0;   // primer frame del bloque que pertenece a la grabación (0 salvo al empezar tras el conteo)
        std::int64_t startPosition = 0; // posición de la canción (ticks) en el frame de inicio; solo con [started]
    };
    // Resultado del último advance(). Hilo de audio.
    const RecordingBlock& recordingBlock() const { return mRecBlock; }

    // ── Posición (ADR 0036) ─────────────────────────────────────────────
    // Coloca el transporte en [ticks] (Position.h; negativos se tratan como 0), en marcha o no. Cancela un
    // conteo previo en curso (el transporte sigue como reproducción normal desde la nueva posición).
    void locate(std::int64_t ticks);
    // Posición de la canción en ticks tal y como la vio el último advance() o la última orden de posición. Durante
    // el conteo previo es la posición donde empezará la grabación (retenida). CUALQUIER hilo (atómica).
    std::int64_t positionSnapshot() const { return mPosition.load(std::memory_order_acquire); }

    // Fase actual del transporte, tal y como la vio el último advance(). Hilo de audio.
    TransportPhase phase() const         { return mPhase; }

    // ── Delay sincronizado: SOLO estado y cálculo ───────────────────────
    void  setDelaySyncEnabled(bool enabled) { mDelaySyncEnabled = enabled; }
    void  setDelaySyncBeats(float beats)    { mDelaySyncBeats = beats; }
    bool  delaySyncEnabled() const          { return mDelaySyncEnabled; }
    // Duración del eco en segundos al tempo vigente, plegada por octavas a
    // [minSeconds, maxSeconds] (ver tempo::syncedDelaySeconds).
    double delaySyncSeconds(double minSeconds, double maxSeconds) const;

    // ── Por bloque ──────────────────────────────────────────────────────
    // Avanza el reloj UNA vez por bloque (haya o no metrónomo audible: es la
    // autoridad de tiempo para cualquier consumidor) y publica el pulso. Devuelve
    // cuántos pulsos cayeron en el bloque.
    int  advance(int numFrames);
    // Mezcla el click del bloque sobre [inOut], con los pulsos de la última
    // advance(). Debe llamarse DESPUÉS de FX y volumen master (bus propio).
    void renderClick(float* inOut, int numFrames);

    bool   isRunning() const { return mClock.isRunning(); }
    double tempo()     const { return mClock.tempo(); }

    // Instantánea de pulso empaquetada (formato en BeatPulse.h). Cualquier hilo.
    std::uint64_t pulseSnapshot() const { return mPulse.load(std::memory_order_acquire); }

private:
    // Posición de la canción ahora mismo (retenida durante el conteo) y su publicación atómica.
    std::int64_t songPosition() const;
    void         publishPosition();

    TempoClock mClock;
    Metronome  mMetronome;
    double     mSampleRate = 48000.0; // para framesPerBeat (subdivisión del click)

    bool  mDelaySyncEnabled = true;
    float mDelaySyncBeats   = 0.75f; // corchea con puntillo: 0,375 s a 120 BPM (el tiempo histórico del Delay)

    TempoClock::Beat mBeats[TempoClock::kMaxBeatsPerBlock];
    ClickGate        mGates[TempoClock::kMaxBeatsPerBlock]; // gates[i] ↔ mBeats[i] (ClickPolicy.h)
    int              mNumBeats = 0;

    // ── Grabación y conteo previo (ADR 0035) — propiedad del hilo de audio ──
    // Estado de la ventana de grabación (ADR 0038): la grabación está en curso entre bloques.
    bool           mRecActive = false;
    RecordingBlock mRecBlock;

    // La canción es una PROYECCIÓN del reloj: posición = reloj + mPositionOffset y tiempo del compás =
    // (reloj + mBeatShift) % pulsos-por-compás. Ambos valen 0 salvo tras un conteo previo: el conteo corre en el
    // reloj alineado al compás (cuenta «1-2-3-4» desde el primer tiempo) y al acabar, la canción continúa en la
    // posición de grabación, que puede ser cualquier pulso. Estos dos valores absorben la diferencia. locate()
    // y un nuevo conteo los ponen a 0.
    std::int64_t   mPositionOffset = 0;
    int            mBeatShift      = 0;
    std::int64_t   mCountInOrigin  = 0;   // posición donde empieza la grabación mientras dura el conteo
    CountIn        mCountIn;
    TransportPhase mPhase         = TransportPhase::Stopped;
    ClickMode      mClickMode     = ClickMode::Always;
    int            mCountInBars   = kCountInBarsDefault;
    bool           mRecordArmed   = false;

    std::uint64_t              mPulseSeq = 0; // propiedad del hilo de audio
    std::atomic<std::uint64_t> mPulse{0};     // copia atómica para cualquier hilo
    std::atomic<std::int64_t>  mPosition{0};  // posición de la canción en ticks, copia atómica (ADR 0036)
};

} // namespace eliner
