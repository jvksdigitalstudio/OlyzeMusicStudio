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
// ── Hilos ─────────────────────────────────────────────────────────────────
// Pertenece al hilo de audio una vez en marcha: todos los métodos se llaman
// desde allí (los cambios llegan como comandos por la cola SPSC, ver
// AudioEngine). `prepare()` puede llamarse desde el hilo de control SOLO
// mientras el stream no está activo (mismo contrato que buildDspGraph()).
// No asigna memoria, no bloquea y no hace E/S: seguro en tiempo real.

#include <cstdint>

namespace eliner {

class TempoClock {
public:
    struct Beat {
        int           frameOffset; // [0, numFrames) dentro del bloque
        std::uint32_t beatInBar;   // 0 = primer tiempo del compás
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

    // Arranca desde el primer tiempo: el pulso 0 cae en el frame 0 del
    // siguiente bloque. No hace nada si ya está en marcha (un "play"
    // repetido no reinicia el compás).
    void start();

    // Detiene el reloj. El siguiente start() vuelve al primer tiempo.
    void stop();

    bool   isRunning()   const { return mRunning; }
    double tempo()       const { return mBpm; }
    int    beatsPerBar() const { return mBeatsPerBar; }

    // Avanza [numFrames] frames y escribe en [out] (capacidad [maxOut]) los
    // pulsos que caen dentro del bloque, en orden creciente de frameOffset.
    // Devuelve cuántos escribió. Si no está en marcha devuelve 0.
    int advance(int numFrames, Beat* out, int maxOut);

private:
    double        mSampleRate       = 48000.0;
    double        mBpm              = 120.0;
    int           mBeatsPerBar      = 4;
    bool          mRunning          = false;
    double        mFramesToNextBeat = 0.0;
    std::uint32_t mBeatInBar        = 0;
};

} // namespace eliner
