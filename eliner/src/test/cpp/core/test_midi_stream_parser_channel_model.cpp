// Fase 1.1 §17/§23 — réplica ejecutable (transliteración fiel) de
// MidiStreamParser.kt + del rastreo (canal,nota) de MidiToSynthBridge.kt.
// Mismos casos que MidiStreamParserTest.kt (tests de canal añadidos al final, para CI); esta
// versión sí se ejecuta en entornos sin kotlinc — mismo patrón que ADR 0016-0019.
//
//   g++ -std=c++20 -Wall -Wextra -Wpedantic -fsanitize=address,undefined
//       test_midi_stream_parser_channel_model.cpp -o /tmp/t && /tmp/t
#include <cstdio>
#include <optional>
#include <set>
#include <vector>

namespace {
int gFailures = 0;
void check(bool c, const char* w) {
    if (!c) { std::fprintf(stderr, "[FAIL] %s\n", w); ++gFailures; }
    else    { std::fprintf(stdout, "[ OK ] %s\n", w); }
}
}

enum class T { NOTE_ON, NOTE_OFF, POLY_AT, CHAN_AT, CC, PROGRAM, PITCH, SYSEX, CLOCK, START, STOP, CONT, SYS_OTHER };
struct Ev { T type; std::optional<int> channel; int d1 = 0, d2 = 0, bend = 8192; };

class Parser {
    int running = 0, pend[2] = {0, 0}, pendCount = 0, expected = 0;
    bool inSysex = false; std::vector<int> sysex;
    static std::optional<T> rt(int b) {
        switch (b) { case 0xF8: return T::CLOCK; case 0xFA: return T::START; case 0xFB: return T::CONT;
                     case 0xFC: return T::STOP; case 0xFE: case 0xFF: return T::SYS_OTHER; default: return std::nullopt; }
    }
    static int dataCount(int s) {
        switch (s & 0xF0) { case 0x80: case 0x90: case 0xA0: case 0xB0: case 0xE0: return 2;
                            case 0xC0: case 0xD0: return 1; default: return 0; }
    }
    void emit(std::vector<Ev>& out) {
        int ch = running & 0x0F; T t;
        switch (running & 0xF0) {
            case 0x80: t = T::NOTE_OFF; break;
            case 0x90: t = pend[1] == 0 ? T::NOTE_OFF : T::NOTE_ON; break;
            case 0xA0: t = T::POLY_AT; break; case 0xB0: t = T::CC; break;
            case 0xC0: t = T::PROGRAM; break; case 0xD0: t = T::CHAN_AT; break;
            case 0xE0: t = T::PITCH; break; default: return;
        }
        Ev e{t, ch};
        if (t == T::PITCH) e.bend = pend[0] | (pend[1] << 7);
        else { e.d1 = pend[0]; e.d2 = expected == 2 ? pend[1] : 0; }
        out.push_back(e);
    }
public:
    void feed(const std::vector<int>& bytes, std::vector<Ev>& out) {
        for (int b : bytes) {
            if (auto r = rt(b)) { out.push_back({*r, std::nullopt}); continue; }
            if (b == 0xF0) { inSysex = true; sysex.clear(); continue; }
            if (inSysex) {
                if (b == 0xF7) { inSysex = false; out.push_back({T::SYSEX, std::nullopt}); sysex.clear(); continue; }
                sysex.push_back(b); continue;
            }
            if (b >= 0x80) {
                if (b == 0xF1 || b == 0xF2 || b == 0xF3 || b == 0xF6) out.push_back({T::SYS_OTHER, std::nullopt});
                running = b; pendCount = 0; expected = dataCount(b);
                if (expected == 0) running = 0;
                continue;
            }
            if (running == 0) continue;
            pend[pendCount++] = b;
            if (pendCount == expected) { emit(out); pendCount = 0; }
        }
    }
};

static std::vector<Ev> parse(std::vector<int> bytes) { Parser p; std::vector<Ev> o; p.feed(bytes, o); return o; }

