#pragma once
#include <cstdint>
#include <atomic>
#include "Oscillator.h"
#include "Envelope.h"
#include "Filter.h"

namespace eliner {

// MIDI note → frequency table
inline double noteToHz(int note, float pitchBendSemitones = 0.0f) {
    return 440.0 * std::pow(2.0, (note - 69 + pitchBendSemitones) / 12.0);
}

class SynthVoice {
public:
    explicit SynthVoice(int sampleRate);

    // Fase 1.1 §17: [channel] (0-15) es el canal MIDI que disparó esta voz.
    // Se guarda para que noteOff/pitch bend puedan dirigirse SOLO a las
    // voces del canal correcto (cada canal MIDI es un espacio de 128 notas
    // independiente por especificación) — antes esta información se
    // descartaba en la frontera JNI y NoteOff/PitchBend afectaban a voces
    // de cualquier canal. No implica un synth multitímbrico (un único
    // timbre sigue sonando para todos los canales): es solo el contrato de
    // enrutamiento de eventos, preparado para cuando exista.
    void noteOn (int note, float velocity, int channel);
    void noteOff();
    void kill();  // immediate silence (voice steal)

    void setPitchBend(float semitones);

    // Returns true while producing sound
    bool isActive()  const;
    int  note()      const { return mNote; }
    int  channel()   const { return mChannel; }
    uint64_t age()   const { return mAge; }

    // Renders numFrames of stereo audio into out (interleaved L/R), ADDS to existing
    void render(float* out, int numFrames);

private:
    int        mSR;
    int        mNote     = -1;
    int        mChannel  = -1; // -1 = voz nunca disparada; 0-15 tras noteOn()
    float      mVelocity = 1.0f;
    float      mPitchBend = 0.0f;
    uint64_t   mAge      = 0;

    // 2 oscillators per voice for richness
    Oscillator mOsc1;
    Oscillator mOsc2;   // detuned
    Envelope   mAmpEnv;
    Envelope   mFiltEnv;
    Filter     mFilter;

    static std::atomic<uint64_t> sVoiceCounter;
};

} // namespace eliner
