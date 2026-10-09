#pragma once
// Polifonía del sintetizador: reparto de notas entre voces, robo de voz y pitch
// bend por canal MIDI.
//
// ── Responsabilidad ───────────────────────────────────────────────────────
// Es la ÚNICA pieza que sabe cuántas voces hay y cómo se asignan. AudioEngine
// ya no toca SynthVoice directamente: le pasa a este pool los eventos MIDI ya
// decodificados y le pide renderizar. No sabe de colas de comandos, de Oboe, de
// FX ni de transporte.
//
// ── Contrato de canal MIDI (Fase 1.1 §17) ─────────────────────────────────
//   - Rango 0–15 (cero-basado, como MidiEvent.channel en Kotlin). Un canal fuera
//     de rango se DESCARTA aquí (defensa en profundidad: AudioEngine ya lo
//     valida en la entrada de control, pero estas ramas indexan un array y un
//     comando corrupto no debe poder escribir fuera de rango en el hilo de audio).
//   - Cada voz recuerda el canal que la disparó; NoteOff y PitchBend se dirigen
//     solo a voces de ese canal (cada canal es un espacio de 128 notas
//     independiente).
//   - AllNotesOff es deliberadamente GLOBAL (pánico / parada de transporte).
//   - El pitch bend se recuerda POR CANAL: una SynthVoice conserva su último bend
//     al reutilizarse, y sin este estado el bend de un canal se filtraría a la
//     siguiente nota disparada en OTRO canal.
//
// ── Hilos ─────────────────────────────────────────────────────────────────
//   build():          hilo de control, ANTES de que el hilo de audio exista o
//                     esté habilitado (asigna memoria).
//   resto de métodos: hilo de audio ÚNICAMENTE (sin locks, sin asignación).
// Sin estado atómico: el dueño (AudioEngine) garantiza esa separación.

#include <array>
#include <cstdint>
#include <memory>
#include "EngineConstants.h"
#include "SynthVoice.h"

namespace eliner {

class VoicePool {
public:
    static constexpr int kMidiChannels = 16;

    // Construye (o reconstruye) todas las voces contra la frecuencia REAL del
    // stream. Hilo de control, antes de habilitar el audio.
    void build(int sampleRate);

    // Dispara una nota. [velocity] en 0–127. Si no hay voz libre roba la más
    // antigua (la de menor age()). Canal inválido → no hace nada.
    void noteOn(int channel, int note, int velocity);

    // Libera la voz activa del MISMO canal y nota. Canal inválido → no hace nada.
    void noteOff(int channel, int note);

    // Silencia todas las voces de golpe (pánico / parada).
    void allNotesOff();

    // Fija el bend (semitonos) del canal y lo aplica a sus voces activas.
    void setPitchBend(int channel, float semitones);

    // Mezcla todas las voces activas en [out] (estéreo intercalado) y devuelve
    // cuántas estaban activas ANTES de renderizar (una voz puede quedar inactiva
    // dentro de render(); se cuenta la actividad del bloque, no la final).
    int render(float* out, int numFrames);

    // ── Introspección de SOLO LECTURA (diagnóstico y tests) ─────────────────
    // Cuántas voces activas hay para ese canal+nota. No se usa en el camino de
    // audio. Hilo de audio, o cualquier hilo si el audio está parado.
    int countActive(int channel, int note) const;

private:
    static bool isValidChannel(int channel) { return channel >= 0 && channel < kMidiChannels; }

    std::array<std::unique_ptr<SynthVoice>, kMaxVoices> mVoices;
    std::array<float, kMidiChannels> mChannelPitchBend{};
};

} // namespace eliner
