// Fase 1.1 (ADR 0025) — réplica ejecutable, en C++ puro, del manejo de
// SysEx de MidiStreamParser.kt: demuestra el bug real (la condición de
// emisión era una tautología por culpa del tope de acumulación) y verifica
// el fix (sysexOverflowed, una señal separada del tamaño del buffer).
//
//   g++ -std=c++20 -Wall -Wextra -Wpedantic -fsanitize=address,undefined
//       test_midi_sysex_overflow_model.cpp -o /tmp/t && /tmp/t
#include <cstdio>
#include <vector>

namespace {
int gFailures = 0;
void check(bool c, const char* w) {
    if (!c) { std::fprintf(stderr, "[FAIL] %s\n", w); ++gFailures; }
    else    { std::fprintf(stdout, "[ OK ] %s\n", w); }
}
constexpr int kMax = 8; // MAX_SYSEX_BYTES real = 4096; aquí pequeño a propósito para poder listar los casos
}

// Réplica EXACTA del código viejo (buggy) — tal como estaba antes de este ADR.
struct OldSysexHandler {
    bool inSysex = false;
    std::vector<int> buf;
    bool sawEnd = false; // si emitió (aunque sea truncado)
    int emittedSize = -1;

    void feed(int b) {
        if (b == 0xF0) { inSysex = true; buf.clear(); return; }
        if (inSysex) {
            if (b == 0xF7) {
                inSysex = false;
                if ((int)buf.size() <= kMax) { // la tautología: buf.size() NUNCA puede superar kMax, ver abajo
                    sawEnd = true;
                    emittedSize = (int)buf.size();
                }
                buf.clear();
                return;
            }
            if ((int)buf.size() < kMax) buf.push_back(b);
            return;
        }
    }
};

// Réplica EXACTA del código nuevo (corregido) — sysexOverflowed separado del tamaño.
struct NewSysexHandler {
    bool inSysex = false;
    bool overflowed = false;
    std::vector<int> buf;
    bool sawEnd = false;
    int emittedSize = -1;

    void feed(int b) {
        if (b == 0xF0) { inSysex = true; buf.clear(); overflowed = false; return; }
        if (inSysex) {
            if (b == 0xF7) {
                inSysex = false;
                if (!overflowed) {
                    sawEnd = true;
                    emittedSize = (int)buf.size();
                }
                buf.clear();
                overflowed = false;
                return;
            }
            if ((int)buf.size() < kMax) buf.push_back(b);
            else overflowed = true;
            return;
        }
    }
};

int main() {
    std::fprintf(stdout, "=== test_midi_sysex_overflow_model ===\n");

    // Caso 1: sysex normal, dentro del límite — ambos deben emitir completo.
    {
        OldSysexHandler o; NewSysexHandler n;
        std::vector<int> msg = {0xF0, 1, 2, 3, 0xF7}; // 3 bytes de payload, kMax=8
        for (int b : msg) { o.feed(b); n.feed(b); }
        check(o.sawEnd && o.emittedSize == 3, "Caso 1 (control): comportamiento viejo, sysex normal, se emite completo (3 bytes)");
        check(n.sawEnd && n.emittedSize == 3, "Caso 1: comportamiento nuevo, sysex normal, se emite completo (3 bytes)");
    }

    // Caso 2 (EL BUG): sysex sobredimensionado (más de kMax bytes de payload).
    {
        OldSysexHandler o; NewSysexHandler n;
        std::vector<int> msg = {0xF0};
        for (int i = 0; i < kMax + 5; ++i) msg.push_back(1); // kMax+5 bytes de payload, excede el límite
        msg.push_back(0xF7);
        for (int b : msg) { o.feed(b); n.feed(b); }

        check(o.sawEnd && o.emittedSize == kMax,
              "Caso 2 [BUG CONFIRMADO]: el código VIEJO emite igual, TRUNCADO a kMax bytes, "
              "en vez de descartar el mensaje entero - la tautologia en accion");
        check(!n.sawEnd,
              "Caso 2 [FIX VERIFICADO]: el código NUEVO NO emite nada - el sysex sobredimensionado "
              "se descarta entero, tal como documenta el comentario original (que el codigo viejo no cumplia)");
    }

    // Caso 3: exactamente en el límite (kMax bytes de payload) — debe emitirse completo, sin overflow.
    {
        OldSysexHandler o; NewSysexHandler n;
        std::vector<int> msg = {0xF0};
        for (int i = 0; i < kMax; ++i) msg.push_back(2);
        msg.push_back(0xF7);
        for (int b : msg) { o.feed(b); n.feed(b); }
        check(n.sawEnd && n.emittedSize == kMax, "Caso 3: payload EXACTAMENTE en el limite se emite completo (no es overflow por un byte)");
    }

    // Caso 4: un byte de más (kMax+1) — el caso límite exacto del bug.
    {
        OldSysexHandler o; NewSysexHandler n;
        std::vector<int> msg = {0xF0};
        for (int i = 0; i < kMax + 1; ++i) msg.push_back(3);
        msg.push_back(0xF7);
        for (int b : msg) { o.feed(b); n.feed(b); }
        check(o.sawEnd, "Caso 4 [BUG]: kMax+1 bytes - el codigo viejo IGUAL emite (truncado a kMax)");
        check(!n.sawEnd, "Caso 4 [FIX]: kMax+1 bytes - el codigo nuevo lo descarta entero");
    }

    // Caso 5: dos sysex consecutivos, el primero sobredimensionado, el segundo normal —
    // el estado de overflow no debe filtrarse al segundo mensaje.
    {
        NewSysexHandler n;
        std::vector<int> big = {0xF0};
        for (int i = 0; i < kMax + 3; ++i) big.push_back(9);
        big.push_back(0xF7);
        std::vector<int> normal = {0xF0, 5, 6, 0xF7};
        for (int b : big) n.feed(b);
        check(!n.sawEnd, "Caso 5.1: primer sysex (sobredimensionado) descartado");
        n.sawEnd = false; // reset para medir solo el segundo
        for (int b : normal) n.feed(b);
        check(n.sawEnd && n.emittedSize == 2, "Caso 5.2: el overflow del primer mensaje NO se filtra al segundo - se emite normal");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
