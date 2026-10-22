#include "VoicePool.h"

namespace eliner {

void VoicePool::build(int sampleRate) {
    for (auto& v : mVoices) {
        v = std::make_unique<SynthVoice>(sampleRate);
    }
}

void VoicePool::noteOn(int channel, int note, int velocity) {
    if (!isValidChannel(channel)) return;

    SynthVoice* target = nullptr;
    SynthVoice* oldest = nullptr;
    uint64_t    minAge = UINT64_MAX;
    for (auto& v : mVoices) {
        if (!v->isActive()) { target = v.get(); break; }
        if (v->age() < minAge) { minAge = v->age(); oldest = v.get(); }
    }
    if (!target) target = oldest; // voice steal
    if (target) {
        // La voz (posiblemente reciclada de otro canal) hereda el bend vigente
        // del canal que la dispara, no el suyo previo.
        target->setPitchBend(mChannelPitchBend[channel]);
        target->noteOn(note, velocity / 127.0f, channel);
    }
}

void VoicePool::noteOff(int channel, int note) {
    if (!isValidChannel(channel)) return;
    // §17: un NoteOff solo afecta a la voz disparada por el MISMO canal+nota.
    for (auto& v : mVoices) {
        if (v->isActive() && v->note() == note && v->channel() == channel) v->noteOff();
    }
}

void VoicePool::allNotesOff() {
    for (auto& v : mVoices) v->kill();
}

void VoicePool::setPitchBend(int channel, float semitones) {
    if (!isValidChannel(channel)) return;
    // §17: el pitch bend es un mensaje POR CANAL: solo afecta a las voces de ese
    // canal; el valor se recuerda para las notas que se disparen después.
    mChannelPitchBend[channel] = semitones;
    for (auto& v : mVoices) {
        if (v->isActive() && v->channel() == channel) v->setPitchBend(semitones);
    }
}

int VoicePool::countActive(int channel, int note) const {
    int n = 0;
    for (const auto& v : mVoices) {
        if (v && v->isActive() && v->channel() == channel && v->note() == note) ++n;
    }
    return n;
}

int VoicePool::render(float* out, int numFrames) {
    int active = 0;
    for (auto& voice : mVoices) {
        if (!voice->isActive()) continue;
        voice->render(out, numFrames);
        active++; // la voz puede quedar inactiva dentro de render(): se cuenta la actividad previa
    }
    return active;
}

} // namespace eliner
