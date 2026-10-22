#pragma once
// Posición de pulso publicada por el hilo de audio para la UI (ADR 0029).
//
// El hilo de audio es el único dueño del reloj de transporte (TempoClock). La
// UI necesita saber "en qué tiempo del compás estamos" para dibujar un
// indicador, pero NO puede consultar el reloj (no es seguro entre hilos). La
// solución es publicar una instantánea mínima en un único std::atomic<uint64_t>:
// el hilo de audio la escribe una vez por bloque en el que cae un pulso y
// cualquier hilo puede leerla sin bloquear ni asignar.
//
// ── Formato (64 bits, sin signo; siempre cabe en un jlong positivo) ───────
//   bits  0..7   beatInBar  — tiempo dentro del compás (0 = primer tiempo)
//   bit   8      running    — 1 mientras el transporte está en marcha
//   bit   9      countIn    — 1 mientras el pulso pertenece al conteo previo a grabar (ADR 0035)
//   bits 10..11  countInBar — compás del conteo (0-based, 0..3); 0 si countIn == 0
//   bits 12..15  reservados (0)
//   bits 16..62  sequence   — contador monótono de pulsos desde que existe
//                             el motor; cambia en CADA pulso, incluso si el
//                             compás es de 1 tiempo (beatInBar no cambiaría)
//
// El valor 0 es "motor sin pulsos todavía / transporte parado desde el inicio".
//
// La secuencia útil llega a 2^47-1 (bit 63 siempre 0 → positivo como jlong).
//
// Este archivo es la ÚNICA definición del formato en C++. El decodificador de
// Kotlin (`BeatPulse.fromPacked`) lo replica y un test JVM lo fija contra
// estas mismas constantes (ver TransportConstantsMatchNativeTest).
//
// Header-only, constexpr, sin dependencias: seguro en tiempo real.

#include <cstdint>

namespace eliner::pulse {

constexpr std::uint64_t kBeatMask    = 0xFFu;
constexpr std::uint64_t kRunningBit  = 1ull << 8;
constexpr std::uint64_t kCountInBit  = 1ull << 9;
constexpr int           kCountInBarShift = 10;
constexpr std::uint64_t kCountInBarMask  = 0x3ull; // 4 compases de conteo como máximo (CountIn.h)
constexpr int           kSeqShift    = 16;

// [countIn]/[countInBar] son opcionales: sin ellos el valor es idéntico al formato anterior al ADR 0035.
constexpr std::uint64_t pack(std::uint64_t sequence, std::uint32_t beatInBar, bool running,
                             bool countIn = false, std::uint32_t countInBar = 0) {
    return (sequence << kSeqShift)
         | (countIn ? kCountInBit : 0ull)
         | (countIn ? ((static_cast<std::uint64_t>(countInBar) & kCountInBarMask) << kCountInBarShift) : 0ull)
         | (running ? kRunningBit : 0ull)
         | (static_cast<std::uint64_t>(beatInBar) & kBeatMask);
}

constexpr std::uint64_t sequenceOf(std::uint64_t packed)  { return packed >> kSeqShift; }
constexpr std::uint32_t beatInBarOf(std::uint64_t packed) { return static_cast<std::uint32_t>(packed & kBeatMask); }
constexpr bool          runningOf(std::uint64_t packed)   { return (packed & kRunningBit) != 0; }
constexpr bool          countInOf(std::uint64_t packed)   { return (packed & kCountInBit) != 0; }
constexpr std::uint32_t countInBarOf(std::uint64_t packed) {
    return static_cast<std::uint32_t>((packed >> kCountInBarShift) & kCountInBarMask);
}

} // namespace eliner::pulse
