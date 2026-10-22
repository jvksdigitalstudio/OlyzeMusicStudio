#pragma once
// Política del click: CUÁNDO puede sonar el metrónomo según el estado del transporte.
//
// Funciones puras y constexpr, sin estado: el TransportEngine las evalúa para cada pulso del bloque y pasa
// el resultado (ClickGate) al Metronome, que no sabe nada de grabación ni de conteo. Así la regla vive en
// UN sitio, se prueba sin audio y el metrónomo sigue siendo un consumidor tonto del reloj.
//
// Modelo (el mismo vocabulario que Pro Tools «Click/Countoff», Cubase «Metronome Setup» y Logic
// «Metronome Settings»): el botón del metrónomo es el interruptor general, [ClickMode] elige EN QUÉ fases
// del transporte suena, y el conteo previo es una función aparte que suena aunque el botón esté apagado.
//
// Header hoja. [ClickMode] es la ÚNICA definición en C++ de estos valores; la capa Kotlin (`ClickMode`) la
// replica y TransportConstantsMatchNativeTest lee ESTE archivo.
#include "ClickSound.h"

namespace eliner {

// Orden = índice enviado por JNI. Append-only (los valores no se reutilizan).
enum class ClickMode : int {
    Always      = 0, // reproduciendo y grabando (el comportamiento histórico del metrónomo)
    RecordOnly  = 1, // solo mientras hay una grabación armada; reproduciendo sin grabar, silencio
    CountInOnly = 2, // solo el conteo previo; después de él, silencio
    Count       = 3, // centinela: NO es un modo
};

// Fase del transporte tal y como la ve el click.
enum class TransportPhase : int {
    Stopped = 0, // parado: el reloj no emite pulsos
    CountIn = 1, // conteo previo a grabar
    Running = 2, // reproduciendo (o grabando, si hay grabación armada)
};

constexpr bool isValidClickMode(int mode) {
    return mode >= 0 && mode < static_cast<int>(ClickMode::Count);
}

// Decisión para UN pulso. El conteo siempre suena (Force): existe precisamente para oírse y es
// independiente del botón del metrónomo. En marcha, [mode] decide; el botón sigue siendo el interruptor
// general (Follow), de modo que un metrónomo apagado no suena nunca fuera del conteo.
constexpr ClickGate gateFor(ClickMode mode, TransportPhase phase, bool recordArmed) {
    switch (phase) {
        case TransportPhase::CountIn: return ClickGate::Force;
        case TransportPhase::Running:
            switch (mode) {
                case ClickMode::Always:      return ClickGate::Follow;
                case ClickMode::RecordOnly:  return recordArmed ? ClickGate::Follow : ClickGate::Silence;
                case ClickMode::CountInOnly: return ClickGate::Silence;
                case ClickMode::Count:       break;
            }
            return ClickGate::Follow;
        case TransportPhase::Stopped: break;
    }
    return ClickGate::Silence;
}

} // namespace eliner
