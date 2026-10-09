// Fase 1.1 §17 — enrutamiento de eventos MIDI por canal, probado contra el
// CÓDIGO DE PRODUCCIÓN: eliner::VoicePool (src/main/cpp/dsp/VoicePool.cpp) y
// eliner::SynthVoice se compilan tal cual del árbol.
//
// Historia: este test (antes `test_midi_channel_voice_routing`) probaba una
// RÉPLICA del `switch` de AudioEngine::applyCommand, porque AudioEngine incluye
// Oboe. Una réplica puede divergir del código real sin que nada lo detecte.
// Al extraer la polifonía a VoicePool (sin dependencias de Oboe) la réplica
// sobra: el test ejercita la clase real.
//
// ALCANCE (declarado): la lógica de voces. La guarda de canal de la ENTRADA de
// control (AudioEngine::noteOn…) y el reenvío por la cola SPSC las cubre
// test_engine_transport_integration; aquí se prueba además la defensa en
// profundidad de VoicePool contra un canal fuera de rango.
#include <array>
#include <cmath>
#include <cstdio>
#include <type_traits>

#include "VoicePool.h"
#include "eliner/core/CommandQueue.h"

using namespace eliner;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}

constexpr int kSR = 48000;

struct Rig {
    VoicePool pool;
    Rig() { pool.build(kSR); }

    // Una voz en fase release sigue activa un rato: se renderiza ~4 s para que
    // un NoteOff surta efecto observable.
    void settle() {
        std::array<float, 2 * 512> buf{};
        for (int i = 0; i < 400; ++i) { buf.fill(0.0f); pool.render(buf.data(), 512); }
    }
    int total() const {
        int n = 0;
        for (int c = 0; c < 16; ++c) for (int note = 0; note < 128; ++note) n += pool.countActive(c, note);
        return n;
    }
    std::array<float, 2 * 256> block() {
        std::array<float, 2 * 256> b{};
        pool.render(b.data(), 256);
        return b;
    }
};

bool identical(const std::array<float, 2 * 256>& a, const std::array<float, 2 * 256>& b, float tol = 1e-6f) {
    for (size_t i = 0; i < a.size(); ++i) if (std::fabs(a[i] - b[i]) > tol) return false;
    return true;
}
} // namespace

