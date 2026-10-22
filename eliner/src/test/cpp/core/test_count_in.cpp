// CountIn + ClickPolicy + formato del pulso (ADR 0035) — unidades PURAS, sin reloj ni audio.
//
// Qué fija este test:
//   · CountIn cuenta COMPASES completos, no pulsos: termina siempre en una barra de compás aunque el compás
//     cambie mientras suena (si contara pulsos, la grabación empezaría a mitad de compás).
//   · El pulso que sigue al último del conteo NO pertenece al conteo.
//   · ClickPolicy: tabla completa (modo × fase × grabación armada). El conteo siempre suena (Force).
//   · Validación de duraciones y modos (los valores fuera de rango se ignoran, no se recortan).
//   · El empaquetado del pulso con los campos nuevos es compatible con el anterior al ADR 0035.
#include <cstdint>
#include <cstdio>
#include <initializer_list>

#include "BeatPulse.h"
#include "ClickPolicy.h"
#include "CountIn.h"

using namespace eliner;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}

// Alimenta [n] pulsos consecutivos de un compás de [bpb] tiempos y devuelve cuántos fueron de conteo.
int countBeats(CountIn& c, int bpb, int n, std::uint32_t startBeat = 0) {
    int counted = 0;
    std::uint32_t beat = startBeat;
    for (int i = 0; i < n; ++i) {
        if (c.onBeat(beat, bpb).countIn) ++counted;
        beat = (beat + 1) % static_cast<std::uint32_t>(bpb);
    }
    return counted;
}
} // namespace

