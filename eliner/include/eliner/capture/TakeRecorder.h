#pragma once
// Grabador de UNA toma de audio hacia un archivo WAV (ADR 0038): lleva las muestras que captura el hilo de audio
// hasta el disco sin que el hilo de audio llegue a esperar nunca.
//
//   hilo de audio ── onInput() ──► CaptureRing (SPSC, sin bloqueos) ──► hilo escritor ──► WavWriter ──► archivo
//
// Responsabilidad única: mover muestras al disco, descartando el comienzo que pide la compensación de latencia
// (TakeAlignment.h). NO sabe cuándo empezar ni parar (lo decide el transporte), no abre streams de audio, no
// conoce JNI ni Android: es C++ estándar y se prueba entero sin dispositivo.
//
// ── Contrato de hilos ─────────────────────────────────────────────────────────────────────────────────
//   begin(), end():   hilo de CONTROL (serializados entre sí por un candado interno; no tiempo real).
//   onInput():        hilo de AUDIO. Tiempo real: sin reservas, sin candados, sin E/S. No hace nada si no se está
//                     grabando, de modo que puede llamarse siempre con el audio de entrada.
//   recording():      cualquier hilo.
// end() es DETERMINISTA: cuando devuelve, todo lo que onInput() aceptó antes de la llamada está en el archivo y
// el archivo está cerrado (o se informa del fallo). Una llamada a onInput() en vuelo cuando se invoca end() se
// espera (microsegundos), de modo que no se pierde ni se escribe tras el cierre.
//
// ── Fallos y qué se informa ───────────────────────────────────────────────────────────────────────────
//   · Disco lento y anillo lleno: se descartan muestras (hueco en la toma) y se cuentan en droppedFrames. El audio
//     NUNCA se bloquea por eso.
//   · Error de escritura (disco lleno): la toma se corta; Result::ok = false.
//   · begin() devuelve false si no pudo abrir el archivo o ya hay una toma en curso.
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>

#include "CaptureRing.h"
#include "WavWriter.h"

namespace eliner {

class TakeRecorder {
public:
    struct Config {
        int           sampleRate = 48000;
        int           channels   = 1;
        // Frames a descartar del comienzo de la captura (compensación de latencia, TakeAlignment.h). ≥ 0.
        std::int64_t  trimFrames = 0;
        // Capacidad del anillo en frames (se redondea a potencia de 2). 0 = 2 segundos a la frecuencia dada.
        std::size_t   ringFrames = 0;
    };

    struct Result {
        bool          ok            = false; // el archivo se escribió y cerró sin errores
        std::uint64_t framesWritten = 0;     // frames que acabaron en el archivo
        std::uint64_t trimmedFrames = 0;     // frames descartados del comienzo por la compensación
        std::uint64_t droppedFrames = 0;     // frames perdidos por anillo lleno (hueco en la toma)
    };

    TakeRecorder() = default;
    ~TakeRecorder();
    TakeRecorder(const TakeRecorder&) = delete;
    TakeRecorder& operator=(const TakeRecorder&) = delete;

    bool begin(const char* path, const Config& config);
    void onInput(const float* frames, std::size_t frameCount);
    Result end();

    bool recording() const { return mActive.load(std::memory_order_acquire); }

private:
    void writerLoop();

    std::mutex                  mControl;        // serializa begin()/end()
    CaptureRing                 mRing;
    WavWriter                   mWav;
    std::thread                 mWriter;
    std::atomic<bool>           mActive{false};  // onInput() acepta muestras
    std::atomic<int>            mPushing{0};     // onInput() en vuelo
    std::atomic<bool>           mStopWriter{false};
    std::atomic<bool>           mWriteFailed{false};
    std::atomic<std::uint64_t>  mTrimmed{0};
    std::int64_t                mTrimRemaining = 0;  // solo lo toca el hilo escritor
    int                         mChannels      = 0;
    bool                        mBegun         = false;
};

} // namespace eliner
