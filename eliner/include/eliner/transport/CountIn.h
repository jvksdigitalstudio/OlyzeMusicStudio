#pragma once
// Conteo previo a grabar ("count-in" / "countoff"): cuántos compases suenan antes de que empiece la grabación.
//
// Es una máquina de estados PURA: no conoce el reloj, el metrónomo ni los hilos. El TransportEngine le
// entrega cada pulso del reloj, en orden, y ella responde si ese pulso pertenece al conteo o ya es el
// primero de la grabación. Header-only a propósito (como TempoSync.h): sin estado global, sin asignación,
// sin E/S; segura en el hilo de audio.
//
// ── Por qué se cuenta en COMPASES y no en pulsos ──────────────────────────────────────────────────────
// Un conteo de N compases termina SIEMPRE en una barra de compás, aunque el usuario cambie el compás o la
// unidad de pulso mientras suena. Si se contaran pulsos (N × pulsos-por-compás al armarlo) un cambio de
// compás a mitad dejaría la grabación empezando en mitad de un compás. El compás se da por completado al
// pasar su ÚLTIMO pulso (beatInBar + 1 >= beatsPerBar, con el compás vigente en ese momento).
//
// ── El primer pulso de la grabación ───────────────────────────────────────────────────────────────────
// El reloj no se reinicia al acabar el conteo: sigue corriendo, y el pulso que sigue al último del conteo
// es el primero de la grabación (tiempo 1 del compás 1) en su frame exacto. No hay hueco ni solape.
#include <cstdint>

namespace eliner {

// Duraciones válidas del conteo, en compases. 0 = sin conteo. Es la ÚNICA definición en C++; la capa
// Kotlin (`CountInLength`) la replica y TransportConstantsMatchNativeTest lee este archivo.
constexpr int kCountInBarsOff     = 0;
constexpr int kCountInBarsMax     = 4;
// Duración por defecto: 1 compás, como el «Count-in: 1 Bar» de Ableton Live. Solo se oye si hay una grabación
// armada, así que reproducir sin grabar sigue siendo idéntico a antes. La capa Kotlin replica este valor.
constexpr int kCountInBarsDefault = 1;

constexpr bool isValidCountInBars(int bars) {
    return bars == 0 || bars == 1 || bars == 2 || bars == kCountInBarsMax;
}

class CountIn {
public:
    // Resultado de procesar un pulso.
    struct Step {
        bool countIn;  // true: el pulso pertenece al conteo. false: ya es de la grabación (o no hay conteo).
        int  barIndex; // compás del conteo, 0-based (solo significativo con countIn == true).
    };

    // Empieza un conteo de [bars] compases (1..kCountInBarsMax). Un valor fuera de rango no arma nada.
    void arm(int bars) {
        if (bars < 1 || bars > kCountInBarsMax) { cancel(); return; }
        mActive    = true;
        mBars      = bars;
        mBarsDone  = 0;
    }

    // Aborta el conteo (parada del transporte). Idempotente.
    void cancel() { mActive = false; mBars = 0; mBarsDone = 0; }

    bool active() const { return mActive; }

    // Procesa el siguiente pulso del reloj. Llamar UNA vez por pulso y en orden.
    Step onBeat(std::uint32_t beatInBar, int beatsPerBar) {
        if (!mActive) return Step{false, 0};
        if (mBarsDone >= mBars) {          // el conteo ya cubrió todos sus compases: este pulso es de la grabación
            cancel();
            return Step{false, 0};
        }
        const Step step{true, mBarsDone};
        if (beatInBar + 1u >= static_cast<std::uint32_t>(beatsPerBar)) ++mBarsDone; // último pulso del compás
        return step;
    }

private:
    bool mActive   = false;
    int  mBars     = 0;
    int  mBarsDone = 0;
};

} // namespace eliner
