#pragma once
// Metrónomo: sintetiza el click y lo mezcla en el bloque de salida.
//
// Es un CONSUMIDOR del TempoClock: no calcula tiempo, solo recibe la lista
// de pulsos de cada bloque (con su frame exacto) y dispara el click en ese
// frame. Por eso el click cae en el sample correcto con independencia del
// tamaño de bloque, y el tempo no depende de ningún temporizador del SO.
//
// ── Bus propio ────────────────────────────────────────────────────────────
// El motor lo mezcla DESPUÉS de la cadena de efectos y del volumen master
// (ver AudioEngine::renderAudio): el click no pasa por reverb ni delay y no
// lo silencia el volumen master — como el bus de "click" de una DAW. Su
// nivel lo gobierna solo setVolume().
//
// ── Sonido ────────────────────────────────────────────────────────────────
// Cinco presets (ClickSound.h): Clásico, Madera, Beep, Cowbell y Hi-hat. Todos
// comparten ataque de 0.5 ms (evita el "pop" de un arranque brusco) y caída
// exponencial; cambian frecuencia, duración, parciales inarmónicos y ruido. El
// primer tiempo del compás suena más agudo y más fuerte (acento) y se puede
// desactivar. "Clásico" es bit a bit el sonido que tenía el motor antes de los
// presets: nada cambia para quien no toque el selector.
//
// ── Subdivisión ───────────────────────────────────────────────────────────
// Entre pulso y pulso puede sonar un click más suave (corcheas, tresillos,
// semicorcheas). El metrónomo cuenta frames desde cada pulso con el tempo del
// momento (framesPerBeat, que le pasa el TransportEngine); cada pulso nuevo
// RESINCRONIZA la cuenta, así que un cambio de tempo corrige la subdivisión en
// el siguiente pulso y nunca se acumula error. El TempoClock no cambia: sigue
// siendo la única autoridad de los PULSOS.
//
// ── Suavizado ─────────────────────────────────────────────────────────────
// La ganancia (volumen / activado) se suaviza por muestra (τ = 5 ms): mover
// el volumen o activar/desactivar en pleno click no produce zipper noise ni
// cortes bruscos. Desactivado y sin click vivo, render() no hace trabajo.
//
// Hilo: exclusivo del hilo de audio (los cambios llegan como comandos).
// Sin asignación, sin bloqueo, sin E/S.

#include <cstdint>
#include "ClickSound.h"
#include "TempoClock.h"

namespace eliner {

class Metronome {
public:
    // Frecuencia de muestreo (>0). Solo con el stream parado.
    void prepare(int sampleRate);

    void setEnabled(bool enabled) { mEnabled = enabled; }

    // Sonido (ClickSound como int). Un valor fuera de rango se IGNORA. Se aplica
    // al siguiente click: no corta el que esté sonando.
    void setSound(int sound);

    // Si false, el primer tiempo suena como el resto (sin más agudo ni más fuerte).
    void setAccentEnabled(bool enabled) { mAccentEnabled = enabled; }

    // Clics por pulso, 1..4 (ClickSubdivision). Fuera de rango se IGNORA.
    void setSubdivision(int perBeat);

    // Olvida la cuenta de subdivisión (el reloj se paró): ningún click suelto
    // tras detener el transporte. No corta el click que esté sonando.
    void resetSubdivision() { mSubsLeft = 0; }

    // Volumen lineal 0..1 (los valores fuera de rango o no finitos se
    // ignoran / limitan). Se aplica con una curva cuadrática, que se siente
    // más natural que la lineal en un control deslizante.
    void setVolume(float volume);

    // Suma el click a [inOut] (estéreo intercalado, [numFrames] frames).
    // [beats] son los pulsos de este bloque, ordenados por frameOffset.
    // [framesPerBeat] = duración de un pulso en frames al tempo actual; solo hace
    // falta para la subdivisión (con 0, o subdivisión 1, no hay clicks intermedios).
    void render(float* inOut, int numFrames, const TempoClock::Beat* beats, int numBeats,
                double framesPerBeat = 0.0);

    // true si no hay nada audible ni pendiente (activado = false, sin click
    // vivo y ganancia a cero).
    bool isIdle() const { return !mEnabled && !mActive && mGain == 0.0f; }

    bool  isEnabled() const { return mEnabled; }
    float volume()    const { return mVolume; }
    int   sound()     const { return mSound; }
    int   subdivision() const { return mSubdivision; }
    bool  accentEnabled() const { return mAccentEnabled; }

private:
    // [amp] = nivel relativo del click (1 acento, 0,75 pulso, menos en subdivisiones).
    void trigger(bool accent, float amp);

    double mSampleRate = 48000.0;
    bool   mEnabled    = false;
    float  mVolume     = 0.7f;

    // Ganancia suavizada.
    float mGain     = 0.0f;
    float mGainCoef = 0.0f;

    int    mSound         = 0;    // ClickSound
    bool   mAccentEnabled = true;
    int    mSubdivision   = 1;    // clics por pulso

    // Subdivisión: clicks intermedios pendientes del pulso actual.
    int    mSubsLeft     = 0;
    double mSubInterval  = 0.0;   // frames entre clicks intermedios
    double mSubCountdown = 0.0;   // frames hasta el siguiente

    // Voz del click (una sola: un click nuevo reinicia la anterior). Los parámetros
    // del preset se copian al DISPARAR, así cambiar de sonido no altera el que suena.
    bool   mActive      = false;
    double mPhase       = 0.0;  // [0,1)
    double mPhaseInc    = 0.0;
    double mPhase2      = 0.0;  // parcial inarmónico
    double mPhase2Inc   = 0.0;
    float  mEnv         = 0.0f;
    float  mEnvDecay    = 0.0f; // multiplicador por muestra
    float  mAmp         = 0.0f;
    float  mToneAmp     = 1.0f;
    float  mPartialAmp  = 0.0f;
    float  mNoiseAmp    = 0.0f;
    float  mNorm        = 1.0f; // 1 / (suma de amplitudes): el pico no crece con los parciales
    float  mNoiseLast   = 0.0f; // para realzar agudos (diferencia de muestras)
    std::uint32_t mRng  = 0x9E3779B9u; // xorshift32: determinista, sin asignación
    int    mAttackPos   = 0;
    int    mAttackLen   = 1;
};

} // namespace eliner
