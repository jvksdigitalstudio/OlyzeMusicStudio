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

} // namespace eliner
