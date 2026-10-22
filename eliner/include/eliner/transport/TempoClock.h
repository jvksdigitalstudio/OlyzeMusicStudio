#pragma once
// Reloj de transporte con precisión de muestra.
//
// Única autoridad del tiempo musical dentro del motor: dado un tempo y el
// número de frames de cada bloque de audio, dice EN QUÉ FRAME exacto de ese
// bloque cae cada pulso. Los consumidores (metrónomo hoy; secuenciador,
// arpegiador o reloj MIDI nativo mañana) se limitan a reaccionar a esos
// eventos; ninguno calcula tiempo por su cuenta.
//
// ── Modelo ────────────────────────────────────────────────────────────────
// Se guarda cuántos frames faltan para el próximo pulso (`double`, fracción
// de muestra incluida) y se avanza bloque a bloque. No hay `delay()`, ni
// relojes de pared, ni acumulación de error de redondeo por pulso: la
// fracción de muestra se arrastra de un pulso al siguiente.
//
// Un cambio de tempo en mitad de un pulso reescala el tiempo restante de ese
// pulso en proporción, de modo que la fase musical es CONTINUA (sin saltos
// ni pulsos repetidos u omitidos al cambiar de BPM).
//
// ── Posición (ADR 0036) ───────────────────────────────────────────────────
// El reloj es el DUEÑO de la posición musical, en ticks (Position.h, 960 por negra). Cada pulso trae la suya
// (Beat::positionTicks) y positionTicks() da la del instante actual con resolución de muestra.
//   · pause()  detiene el reloj CONSERVANDO la posición y la fase dentro del pulso; start() continúa justo ahí.
//   · stop()   detiene Y rebobina a 0 (el siguiente start() empieza en el primer tiempo).
//   · locate() coloca el reloj en cualquier posición, también a mitad de pulso y también negativa (conteo
//     previo antes del compás 1); el siguiente pulso cae donde toca, con su tiempo dentro del compás correcto.
// La posición no depende del tamaño de bloque: es función de los frames avanzados y del tempo.
//
// ── Hilos ─────────────────────────────────────────────────────────────────
// Pertenece al hilo de audio una vez en marcha: todos los métodos se llaman
// desde allí (los cambios llegan como comandos por la cola SPSC, ver
// AudioEngine). `prepare()` puede llamarse desde el hilo de control SOLO
// mientras el stream no está activo (mismo contrato que buildDspGraph()).
// No asigna memoria, no bloquea y no hace E/S: seguro en tiempo real.

#include <cstdint>

#include "Position.h"

namespace eliner {

class TempoClock {
public:
    struct Beat {
        int           frameOffset; // [0, numFrames) dentro del bloque
        std::uint32_t beatInBar;   // 0 = primer tiempo del compás
        // Posición musical del INICIO de este pulso, en ticks (ADR 0036). Valor por defecto para que las
        // inicializaciones `Beat{offset, beat}` anteriores a la posición sigan siendo válidas.
        std::int64_t  positionTicks = 0;
    };

    // Cota de eventos por bloque. A 300 BPM y 8 kHz un pulso dura ≥1600
    // frames, por lo que un bloque realista contiene 1–2; el margen cubre
    // bloques enormes sin asignar. Los excedentes se descartan de la lista
    // devuelta, pero el contador de compás sigue siendo correcto.
    static constexpr int kMaxBeatsPerBlock = 8;

    static constexpr int kMinBeatsPerBar = 1;
    static constexpr int kMaxBeatsPerBar = 16;

    // Fija la frecuencia de muestreo (>0). Si el reloj está en marcha,
    // reescala el tiempo restante del pulso actual.
    void prepare(int sampleRate);

    // Tempo en BPM (negras por minuto), limitado a [20, 300]. Ignora
    // valores no finitos. Fase continua: ver cabecera.
    void setTempo(double bpm);

    // Pulsos por compás, limitado a [1, 16].
    void setBeatsPerBar(int beats);

    // Duración de un PULSO en negras (1.0 = negra, 0.5 = corchea…; ver BeatUnit.h). El tempo sigue en
    // negras por minuto. Valores no finitos o fuera de [kMinQuartersPerPulse, kMaxQuartersPerPulse]
    // se IGNORAN. Con el reloj en marcha reescala el tiempo restante del pulso (fase continua).
    void setQuartersPerPulse(double quarters);
    static constexpr double kMinQuartersPerPulse = 0.25; // semicorchea
    static constexpr double kMaxQuartersPerPulse = 2.0;  // blanca

    // Pone el reloj en marcha DESDE DONDE ESTÉ: tras construirlo o tras stop() es el primer tiempo (el pulso
    // 0 cae en el frame 0 del siguiente bloque); tras pause() o locate() es esa posición. No hace nada si ya
    // está en marcha (un "play" repetido no reinicia el compás).
    void start();

    // Reinicia el compás: el siguiente bloque empieza con un pulso en el tiempo 1 (beatInBar 0) en su primer
    // frame. Es el "Sync" del tapper de FL Studio (cada toque reinicia el tiempo 1). Con el reloj parado no hace
    // nada. NO compensa la latencia de salida de audio.
    void restartBar();

    // Detiene el reloj y rebobina a la posición 0: el siguiente start() vuelve al primer tiempo.
    void stop();
    // Detiene el reloj CONSERVANDO la posición y la fase dentro del pulso (la «pausa» de un DAW). El siguiente
    // start() continúa exactamente donde se paró.
    void pause();
    // Coloca el reloj en [ticks] (recortado a [kMinPositionTicks, kMaxPositionTicks]) esté o no en marcha. Si
    // cae a mitad de un pulso, el siguiente pulso llega cuando toca (fase continua); si cae en un límite de
    // pulso, en el frame 0 del siguiente bloque.
    void locate(std::int64_t ticks);
    // Posición actual en ticks, con resolución de muestra (hasta el final del último advance()).
    std::int64_t positionTicks() const;

    bool   isRunning()   const { return mRunning; }
    double tempo()       const { return mBpm; }
    int    beatsPerBar() const { return mBeatsPerBar; }
    double quartersPerPulse() const { return mQuartersPerPulse; }

    // Avanza [numFrames] frames y escribe en [out] (capacidad [maxOut]) los
    // pulsos que caen dentro del bloque, en orden creciente de frameOffset.
    // Devuelve cuántos escribió. Si no está en marcha devuelve 0.
    int advance(int numFrames, Beat* out, int maxOut);

private:
    double        mSampleRate       = 48000.0;
    double        mBpm              = 120.0;
    int           mBeatsPerBar      = 4;
    double        mQuartersPerPulse = 1.0;
    bool          mRunning          = false;
    double        mFramesToNextBeat = 0.0;
    std::uint32_t mBeatInBar        = 0;
    // Posición (ticks) del INICIO del próximo pulso que se emitirá (ADR 0036).
    std::int64_t  mNextPulseTicks   = 0;

    double       framesPerPulse() const { return mSampleRate * 60.0 / mBpm * mQuartersPerPulse; }
    std::int64_t pulseTicks()     const { return ticksPerPulse(mQuartersPerPulse); }
    // Fracción [0,1] del pulso en curso que AÚN falta por sonar.
    double       pulseRemainder() const;
};

} // namespace eliner