// Réplica del rastreo (canal,nota) de MidiToSynthBridge.
struct Tracker {
    std::set<int> keys;
    void set(int ch, int note, bool on) { int k = (ch << 7) | note; if (on) keys.insert(k); else keys.erase(k); }
    std::set<int> notes() const { std::set<int> n; for (int k : keys) n.insert(k & 0x7F); return n; }
};

int main() {
    std::fprintf(stdout, "=== test_midi_stream_parser_channel_model ===\n");

    { bool ok = true; for (int ch = 0; ch < 16; ++ch) { auto e = parse({0x90 | ch, 60, 100}); ok &= e.size() == 1 && e[0].type == T::NOTE_ON && e[0].channel == ch && e[0].d1 == 60 && e[0].d2 == 100; }
      check(ok, "NoteOn conserva los 16 canales (0-15, cero-basado)"); }
    { auto a = parse({0x83, 60, 0}); auto b = parse({0x95, 60, 0});
      check(a.size() == 1 && a[0].type == T::NOTE_OFF && a[0].channel == 3, "NoteOff 0x8n conserva canal");
      check(b.size() == 1 && b[0].type == T::NOTE_OFF && b[0].channel == 5, "NoteOn velocity 0 -> NOTE_OFF en su canal"); }
    { check(parse({0xB2, 7, 100})[0].channel == 2, "Control Change conserva canal");
      auto pc = parse({0xC4, 12}); check(pc[0].type == T::PROGRAM && pc[0].channel == 4 && pc[0].d1 == 12, "Program Change conserva canal");
      auto ca = parse({0xD6, 90}); check(ca[0].type == T::CHAN_AT && ca[0].channel == 6 && ca[0].d1 == 90, "Channel Aftertouch conserva canal");
      auto pa = parse({0xA7, 60, 50}); check(pa[0].type == T::POLY_AT && pa[0].channel == 7, "Poly Aftertouch conserva canal"); }
    { auto p = parse({0xE9, 0x00, 0x40}); check(p[0].type == T::PITCH && p[0].channel == 9 && p[0].bend == 8192, "Pitch Bend 14 bits centro=8192, canal 9");
      check(parse({0xE0, 0x7F, 0x7F})[0].bend == 16383, "Pitch Bend máximo 16383"); }
    { auto e = parse({0x93, 60, 100, 62, 100, 64, 100});
      check(e.size() == 3 && e[0].d1 == 60 && e[1].d1 == 62 && e[2].d1 == 64 && e[0].channel == 3 && e[1].channel == 3 && e[2].channel == 3,
            "Running status: 3 notas, todas en canal 3"); }
    { auto e = parse({0x90, 60, 100, 0x91, 62, 100, 64, 100});
      check(e.size() == 3 && e[0].channel == 0 && e[1].channel == 1 && e[2].channel == 1, "Nuevo status byte cambia el canal de running status"); }
    { auto e = parse({0x94, 60, 0xF8, 100});
      check(e.size() == 2 && e[0].type == T::CLOCK && !e[0].channel && e[1].type == T::NOTE_ON && e[1].channel == 4,
            "Real-time intercalado no altera el mensaje ni su canal"); }
    { check(!parse({0xF8})[0].channel, "Clock sin canal"); check(!parse({0xF0, 1, 2, 3, 0xF7})[0].channel, "SysEx sin canal"); }
    { auto e = parse({0x90, 60, 100, 0xF3, 5, 62, 100});
      check(e.size() == 2 && e[0].type == T::NOTE_ON && e[1].type == T::SYS_OTHER, "System Common cancela running status (62,100 no son una nota)"); }
    { check(parse({60, 100}).empty(), "Data bytes sueltos sin status se descartan"); }

    // Rastreo (canal,nota) del bridge: el bug que corrige §17.
    { Tracker t; t.set(0, 60, true); t.set(1, 60, true); t.set(1, 60, false);
      check(t.notes().count(60) == 1, "NoteOff ch1 NO apaga el resalte de la nota 60 que ch0 mantiene");
      t.set(0, 60, false);
      check(t.notes().empty(), "al soltar también ch0 la nota desaparece"); }
    { Tracker t; t.set(15, 127, true); t.set(0, 0, true);
      check(t.notes() == std::set<int>({0, 127}), "claves (canal shl 7 | nota) sin colisiones en los extremos"); }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
