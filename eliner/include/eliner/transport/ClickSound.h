#pragma once
// Vocabulario del click del metrónomo: qué sonido y qué subdivisión.
//
// Header hoja, sin dependencias. Es la ÚNICA definición en C++ de estos valores;
// la capa Kotlin (`MetronomeSound`, `ClickSubdivision`) los replica y
// TransportConstantsMatchNativeTest lee ESTE archivo para que no puedan divergir.
// Los valores viajan por el canal de parámetros (un float por parámetro) y se
// validan en el motor: un valor fuera de rango se ignora, nunca se recorta a otro.

namespace eliner {

// Orden = índice enviado por JNI. Append-only (los valores no se reutilizan).
enum class ClickSound : int {
    Classic = 0, // seno limpio y corto (el sonido histórico del motor)
    Wood    = 1, // bloque de madera: parciales inarmónicos, caída muy rápida
    Beep    = 2, // pitido electrónico, más largo y grave
    Cowbell = 3, // cencerro: dos parciales inarmónicos, caída larga
    Hat     = 4, // hi-hat: ráfaga de ruido muy corta
    Count   = 5, // centinela: NO es un sonido
};

// Valor = clics por pulso (1 = solo el pulso). Se envía tal cual.
enum class ClickSubdivision : int {
    None        = 1,
    Eighths     = 2,
    Triplets    = 3,
    Sixteenths  = 4,
};

constexpr int kMinClickSubdivision = 1;
constexpr int kMaxClickSubdivision = 4;

// ── Swing de la subdivisión (ADR 0039) ────────────────────────────────────────────────────────────────
// Porcentaje del PAR de subdivisiones que ocupa la primera: 50 = recto (sin swing), 67 ≈ tresillo, 75 = punto-y-
// semi. Es el mismo eje que el «Swing» de Logic y Cubase. Única definición en C++; la capa Kotlin (`ClickSwing`)
// la replica y TransportConstantsMatchNativeTest lee este archivo.
constexpr int kMinClickSwingPercent     = 50;
constexpr int kMaxClickSwingPercent     = 75;
constexpr int kDefaultClickSwingPercent = 50;

// Intensidad de CADA tiempo del compás (patrón de acentos). Orden = valor enviado por JNI.
enum class BeatLevel : int {
    Mute   = 0, // el tiempo no suena (ni sus subdivisiones)
    Soft   = 1, // suave
    Medium = 2, // normal
    Accent = 3, // acento: más fuerte y más agudo
    Count  = 4, // centinela: NO es un nivel
};

// Decisión POR PULSO sobre si el metrónomo debe sonar, calculada por quien conoce el contexto de transporte
// (ver ClickPolicy.h) y entregada al Metronome junto con la lista de pulsos del bloque. Solo existe en C++:
// no cruza JNI. Orden = valor numérico (se usa en arrays de uint8_t).
enum class ClickGate : unsigned char {
    Follow  = 0, // el interruptor del usuario manda: suena solo si el metrónomo está activado
    Silence = 1, // este pulso no suena (ni sus subdivisiones), esté o no activado el metrónomo
    Force   = 2, // este pulso suena AUNQUE el metrónomo esté apagado (el conteo previo a grabar)
};

// El patrón viaja EMPAQUETADO, 2 bits por tiempo, en dos palabras de 16 bits (tiempos 0–7 y 8–15):
// 16 bits caben exactos en un float/int de los comandos y las dos palabras viajan en UN solo comando
// (SetMetronomeBeatLevels), así que el hilo de audio nunca oye un patrón a medias.
constexpr int kMaxPatternBeats    = 16; // = TempoClock::kMaxBeatsPerBar
constexpr int kBeatLevelBits      = 2;
constexpr int kBeatsPerPatternWord = 8;
constexpr unsigned kPatternWordMax = 0xFFFFu;

// Nivel del tiempo [beat] (0..15) dentro del patrón empaquetado (lo, hi).
constexpr int beatLevelOf(unsigned lo, unsigned hi, int beat) {
    const unsigned word = beat < kBeatsPerPatternWord ? lo : hi;
    return static_cast<int>((word >> (kBeatLevelBits * (beat % kBeatsPerPatternWord))) & 0x3u);
}

} // namespace eliner
