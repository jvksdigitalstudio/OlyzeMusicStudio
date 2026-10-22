#pragma once
// Tempo → tiempo: utilidades puras (sin estado, sin asignación, sin E/S) para
// convertir un tempo y una duración musical en segundos.
//
// Seguras en el hilo de audio y en hilos de control. Header-only a propósito:
// es la única fuente de verdad del rango de tempo y de la regla de "plegado"
// musical, compartida por el reloj, el motor y los tests.
//
// NOTA -ffast-math: el build de producción compila con -ffast-math (ver
// CMakeLists.txt), que permite al compilador asumir que NaN/Inf no existen;
// bajo esa suposición std::isfinite puede optimizarse a `true`. Por eso la
// validación de entradas externas se hace por BITS (isFinite), que no
// depende de esa suposición.

#include <algorithm>
#include <bit>
#include <cstdint>

namespace eliner::tempo {

inline constexpr double kMinBpm     = 20.0;
inline constexpr double kMaxBpm     = 300.0;
inline constexpr double kDefaultBpm = 120.0;

// true si x no es NaN ni ±Inf, comprobado sobre la representación IEEE-754
// (exponente != todo unos). Inmune a -ffast-math / -ffinite-math-only.
inline bool isFinite(double x) noexcept {
    return ((std::bit_cast<std::uint64_t>(x) >> 52) & 0x7FFu) != 0x7FFu;
}
inline bool isFinite(float x) noexcept {
    return ((std::bit_cast<std::uint32_t>(x) >> 23) & 0xFFu) != 0xFFu;
}

// Limita [bpm] a [kMinBpm, kMaxBpm]. Precondición: isFinite(bpm).
inline double clampBpm(double bpm) noexcept {
    return std::clamp(bpm, kMinBpm, kMaxBpm);
}

// Duración de una negra (un pulso) en segundos.
inline double secondsPerBeat(double bpm) noexcept {
    return 60.0 / clampBpm(bpm);
}

// Duración de [beats] pulsos (negras) a [bpm], en segundos.
// Ej.: corchea con puntillo = 0.75 pulsos → a 120 BPM, 0.375 s.
inline double beatsToSeconds(double bpm, double beats) noexcept {
    return secondsPerBeat(bpm) * beats;
}

// Pliega [seconds] al rango [minSeconds, maxSeconds] por OCTAVAS (÷2 / ×2)
// en vez de recortar. Es la regla musical de los delays sincronizados: a un
// tempo lento una corchea con puntillo puede no caber en el buffer del
// delay. Recortarla rompería la relación rítmica con el pulso; su mitad (o
// su doble) es una subdivisión exacta del mismo ritmo y la conserva.
// Acotado a 32 iteraciones (nunca bucla indefinidamente).
inline double foldIntoRange(double seconds, double minSeconds, double maxSeconds) noexcept {
    for (int i = 0; i < 32 && seconds > maxSeconds; ++i) seconds *= 0.5;
    for (int i = 0; i < 32 && seconds < minSeconds; ++i) seconds *= 2.0;
    return std::clamp(seconds, minSeconds, maxSeconds);
}

// Tiempo de delay sincronizado: [beats] pulsos a [bpm], plegado a
// [minSeconds, maxSeconds]. Precondición: isFinite(beats) && beats > 0.
inline double syncedDelaySeconds(double bpm, double beats,
                                 double minSeconds, double maxSeconds) noexcept {
    return foldIntoRange(beatsToSeconds(bpm, beats), minSeconds, maxSeconds);
}

} // namespace eliner::tempo
