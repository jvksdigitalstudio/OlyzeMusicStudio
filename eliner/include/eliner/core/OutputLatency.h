#pragma once
// Estimador de la latencia de SALIDA de audio (ADR 0037): cuánto pasa desde que el motor entrega un bloque
// hasta que el conversor lo convierte en sonido. Es el retraso entre lo que el motor «hace» (un pulso, un
// click) y lo que el usuario OYE, y por tanto lo que hay que descontar para que lo que se ve coincida con
// lo que se oye.
//
// Header-only y puro: no conoce Oboe ni hilos. Quien lo usa (AudioEngine, hilo de control) le entrega las
// muestras que mide `AudioStream::calculateLatencyMillis()` y lee el resultado.
//
// ── Por qué la MEDIANA y no la última muestra ni la media ────────────────────────────────────────────
// La latencia que informa el sistema sale de marcas de tiempo del hardware (`getTimestamp`) y tiene
// valores atípicos esporádicos (una marca vieja o adelantada, un cambio de ruta de audio). Una media los
// arrastra; la última muestra los hereda enteros. La mediana de las últimas [kWindow] muestras ignora esos
// valores aislados y converge en pocas muestras.
#include <algorithm>
#include <cmath>

namespace eliner {

class OutputLatencyEstimator {
public:
    static constexpr int    kWindow       = 9;
    // Una latencia de salida real está entre unos pocos ms (AAudio exclusivo) y unos cientos (Bluetooth).
    // Fuera de (0, kMaxPlausibleMs] es una medida corrupta y se descarta, no se recorta.
    static constexpr double kMaxPlausibleMs = 1000.0;

    // Añade una muestra en milisegundos. Devuelve false si se descartó (no finita, ≤ 0 o implausible).
    bool add(double ms) {
        if (!std::isfinite(ms) || ms <= 0.0 || ms > kMaxPlausibleMs) return false;
        mSamples[mNext] = ms;
        mNext = (mNext + 1) % kWindow;
        if (mCount < kWindow) ++mCount;
        return true;
    }

    // Hay al menos una muestra válida.
    bool valid() const { return mCount > 0; }
    int  count() const { return mCount; }

    // Mediana de las muestras vigentes (media de las dos centrales si son pares). 0 si no hay ninguna.
    double millis() const {
        if (mCount == 0) return 0.0;
        double tmp[kWindow];
        std::copy(mSamples, mSamples + mCount, tmp);
        std::sort(tmp, tmp + mCount);
        const int mid = mCount / 2;
        return (mCount % 2 == 1) ? tmp[mid] : 0.5 * (tmp[mid - 1] + tmp[mid]);
    }

    void reset() { mCount = 0; mNext = 0; }

private:
    double mSamples[kWindow] = {};
    int    mNext  = 0;
    int    mCount = 0;
};

} // namespace eliner