int main() {
    // ── Validación de duraciones ──
    check(isValidCountInBars(0) && isValidCountInBars(1) && isValidCountInBars(2) && isValidCountInBars(4),
          "duraciones válidas: 0, 1, 2 y 4 compases");
    check(!isValidCountInBars(3) && !isValidCountInBars(-1) && !isValidCountInBars(5) && !isValidCountInBars(8),
          "3, negativos y >4 NO son duraciones válidas (se ignoran, no se recortan)");

    // ── Sin armar: nunca hay conteo ──
    {
        CountIn c;
        check(!c.active(), "recién creado: inactivo");
        check(!c.onBeat(0, 4).countIn, "inactivo: el pulso no es de conteo");
        c.arm(0);  check(!c.active(), "arm(0) no arma nada");
        c.arm(5);  check(!c.active(), "arm(5) (fuera de rango) no arma nada");
        c.arm(-2); check(!c.active(), "arm(-2) no arma nada");
    }

    // ── 4/4: N compases = 4N pulsos de conteo; el siguiente ya es de grabación ──
    for (int bars : {1, 2, 4}) {
        CountIn c; c.arm(bars);
        const int expected = bars * 4;
        char msg[96];
        std::snprintf(msg, sizeof msg, "4/4, %d compás(es): %d pulsos de conteo y el resto no", bars, expected);
        const int counted = countBeats(c, 4, expected + 8);
        check(counted == expected, msg);
        check(!c.active(), "tras el conteo la máquina queda inactiva");
    }

    // ── El pulso que sigue al conteo es el tiempo 1 y NO es de conteo ──
    {
        CountIn c; c.arm(1);
        for (std::uint32_t b = 0; b < 4; ++b) (void)c.onBeat(b, 4);
        const auto first = c.onBeat(0, 4);
        check(!first.countIn, "el primer pulso tras el conteo es de la grabación");
        check(!c.onBeat(1, 4).countIn, "y los siguientes también");
    }

    // ── Índice de compás del conteo ──
    {
        CountIn c; c.arm(2);
        bool ok = true;
        for (int i = 0; i < 8; ++i) {
            const auto s = c.onBeat(static_cast<std::uint32_t>(i % 4), 4);
            if (!s.countIn || s.barIndex != i / 4) ok = false;
        }
        check(ok, "barIndex = 0 en los 4 primeros pulsos y 1 en los 4 siguientes");
    }

    // ── Compases de 1 tiempo: cada pulso cierra un compás ──
    {
        CountIn c; c.arm(4);
        check(countBeats(c, 1, 10) == 4, "compás de 1 tiempo: 4 compases = 4 pulsos");
    }

    // ── Compás de 16 tiempos ──
    {
        CountIn c; c.arm(2);
        check(countBeats(c, 16, 40) == 32, "compás de 16 tiempos: 2 compases = 32 pulsos");
    }

    // ── Cambio de compás a mitad: termina en una barra de compás ──
    {
        CountIn c; c.arm(2);
        // 4/4: tiempos 0..3 (compás 0 completo) y 0,1 del segundo; entonces el compás pasa a 3/4.
        int counted = 0;
        for (std::uint32_t b = 0; b < 4; ++b) counted += c.onBeat(b, 4).countIn ? 1 : 0;
        counted += c.onBeat(0, 4).countIn ? 1 : 0;
        counted += c.onBeat(1, 4).countIn ? 1 : 0;
        // Ahora 3/4: el tiempo 2 es el último del compás vigente y lo cierra.
        const auto s2 = c.onBeat(2, 3);
        check(s2.countIn && s2.barIndex == 1, "el pulso que cierra el 2.º compás (ya en 3/4) sigue siendo de conteo");
        counted += s2.countIn ? 1 : 0;
        const auto after = c.onBeat(0, 3);
        check(!after.countIn, "el siguiente pulso es el tiempo 1 de la grabación (alineado con la barra)");
        check(counted == 7, "4 + 2 + 1 pulsos de conteo: el compás cambiado cuenta como completo al cerrarse");
    }

    // ── cancel() ──
    {
        CountIn c; c.arm(2);
        (void)c.onBeat(0, 4); (void)c.onBeat(1, 4);
        c.cancel();
        check(!c.active() && !c.onBeat(2, 4).countIn, "cancel() detiene el conteo y es idempotente");
        c.cancel();
        c.arm(1);
        check(countBeats(c, 4, 6) == 4, "tras cancelar se puede armar de nuevo desde cero");
    }

    // ── Re-armar reinicia el contador ──
    {
        CountIn c; c.arm(2);
        (void)countBeats(c, 4, 5);
        c.arm(1);
        check(countBeats(c, 4, 6) == 4, "arm() sobre un conteo en curso lo reinicia");
    }

    // ── ClickPolicy: tabla completa ──
    {
        using G = ClickGate; using M = ClickMode; using P = TransportPhase;
        for (bool armed : {false, true}) {
            for (M m : {M::Always, M::RecordOnly, M::CountInOnly}) {
                check(gateFor(m, P::CountIn, armed) == G::Force, "conteo: SIEMPRE Force (independiente del modo y del botón)");
                check(gateFor(m, P::Stopped, armed) == G::Silence, "parado: Silence");
            }
        }
        check(gateFor(M::Always, P::Running, false) == G::Follow, "Always reproduciendo: Follow");
        check(gateFor(M::Always, P::Running, true)  == G::Follow, "Always grabando: Follow");
        check(gateFor(M::RecordOnly, P::Running, false) == G::Silence, "RecordOnly sin grabación armada: Silence");
        check(gateFor(M::RecordOnly, P::Running, true)  == G::Follow,  "RecordOnly con grabación armada: Follow");
        check(gateFor(M::CountInOnly, P::Running, false) == G::Silence, "CountInOnly reproduciendo: Silence");
        check(gateFor(M::CountInOnly, P::Running, true)  == G::Silence, "CountInOnly grabando: Silence (solo suena el conteo)");
        static_assert(gateFor(ClickMode::Always, TransportPhase::Running, false) == ClickGate::Follow,
                      "gateFor es constexpr");
    }

    // ── Validación de modos ──
    check(isValidClickMode(0) && isValidClickMode(1) && isValidClickMode(2), "modos 0..2 válidos");
    check(!isValidClickMode(3) && !isValidClickMode(-1) && !isValidClickMode(99), "3, -1 y 99 no son modos (el centinela Count no lo es)");

    // ── Formato del pulso ──
    {
        constexpr std::uint64_t legacy = pulse::pack(1, 15, true);
        static_assert(legacy == ((1ull << 16) | (1ull << 8) | 15ull),
                      "pack(seq, beat, running) con los campos nuevos por defecto es idéntico al formato anterior");
        check(!pulse::countInOf(legacy) && pulse::countInBarOf(legacy) == 0, "sin conteo: bits de conteo a 0");

        const std::uint64_t p = pulse::pack(5, 2, true, true, 3);
        check(pulse::sequenceOf(p) == 5 && pulse::beatInBarOf(p) == 2 && pulse::runningOf(p),
              "los campos antiguos no se alteran al añadir el conteo");
        check(pulse::countInOf(p) && pulse::countInBarOf(p) == 3, "conteo y compás (3) se empaquetan y se leen");
        check(pulse::countInBarOf(pulse::pack(1, 0, true, true, 7)) == 3, "el compás se enmascara a 2 bits (7 → 3)");
        check(pulse::countInBarOf(pulse::pack(1, 0, true, false, 3)) == 0, "con countIn=false el compás se ignora");
        const std::uint64_t big = pulse::pack((1ull << 47) - 1, 15, true, true, 3);
        check((big >> 63) == 0, "con la secuencia máxima el bit 63 sigue siendo 0 (cabe en un jlong positivo)");
        check(pulse::sequenceOf(big) == (1ull << 47) - 1, "la secuencia máxima no se contamina con los bits nuevos");
    }

    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
