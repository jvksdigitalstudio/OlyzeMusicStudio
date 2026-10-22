// TakeAlignment (ADR 0038): compensación de latencia, matemática pura.
#include <cmath>
#include <cstdio>
#include "TakeAlignment.h"
using namespace eliner;
namespace {
int gFailures = 0;
void check(bool c, const char* w) { if (!c) { std::fprintf(stderr, "[FAIL] %s\n", w); ++gFailures; } else std::fprintf(stdout, "[ OK ] %s\n", w); }
}
int main() {
    check(framesFromMillis(10.0, 48000) == 480, "10 ms a 48 kHz = 480 frames");
    check(framesFromMillis(12.5, 48000) == 600, "12,5 ms a 48 kHz = 600 frames");
    check(framesFromMillis(1.0, 44100) == 44, "1 ms a 44,1 kHz = 44 (redondeo al más cercano: 44,1)");
    check(framesFromMillis(0.5, 44100) == 22, "0,5 ms a 44,1 kHz = 22 (22,05)");
    check(framesFromMillis(0.0, 48000) == 0 && framesFromMillis(-3.0, 48000) == 0, "cero y negativos: 0");
    check(framesFromMillis(NAN, 48000) == 0 && framesFromMillis(INFINITY, 48000) == 0, "no finitos: 0 (sin inventar valor)");
    check(framesFromMillis(10.0, 0) == 0 && framesFromMillis(10.0, -1) == 0, "frecuencia inválida: 0");
    check(trimFramesFor(12.5, 7.5, 48000) == 600 + 360, "round trip = salida + entrada (960 frames a 48 kHz)");
    check(trimFramesFor(12.5, -1.0, 48000) == 600, "entrada sin medir aporta 0, no un valor inventado");
    check(trimFramesFor(-1.0, -1.0, 48000) == 0, "ninguna medida: sin recorte");
    check(trimFramesFor(500.0, 500.0, 48000, 9600) == 9600, "se limita al máximo pedido (una medida absurda no se come la toma)");
    check(trimFramesFor(10.0, 10.0, 48000, 0) == 960, "máximo 0 = sin límite");
    std::fprintf(stdout, "\n%s (%d fallo(s))\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
