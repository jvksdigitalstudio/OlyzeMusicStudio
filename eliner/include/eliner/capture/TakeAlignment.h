#pragma once
// Alineación de una toma con la línea de tiempo (ADR 0038): matemática pura de la compensación de latencia.
//
// ── Por qué hay que recortar el comienzo ──────────────────────────────────────────────────────────────
// El intérprete toca lo que OYE. Lo que oye (el click o la pista) salió del motor `outputLatency` antes de
// sonar, y lo que toca tarda `inputLatency` en llegar a la app. Por tanto, el frame k de la captura contiene lo
// que sonó en el instante de la línea de tiempo `k − (outputLatency + inputLatency)`: la toma llega RETRASADA un
// viaje de ida y vuelta (round trip). Para alinearla basta descartar los primeros `roundTrip` frames de la
// captura (que corresponden a antes del punto de grabación); el frame 0 de la toma pasa a ser el de la posición
// de grabación.
//
// Es una estimación, no una medida: la latencia de salida sale de marcas de tiempo del hardware (OutputLatency.h)
// y la de entrada, de las del stream de entrada. Cualquier desajuste residual (el propio intérprete, el
// transporte USB/BLE) se corrige a mano con un desfase manual por toma, como hacen los DAW.
#include <cmath>
#include <cstdint>

namespace eliner {

// Ms → frames, redondeado al más cercano. Un valor no finito o negativo cuenta como 0 (sin medida).
inline std::int64_t framesFromMillis(double ms, int sampleRate) {
    if (!std::isfinite(ms) || ms <= 0.0 || sampleRate <= 0) return 0;
    return static_cast<std::int64_t>(std::llround(ms * static_cast<double>(sampleRate) / 1000.0));
}

// Frames a descartar del comienzo de la captura: latencia de salida + de entrada, en frames. Una latencia sin
// medir (negativa o no finita) aporta 0 en lugar de inventarse un valor: la toma queda sin compensar esa parte
// y se puede corregir a mano. Se limita a [maxFrames] para que una medida absurda no se coma la toma entera.
inline std::int64_t trimFramesFor(double outputLatencyMs, double inputLatencyMs, int sampleRate,
                                  std::int64_t maxFrames = 0) {
    std::int64_t trim = framesFromMillis(outputLatencyMs, sampleRate) + framesFromMillis(inputLatencyMs, sampleRate);
    if (maxFrames > 0 && trim > maxFrames) trim = maxFrames;
    return trim;
}

} // namespace eliner
