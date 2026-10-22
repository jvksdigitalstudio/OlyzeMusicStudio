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
// exponencial; cambian frecuencia, duración, parciales inarmónicos y ruido.
// "Clásico" es bit a bit el sonido que tenía el motor antes de los presets: nada
// cambia para quien no toque el selector.
//
// ── Intensidad por tiempo (ADR 0032) ──────────────────────────────────────
// Cada tiempo del compás tiene su nivel (BeatLevel): Mute (no suena, ni sus
// subdivisiones), Soft, Medium o Accent (más fuerte y más agudo). Por defecto el
// primer tiempo es Accent y el resto Medium, que es el sonido histórico. El
// patrón se fija entero y de una vez (setBeatLevels).
//
// ── Subdivisión ───────────────────────────────────────────────────────────
// Entre pulso y pulso puede sonar un click más suave (corcheas, tresillos,
// semicorcheas). El metrónomo cuenta frames desde cada pulso con el tempo del
// momento (framesPerBeat, que le pasa el TransportEngine); cada pulso nuevo
// RESINCRONIZA la cuenta, así que un cambio de tempo corrige la subdivisión en
// el siguiente pulso y nunca se acumula error. El TempoClock no cambia: sigue
// siendo la única autoridad de los PULSOS.
//
// ── Decisión por pulso (ADR 0035) ─────────────────────────────────────────
// Además de la lista de pulsos, render() acepta una lista PARALELA de ClickGate (una decisión por pulso,
// ver ClickSound.h) que calcula el TransportEngine con ClickPolicy.h. El metrónomo no sabe QUÉ es un
// conteo previo ni una grabación: solo obedece al gate de cada pulso.
//   Follow  → suena si el metrónomo está activado (comportamiento de siempre).
//   Silence → este pulso, y sus subdivisiones, no suenan.
//   Force   → suena aunque el metrónomo esté apagado (el conteo previo a grabar).
// Sin lista de gates (nullptr) todos los pulsos son Follow: el contrato histórico no cambia.
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

    // Atenuación temporal del click, 0.0 (mudo) – 1.0 (sin atenuar), independiente del volumen del usuario y
    // multiplicada con él. La usa el "Mute" del tap tempo (silenciar mientras se mide y volver a entrar
    // gradualmente). Se suaviza igual que el volumen (sin chasquidos). Un valor no finito se IGNORA; fuera de
    // [0, 1] se recorta (es una ganancia, no un índice).
    void setDuck(float duck);

    // Al DESACTIVAR se cancelan los clicks de subdivisión pendientes: con el metrónomo apagado
    // render() deja de procesar (isIdle), así que la cuenta quedaría congelada y, al reactivarlo
    // a mitad de un pulso, sonaría un click intermedio huérfano sin pulso que lo origine.
    void setEnabled(bool enabled) {
        mEnabled = enabled;
        if (!enabled) mSubsLeft = 0;
    }

    // Sonido (ClickSound como int). Un valor fuera de rango se IGNORA. Se aplica
    // al siguiente click: no corta el que esté sonando.
    void setSound(int sound);

    // Patrón de intensidad por tiempo, empaquetado (ver ClickSound.h: 2 bits por tiempo, tiempos
    // 0–7 en [lo] y 8–15 en [hi]). Si cualquiera de las dos palabras excede 16 bits se IGNORAN ambas.
    // Por defecto: primer tiempo acentuado y el resto normales (el sonido histórico del metrónomo).
    void setBeatLevels(unsigned lo, unsigned hi);

    // Clics por pulso, 1..4 (ClickSubdivision). Fuera de rango se IGNORA.
    void setSubdivision(int perBeat);

    // Swing de la subdivisión, en % del par (kMinClickSwingPercent..kMaxClickSwingPercent; 50 = recto). Fuera de
    // rango se IGNORA. Retrasa el click de subdivisión de cada PAR: con 2 por pulso (corcheas) el de en medio cae
    // al swing% del pulso; con 4 (semicorcheas) cada par se reparte swing%/(100-swing)% de media negra. Con 3
    // por pulso (tresillos) no hay par que desplazar y se ignora. El pulso (tiempo fuerte) NUNCA se mueve, y el
    // reloj no sabe nada del swing: es una propiedad del click (ADR 0039). Se aplica en el siguiente pulso.
    void setSwing(int percent);

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
    //
    // [gates]: nullptr, o un array de al menos [numBeats] decisiones, gates[i] para beats[i] (ver
    // «Decisión por pulso» arriba).
    void render(float* inOut, int numFrames, const TempoClock::Beat* beats, int numBeats,
                double framesPerBeat = 0.0, const ClickGate* gates = nullptr);

    // true si no hay nada audible ni pendiente (activado = false, sin click
    // vivo y ganancia a cero).
    bool isIdle() const { return !mEnabled && !mActive && mGain == 0.0f; }

    bool  isEnabled() const { return mEnabled; }
    float volume()    const { return mVolume; }
    int   sound()     const { return mSound; }
    int   subdivision() const { return mSubdivision; }
    int   swingPercent() const { return mSwingPercent; }
    int   beatLevel(int beat) const { return (beat >= 0 && beat < kMaxPatternBeats) ? mLevels[beat] : 2; }

private:
    // [accent] = timbre agudo del acento; [amp] = nivel relativo del click (ver kLevelAmp).
    void trigger(bool accent, float amp);

    double mSampleRate = 48000.0;
    bool   mEnabled    = false;
    // El click que suena (o la subdivisión pendiente) nació de un pulso Force con el metrónomo apagado:
    // mientras dure, la ganancia objetivo sigue abierta. Se cierra al terminar el click (ver render()).
    bool   mForced     = false;
    float  mVolume     = 0.7f;
    float  mDuck       = 1.0f;   // atenuación temporal (tap mute); 1 = sin atenuar

    // Ganancia suavizada.
    float mGain     = 0.0f;
    float mGainCoef = 0.0f;

    int    mSound         = 0;    // ClickSound
    // Nivel (BeatLevel) de cada tiempo del compás. Defecto: Accent, Medium × 15.
    std::uint8_t mLevels[kMaxPatternBeats] = {3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2};
    int    mSubdivision   = 1;    // clics por pulso
    int    mSwingPercent  = kDefaultClickSwingPercent;

    // Subdivisión: clicks intermedios pendientes del pulso actual.
    int    mSubsLeft     = 0;
    double mSubInterval  = 0.0;   // frames entre clicks intermedios (rectos) = framesPerBeat / subdivisión
    int    mSubGap       = 0;     // hueco en curso (0 = entre el pulso y el 1.er click intermedio)
    double mSubFirst     = 0.0;   // duración de los huecos PARES (0, 2…) y de los IMPARES, con swing (ADR 0039)
    double mSubSecond    = 0.0;
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
