#pragma once
// Subsistema de transporte del motor: UNA sola pieza que posee el tiempo musical.
//
// ── Responsabilidad ───────────────────────────────────────────────────────
// Reúne lo que antes estaba disperso dentro de AudioEngine:
//   - el reloj de transporte con precisión de muestra (TempoClock),
//   - el metrónomo (Metronome), consumidor del reloj en un bus propio,
//   - la publicación del pulso para la UI (BeatPulse.h, ADR 0029),
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
// Sin locks, sin asignación, sin E/S: seguro en tiempo real.

#include <atomic>
#include <cstdint>
#include "BeatPulse.h"
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
    void setMetronomeEnabled(bool on)    { mMetronome.setEnabled(on); }
    void setMetronomeVolume(float v)     { mMetronome.setVolume(v); }
    // Click del metrónomo (ver ClickSound.h). Valores fuera de rango se ignoran.
    void setClickSound(int sound)        { mMetronome.setSound(sound); }
    void setClickAccent(bool enabled)    { mMetronome.setAccentEnabled(enabled); }
    void setClickSubdivision(int perBeat){ mMetronome.setSubdivision(perBeat); }

    // Arranca (primer tiempo en el primer frame del siguiente bloque; un arranque
    // repetido no reinicia) o detiene. Al detener publica "no running" para que la
    // UI vea la parada aunque no llegue otro pulso.
    void setRunning(bool running);

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
    TempoClock mClock;
    Metronome  mMetronome;
    double     mSampleRate = 48000.0; // para framesPerBeat (subdivisión del click)

    bool  mDelaySyncEnabled = true;
    float mDelaySyncBeats   = 0.75f; // corchea con puntillo: 0,375 s a 120 BPM (el tiempo histórico del Delay)

    TempoClock::Beat mBeats[TempoClock::kMaxBeatsPerBlock];
    int              mNumBeats = 0;

    std::uint64_t              mPulseSeq = 0; // propiedad del hilo de audio
    std::atomic<std::uint64_t> mPulse{0};     // copia atómica para cualquier hilo
};

} // namespace eliner