int main() {
    std::fprintf(stdout, "=== test_voice_pool ===\n");

    // 0. EngineCommand: el canal viaja en el struct y por defecto es -1.
    {
        EngineCommand def{};
        check(def.channel == -1, "EngineCommand.channel por defecto = -1 (nunca parece canal 0 válido)");
        EngineCommand c{EngineCommandType::NoteOn, 60, 100, 0.0f, 5};
        check(c.channel == 5 && c.intA == 60 && c.intB == 100, "EngineCommand transporta el canal junto a nota/velocity");
        static_assert(std::is_trivially_copyable_v<EngineCommand>, "EngineCommand debe seguir siendo POD para el ring buffer");
    }

    // 1. NoteOn conserva el canal en la voz.
    {
        Rig r;
        r.pool.noteOn(3, 60, 100);
        check(r.pool.countActive(3, 60) == 1, "NoteOn ch3 nota60: la voz recuerda canal 3");
        check(r.pool.countActive(0, 60) == 0, "NoteOn ch3: ninguna voz en canal 0");
    }

    // 2. EL BUG: NoteOff de OTRO canal no debe silenciar la nota.
    {
        Rig r;
        r.pool.noteOn(0, 60, 100);  // teclado en pantalla, canal 0
        r.pool.noteOff(1, 60);      // controlador externo, canal 1, MISMA nota
        r.settle();
        check(r.pool.countActive(0, 60) == 1, "NoteOff ch1 nota60 NO silencia la nota60 sonando en ch0");
        r.pool.noteOff(0, 60);
        r.settle();
        check(r.pool.countActive(0, 60) == 0, "NoteOff ch0 nota60 sí la silencia (tras el release)");
    }

    // 3. Mismo número de nota en dos canales = dos voces independientes.
    {
        Rig r;
        r.pool.noteOn(0, 64, 100);
        r.pool.noteOn(9, 64, 100);
        check(r.pool.countActive(0, 64) == 1 && r.pool.countActive(9, 64) == 1, "misma nota en ch0 y ch9: 2 voces");
        r.pool.noteOff(9, 64);
        r.settle();
        check(r.pool.countActive(9, 64) == 0 && r.pool.countActive(0, 64) == 1,
              "NoteOff ch9 solo libera la voz de ch9; la de ch0 sigue");
    }

    // 4. Pitch bend por canal: solo afecta a voces de ese canal (verificación audible real).
    {
        // Determinista: la voz de ch0 con y sin bend en OTRO canal (ch1) suena idéntica.
        Rig a, b;
        a.pool.noteOn(0, 60, 100); b.pool.noteOn(0, 60, 100);
        a.pool.setPitchBend(1, 2.0f);   // bend en OTRO canal, sin voces ahí
        check(identical(a.block(), b.block()), "el bend de ch1 no altera la voz de ch0 (idéntica a una sin bend)");
        Rig c, d;
        c.pool.noteOn(1, 60, 100); d.pool.noteOn(1, 60, 100);
        c.pool.setPitchBend(1, 2.0f);
        check(!identical(c.block(), d.block(), 1e-3f), "…pero sí altera la voz de ch1 (el bend es audible)");
    }

    // 5. Una voz reciclada NO hereda el bend del canal anterior.
    {
        Rig r, ref;
        r.pool.noteOn(2, 60, 100);
        r.pool.setPitchBend(2, 2.0f);
        r.pool.allNotesOff();            // libera la voz (kill)
        r.pool.noteOn(5, 60, 100);       // canal distinto, puede reutilizar la misma voz
        ref.pool.noteOn(5, 60, 100);     // referencia: ch5 sin bend
        check(identical(r.block(), ref.block()), "voz reciclada: el bend de ch2 NO se filtra a la nota nueva de ch5");
    }

    // 6. Una nota nueva en un canal con bend vigente lo hereda.
    {
        Rig r, ref, plain;
        r.pool.setPitchBend(4, 1.5f);    // bend ANTES de la nota
        r.pool.noteOn(4, 62, 100);
        ref.pool.setPitchBend(4, 1.5f);
        ref.pool.noteOn(4, 62, 100);
        plain.pool.noteOn(4, 62, 100);
        const auto a = r.block(), b = ref.block(), unbent = plain.block();
        check(identical(a, b), "nota nueva en ch4 hereda el bend vigente (determinista)");
        check(!identical(a, unbent, 1e-3f), "y ese bend realmente cambia el sonido respecto a sin bend");
    }

    // 7. Canales inválidos: descartados sin efecto (defensa en profundidad, sin fuera de rango).
    {
        Rig r;
        r.pool.noteOn(-1, 60, 100);
        r.pool.noteOn(16, 60, 100);   // no se recorta a otro canal
        r.pool.noteOn(200, 60, 100);
        r.pool.noteOff(99, 60);
        r.pool.setPitchBend(-5, 1.0f);
        r.pool.setPitchBend(16, 1.0f);
        check(r.total() == 0, "canales -1/16/200 no producen voz y no corrompen memoria (ASan/UBSan)");
        r.pool.noteOn(0, 60, 100);
        check(r.pool.countActive(0, 60) == 1, "tras entradas inválidas el pool sigue operativo");
    }

    // 8. AllNotesOff sigue siendo global (todos los canales).
    {
        Rig r;
        r.pool.noteOn(0, 60, 100); r.pool.noteOn(7, 61, 100); r.pool.noteOn(15, 62, 100);
        r.pool.allNotesOff();
        check(r.total() == 0, "AllNotesOff silencia todos los canales (pánico global, por diseño)");
    }

    // 9. Los 16 canales (rango completo, cero-basado) y robo de voz al agotar la polifonía.
    {
        Rig r;
        for (int c = 0; c < 16; ++c) r.pool.noteOn(c, 60 + (c % 4), 90);
        int voices = 0;
        for (int c = 0; c < 16; ++c) voices += r.pool.countActive(c, 60 + (c % 4));
        check(voices == 16, "16 canales simultáneos: 16 voces");

        Rig steal;
        for (int i = 0; i < kMaxVoices + 8; ++i) steal.pool.noteOn(i % 16, 30 + i, 90);
        check(steal.total() == kMaxVoices,
              "con más NoteOn que voces (kMaxVoices) se roban las más antiguas, sin crash ni exceder el máximo");
    }

    // 10. render() informa cuántas voces estaban activas en el bloque.
    {
        Rig r;
        std::array<float, 2 * 128> buf{};
        check(r.pool.render(buf.data(), 128) == 0, "pool silencioso: render() devuelve 0 voces activas");
        r.pool.noteOn(0, 60, 100); r.pool.noteOn(1, 64, 100); r.pool.noteOn(2, 67, 100);
        check(r.pool.render(buf.data(), 128) == 3, "tres notas: render() devuelve 3 voces activas");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
