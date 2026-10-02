// Fase 1.1 §17 — enrutamiento de eventos MIDI por canal en el motor nativo.
//
// USA CÓDIGO REAL DE PRODUCCIÓN: eliner::SynthVoice (src/main/cpp/dsp/
// SynthVoice.cpp) y eliner::EngineCommand (include/eliner/core/
// CommandQueue.h) se compilan tal cual del árbol. Lo único replicado es el
// `switch` de AudioEngine::applyCommand (casos NoteOn/NoteOff/PitchBend) y
// las guardas de AudioEngine::noteOn/noteOff/setPitchBend, porque
// AudioEngine.cpp incluye headers de Oboe no disponibles en este entorno.
// Cualquier cambio en esas ramas de AudioEngine.cpp debe reflejarse aquí.
//
// Compilación (desde eliner/):
//   g++ -std=c++20 -Wall -Wextra -Wpedantic -fsanitize=address,undefined
//       -Iinclude/eliner/dsp -Iinclude
//       src/test/cpp/core/test_midi_channel_voice_routing.cpp
//       src/main/cpp/dsp/SynthVoice.cpp -o /tmp/t_chan
#include <array>
#include <cmath>
#include <cstdio>
#include <memory>

#include "SynthVoice.h"
#include "eliner/core/CommandQueue.h"

using namespace eliner;

namespace {
int gFailures = 0;
void check(bool cond, const char* what) {
    if (!cond) { std::fprintf(stderr, "[FAIL] %s\n", what); ++gFailures; }
    else       { std::fprintf(stdout, "[ OK ] %s\n", what); }
}
}

// Réplica de la lógica de AudioEngine (control-thread guards + audio-thread apply).
struct EngineRoutingModel {
    static constexpr int kMaxVoices = 8;
    std::array<std::unique_ptr<SynthVoice>, kMaxVoices> mVoices;
    std::array<float, 16> mChannelPitchBend{};

    EngineRoutingModel() {
        for (auto& v : mVoices) v = std::make_unique<SynthVoice>(48000);
    }

    static bool isValidMidiChannel(int c) { return c >= 0 && c <= 15; }

    // Devuelve false si la guarda de control lo descartó (no se encola).
    bool noteOn(int channel, int note, int velocity) {
        if (!isValidMidiChannel(channel)) return false;
        applyCommand({EngineCommandType::NoteOn, note, velocity, 0.0f, channel});
        return true;
    }
    bool noteOff(int channel, int note) {
        if (!isValidMidiChannel(channel)) return false;
        applyCommand({EngineCommandType::NoteOff, note, 0, 0.0f, channel});
        return true;
    }
    bool setPitchBend(int channel, float st) {
        if (!isValidMidiChannel(channel)) return false;
        applyCommand({EngineCommandType::PitchBend, 0, 0, st, channel});
        return true;
    }
    void allNotesOff() { applyCommand({EngineCommandType::AllNotesOff, 0, 0, 0.0f}); }

    void applyCommand(const EngineCommand& cmd) {
        switch (cmd.type) {
            case EngineCommandType::NoteOn: {
                if (cmd.channel < 0 || cmd.channel > 15) break;
                SynthVoice* target = nullptr;
                SynthVoice* oldest = nullptr;
                uint64_t minAge = UINT64_MAX;
                for (auto& v : mVoices) {
                    if (!v->isActive()) { target = v.get(); break; }
                    if (v->age() < minAge) { minAge = v->age(); oldest = v.get(); }
                }
                if (!target) target = oldest;
                if (target) {
                    target->setPitchBend(mChannelPitchBend[cmd.channel]);
                    target->noteOn(cmd.intA, cmd.intB / 127.0f, cmd.channel);
                }
                break;
            }
            case EngineCommandType::NoteOff:
                for (auto& v : mVoices)
                    if (v->isActive() && v->note() == cmd.intA && v->channel() == cmd.channel) v->noteOff();
                break;
            case EngineCommandType::AllNotesOff:
                for (auto& v : mVoices) v->kill();
                break;
            case EngineCommandType::PitchBend:
                if (cmd.channel < 0 || cmd.channel > 15) break;
                mChannelPitchBend[cmd.channel] = cmd.floatA;
                for (auto& v : mVoices)
                    if (v->isActive() && v->channel() == cmd.channel) v->setPitchBend(cmd.floatA);
                break;
            default: break;
        }
    }

