#pragma once
// Posición del transporte: vocabulario y conversiones PURAS (ADR 0036).
//
// La posición musical se mide en TICKS a una resolución fija de [kTicksPerQuarter] por negra (PPQ), la unidad
// que usan los DAW para colocar eventos (Logic y Pro Tools: 960; Cubase: 480–960). 960 divide exactamente la
// negra en 2, 3, 4, 5, 6, 8, 10, 12, 15, 16… partes, de modo que corcheas, tresillos, semicorcheas y quintillos
// caen en ticks enteros, y las cuatro unidades de pulso del metrónomo (blanca, negra, corchea, semicorchea)
// duran 1920, 960, 480 y 240 ticks.
//
// Todo aquí es constexpr y sin estado: ni reloj, ni hilos, ni E/S. El reloj (TempoClock) es el dueño de la
// posición; este header solo sabe convertirla.
//
// ── Posiciones negativas ──────────────────────────────────────────────────────────────────────────────
// Un conteo previo ante el compás 1 vive en posiciones NEGATIVAS (el compás 0, −1…). Por eso la posición es
// con signo y las conversiones usan división de SUELO (floorDiv/floorMod): −1 tick pertenece al último tick
// del compás anterior, no al primero del compás 0.
#include <cstdint>

namespace eliner {

constexpr int           kTicksPerQuarter = 960;
// La posición viaja por la cola de comandos como float (DspParameterId::TransportPosition), que representa
// EXACTAMENTE los enteros hasta 2^24: ese es el límite (≈ 2,4 h a 120 BPM, ≈ 58 min a 300 BPM). Pasarlo es
// recortar, nunca desbordar.
constexpr std::int64_t  kMaxPositionTicks = 1 << 24;
// El conteo previo mira como máximo 4 compases de 16 pulsos de blanca por detrás del origen.
constexpr std::int64_t  kMinPositionTicks = -static_cast<std::int64_t>(4) * 16 * 2 * kTicksPerQuarter;

// Duración de UN pulso en ticks. [quartersPerPulse] es el de BeatUnit.h (0.25, 0.5, 1.0, 2.0), siempre
// representable en ticks enteros; para cualquier otro valor se redondea al tick más cercano (mínimo 1).
constexpr std::int64_t ticksPerPulse(double quartersPerPulse) {
    const double t = quartersPerPulse * static_cast<double>(kTicksPerQuarter);
    const auto   r = static_cast<std::int64_t>(t + 0.5);
    return r < 1 ? 1 : r;
}

constexpr std::int64_t clampPositionTicks(std::int64_t ticks) {
    return ticks < kMinPositionTicks ? kMinPositionTicks
         : ticks > kMaxPositionTicks ? kMaxPositionTicks
         : ticks;
}

// División y módulo de SUELO para divisor > 0 (el `/` y `%` de C++ truncan hacia cero con negativos).
constexpr std::int64_t floorDiv(std::int64_t a, std::int64_t b) {
    const std::int64_t q = a / b;
    return (a % b != 0 && a < 0) ? q - 1 : q;
}
constexpr std::int64_t floorMod(std::int64_t a, std::int64_t b) { return a - floorDiv(a, b) * b; }

// ── MIDI Song Position Pointer ────────────────────────────────────────────────────────────────────────
// El SPP cuenta «MIDI beats» = SEMICORCHEAS (6 pulsos de reloj MIDI cada una), en 14 bits (0..16383).
constexpr int           kTicksPerMidiBeat = kTicksPerQuarter / 4;
constexpr int           kMaxSongPosition  = 16383;

// Posición → SPP. Redondea hacia ABAJO (un receptor no puede colocarse entre dos semicorcheas) y recorta al
// rango del mensaje: lo negativo es 0 y lo que excede 14 bits es el último valor representable.
constexpr int songPositionFor(std::int64_t ticks) {
    if (ticks <= 0) return 0;
    const std::int64_t beats = ticks / kTicksPerMidiBeat;
    return beats > kMaxSongPosition ? kMaxSongPosition : static_cast<int>(beats);
}

// Las dos mitades de 7 bits del mensaje (0xF2 LSB MSB).
constexpr int songPositionLsb(int songPosition) { return songPosition & 0x7F; }
constexpr int songPositionMsb(int songPosition) { return (songPosition >> 7) & 0x7F; }

// ── Compás | pulso | tick ─────────────────────────────────────────────────────────────────────────────
// Para mostrar la posición. Todo en base 0 (la UI suma 1). Con un compás CONSTANTE desde el origen; el mapa
// de compases llegará con el mapa de tempo (ADR 0036, hoja de ruta).
struct BarBeatTick {
    std::int64_t bar;   // compás (negativo antes del origen)
    int          beat;  // pulso dentro del compás, 0..beatsPerBar-1
    int          tick;  // tick dentro del pulso, 0..ticksPerPulse-1
};

constexpr BarBeatTick barBeatTickFor(std::int64_t ticks, int beatsPerBar, double quartersPerPulse) {
    const std::int64_t pulse = ticksPerPulse(quartersPerPulse);
    const std::int64_t index = floorDiv(ticks, pulse);
    const std::int64_t bpb   = beatsPerBar < 1 ? 1 : beatsPerBar;
    return BarBeatTick{floorDiv(index, bpb), static_cast<int>(floorMod(index, bpb)),
                       static_cast<int>(floorMod(ticks, pulse))};
}

} // namespace eliner
