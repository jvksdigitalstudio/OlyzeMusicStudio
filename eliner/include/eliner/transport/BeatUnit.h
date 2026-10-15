#pragma once
// Unidad de pulso del compás: la figura que cuenta cada pulso (el DENOMINADOR del compás).
//
// Header hoja, sin dependencias. Es la ÚNICA definición en C++; `BeatUnit` en Kotlin la replica y
// TransportConstantsMatchNativeTest lee ESTE archivo para que no puedan divergir.
//
// ── Qué cambia y qué NO ───────────────────────────────────────────────────
// El TEMPO sigue siendo SIEMPRE en negras por minuto (BPM). La unidad solo fija cuánto dura un PULSO del
// metrónomo: 1/denominador de redonda, es decir 4/denominador negras.
//   4/4 → pulso = negra (1 negra);   6/8 → pulso = corchea (0,5 negra);   12/16 → semicorchea (0,25).
// Por eso el delay sincronizado (pulsos en negras) y el reloj MIDI (24 por negra) NO se ven afectados.
//
// Un compás compuesto como 6/8 (dos grupos de tres) se consigue con la unidad de corchea (6 pulsos
// por compás) y un patrón de acentos que agrupe de tres en tres; el patrón por defecto de un compás
// compuesto ya lo hace (ver BeatPattern en Kotlin).

namespace eliner {

// Valor = denominador. Se envía tal cual.
enum class BeatUnit : int {
    Half      = 2,
    Quarter   = 4,
    Eighth    = 8,
    Sixteenth = 16,
};

constexpr bool isValidBeatUnit(int denominator) {
    return denominator == 2 || denominator == 4 || denominator == 8 || denominator == 16;
}

// Duración de un pulso expresada en negras. Precondición: isValidBeatUnit(denominator).
constexpr double quartersPerPulse(int denominator) { return 4.0 / static_cast<double>(denominator); }

} // namespace eliner