    // Una voz en fase release sigue "isActive" un rato: para saber si un
    // NoteOff surtió efecto se usa el envelope vía render (energía en release
    // decae) — más simple: contamos voces cuyo (canal,nota) coincide y siguen activas
    // DESPUÉS de renderizar suficiente release.
    void settle() {
        std::array<float, 2 * 512> buf{};
        for (int i = 0; i < 400; ++i) { // ~4 s de release a 48 kHz
            buf.fill(0.0f);
            for (auto& v : mVoices) v->render(buf.data(), 512);
        }
    }
    int activeCount(int channel, int note) const {
        int n = 0;
        for (auto& v : mVoices)
            if (v->isActive() && v->channel() == channel && v->note() == note) ++n;
        return n;
    }
    const SynthVoice* findVoice(int channel, int note) const {
        for (auto& v : mVoices)
            if (v->isActive() && v->channel() == channel && v->note() == note) return v.get();
        return nullptr;
    }
};

int main() {
    std::fprintf(stdout, "=== test_midi_channel_voice_routing ===\n");

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
        EngineRoutingModel e;
        e.noteOn(3, 60, 100);
        check(e.activeCount(3, 60) == 1, "NoteOn ch3 nota60: la voz recuerda canal 3");
        check(e.activeCount(0, 60) == 0, "NoteOn ch3: ninguna voz en canal 0");
    }

    // 2. EL BUG: NoteOff de OTRO canal no debe silenciar la nota.
    {
        EngineRoutingModel e;
        e.noteOn(0, 60, 100);  // teclado en pantalla, canal 0
        e.noteOff(1, 60);      // controlador externo, canal 1, MISMA nota
        e.settle();
        check(e.activeCount(0, 60) == 1,
              "[BUG PRE-FIX] NoteOff ch1 nota60 NO silencia la nota60 sonando en ch0");
        e.noteOff(0, 60);
        e.settle();
        check(e.activeCount(0, 60) == 0, "NoteOff ch0 nota60 sí la silencia (tras el release)");
    }

    // 3. Mismo número de nota en dos canales = dos voces independientes.
    {
        EngineRoutingModel e;
        e.noteOn(0, 64, 100);
        e.noteOn(9, 64, 100);
        check(e.activeCount(0, 64) == 1 && e.activeCount(9, 64) == 1, "misma nota en ch0 y ch9: 2 voces");
        e.noteOff(9, 64);
        e.settle();
        check(e.activeCount(9, 64) == 0 && e.activeCount(0, 64) == 1,
              "NoteOff ch9 solo libera la voz de ch9; la de ch0 sigue");
    }

    // 4. Pitch bend por canal: solo afecta a voces de ese canal.
    {
        EngineRoutingModel e;
        e.noteOn(0, 60, 100);
        e.noteOn(1, 60, 100);
        e.setPitchBend(1, 2.0f);
        check(e.mChannelPitchBend[1] == 2.0f && e.mChannelPitchBend[0] == 0.0f,
              "[BUG PRE-FIX] el bend de ch1 no se registra en ch0");
        // Verificación audible real: render de la voz ch0 vs una voz de referencia sin bend.
        EngineRoutingModel ref;
        ref.noteOn(0, 60, 100);
        std::array<float, 2 * 256> a{}, b{};
        e.findVoice(0, 60) ? (void)0 : (void)0;
        const_cast<SynthVoice*>(e.findVoice(0, 60))->render(a.data(), 256);
        const_cast<SynthVoice*>(ref.findVoice(0, 60))->render(b.data(), 256);
        bool identical = true;
        for (size_t i = 0; i < a.size(); ++i) if (std::fabs(a[i] - b[i]) > 1e-6f) { identical = false; break; }
        check(identical, "la voz de ch0 suena idéntica a una sin bend: el bend de ch1 no la afectó");
    }

    // 5. Una voz reciclada NO hereda el bend del canal anterior.
    {
        EngineRoutingModel e;
        e.noteOn(2, 60, 100);
        e.setPitchBend(2, 2.0f);
        e.allNotesOff();               // libera la voz (kill)
        e.noteOn(5, 60, 100);          // canal distinto, puede reutilizar la misma voz
        EngineRoutingModel ref;
        ref.noteOn(5, 60, 100);        // referencia: ch5 sin bend
        std::array<float, 2 * 256> a{}, b{};
        const_cast<SynthVoice*>(e.findVoice(5, 60))->render(a.data(), 256);
        const_cast<SynthVoice*>(ref.findVoice(5, 60))->render(b.data(), 256);
        bool identical = true;
        for (size_t i = 0; i < a.size(); ++i) if (std::fabs(a[i] - b[i]) > 1e-6f) { identical = false; break; }
        check(identical, "voz reciclada: el bend de ch2 NO se filtra a la nota nueva de ch5");
    }

    // 6. Una nota nueva en un canal con bend vigente lo hereda.
    {
        EngineRoutingModel e;
        e.setPitchBend(4, 1.5f);       // bend ANTES de la nota
        e.noteOn(4, 62, 100);
        EngineRoutingModel ref;
        ref.setPitchBend(4, 1.5f);
        ref.noteOn(4, 62, 100);
        std::array<float, 2 * 256> a{}, b{}, unbent{};
        EngineRoutingModel plain; plain.noteOn(4, 62, 100);
        const_cast<SynthVoice*>(e.findVoice(4, 62))->render(a.data(), 256);
        const_cast<SynthVoice*>(ref.findVoice(4, 62))->render(b.data(), 256);
        const_cast<SynthVoice*>(plain.findVoice(4, 62))->render(unbent.data(), 256);
        bool same = true, differs = false;
        for (size_t i = 0; i < a.size(); ++i) {
            if (std::fabs(a[i] - b[i]) > 1e-6f) same = false;
            if (std::fabs(a[i] - unbent[i]) > 1e-3f) differs = true;
        }
        check(same, "nota nueva en ch4 hereda el bend vigente (determinista)");
        check(differs, "y ese bend realmente cambia el sonido respecto a sin bend");
    }

    // 7. Canales inválidos: descartados sin efecto.
    {
        EngineRoutingModel e;
        check(!e.noteOn(-1, 60, 100), "canal -1 rechazado");
        check(!e.noteOn(16, 60, 100), "canal 16 rechazado (no se recorta a otro canal)");
        check(!e.noteOff(99, 60), "noteOff canal 99 rechazado");
        check(!e.setPitchBend(-5, 1.0f), "pitch bend canal -5 rechazado");
        int total = 0;
        for (int c = 0; c < 16; ++c) for (int n = 0; n < 128; ++n) total += e.activeCount(c, n);
        check(total == 0, "ningún canal inválido produjo una voz");
        // Defensa en profundidad en el hilo de audio: comando corrupto directo.
        e.applyCommand({EngineCommandType::NoteOn, 60, 100, 0.0f, 200});
        e.applyCommand({EngineCommandType::PitchBend, 0, 0, 1.0f, -1});
        total = 0;
        for (int c = 0; c < 16; ++c) for (int n = 0; n < 128; ++n) total += e.activeCount(c, n);
        check(total == 0, "comando corrupto (canal fuera de rango) ignorado en el hilo de audio, sin OOB");
    }

    // 8. AllNotesOff sigue siendo global (todos los canales).
    {
        EngineRoutingModel e;
        e.noteOn(0, 60, 100); e.noteOn(7, 61, 100); e.noteOn(15, 62, 100);
        e.allNotesOff();
        int total = 0;
        for (int c = 0; c < 16; ++c) for (int n = 0; n < 128; ++n) total += e.activeCount(c, n);
        check(total == 0, "AllNotesOff silencia todos los canales (pánico global, por diseño)");
    }

    // 9. Los 16 canales (rango completo, cero-basado).
    {
        EngineRoutingModel e;
        for (int c = 0; c < 16; ++c) e.noteOn(c, 60 + (c % 4), 90);
        int voices = 0;
        for (int c = 0; c < 16; ++c) voices += e.activeCount(c, 60 + (c % 4));
        check(voices == 8, "8 voces disponibles: con 16 NoteOn se roban las más antiguas, sin crash");
    }

    std::fprintf(stdout, "=== %s (%d fallo/s) ===\n", gFailures == 0 ? "PASS" : "FAIL", gFailures);
    return gFailures == 0 ? 0 : 1;
}
