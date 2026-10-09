// AudioEngine — hilo de CONTROL: API pública que valida y ENCOLA comandos.
//
// Nada de lo que hay aquí toca estado de audio: cada llamada valida su entrada
// y la envía al hilo de audio por la cola SPSC (que aplica AudioEngineCommands.cpp).
// Ver AudioEngine.cpp para el reparto de unidades.
#include "AudioEngine.h"
#include "EngineLog.h"
#include "TempoSync.h" // validación por bits y límites de tempo (ADR 0028)

namespace eliner {

// ── Control thread → command queue (never blocks, never touches DSP state) ──
bool AudioEngine::pushCommand(const EngineCommand& cmd) {
    bool ok = mCommandQueue.push(cmd);
    if (!ok) {
        LOGE("Command queue full — dropped command type=%d", (int)cmd.type);
    }
    return ok;
}

void AudioEngine::pushParameter(DspParameterId id, float value) {
    EngineCommand cmd;
    cmd.type    = EngineCommandType::SetParameter;
    cmd.floatA  = value;
    cmd.paramId = id;
    pushCommand(cmd);
}

// ── MIDI (control thread — JNI-facing) ───────────────────────────────────────
//
// Fase 1.1 §17 — contrato de canal MIDI:
//   - Rango: 0-15 (cero-basado, igual que MidiEvent.channel en Kotlin y que
//     el nibble bajo del byte de estado MIDI). La conversión a 1-16 es
//     responsabilidad exclusiva de la UI, nunca de esta capa.
//   - Un canal fuera de rango se DESCARTA aquí (no se recorta a otro
//     canal, lo que enrutaría el evento a un canal equivocado en silencio).
//     El descarte es silencioso hacia el llamador, mismo contrato
//     "best-effort" que el resto de esta cola.
//   - Ownership: cada voz recuerda el canal que la disparó (SynthVoice::
//     channel()); NoteOff y PitchBend se dirigen solo a voces de ese canal.
//   - AllNotesOff es deliberadamente GLOBAL (pánico/stop de transporte, su
//     firma pública no lleva canal).
//   - sendCC: ver comentario en la propia función.
static inline bool isValidMidiChannel(int channel) { return channel >= 0 && channel <= 15; }

void AudioEngine::noteOn(int channel, int note, int velocity) {
    if (!isValidMidiChannel(channel)) return;
    pushCommand({EngineCommandType::NoteOn, note, velocity, 0.0f, channel});
}

void AudioEngine::noteOff(int channel, int note) {
    if (!isValidMidiChannel(channel)) return;
    pushCommand({EngineCommandType::NoteOff, note, 0, 0.0f, channel});
}

void AudioEngine::allNotesOff() {
    pushCommand({EngineCommandType::AllNotesOff, 0, 0, 0.0f});
}

void AudioEngine::sendCC(int /*channel*/, int cc, int value) {
    // CC handling — expand per instrument as modules are added.
    // `channel` queda sin usar A PROPÓSITO (a diferencia de noteOn/noteOff/
    // pitch bend, donde ignorarlo era un bug de enrutamiento de voces): los
    // CC 7/10/91/93/123 se interpretan hoy como controles GLOBALES del
    // master (un único bus master, sin strips por canal — el Mixer queda
    // fuera de esta fase). Cuando existan strips/instrumentos por canal,
    // el canal se propagará aquí igual que en NoteOn; el contrato JNI ya
    // lo trae hasta este punto.
    switch (cc) {
        case 7:  setMasterVolume(value / 127.0f); break;  // Main Volume
        case 10: setMasterPan((value - 64) / 64.0f); break; // Pan
        case 91: setReverbMix(value / 127.0f); break;       // Reverb Send
        case 93: setDelayMix(value / 127.0f); break;        // Chorus/Delay Send
        case 123: allNotesOff(); break;                      // All Notes Off
        default: break;
    }
}

void AudioEngine::setPitchBend(int channel, float semitones) {
    if (!isValidMidiChannel(channel)) return;
    pushCommand({EngineCommandType::PitchBend, 0, 0, semitones, channel});
}

void AudioEngine::setMasterVolume(float vol) {
    pushParameter(DspParameterId::MasterVolume, vol);
}

void AudioEngine::setMasterPan(float pan) {
    pushParameter(DspParameterId::MasterPan, pan);
}

// ── Transport / tempo (control thread — ADR 0028) ────────────────────────────
//
// Antes de ADR 0028 setTempo() era un no-op: no existía ningún consumidor del
// tempo. Ahora el reloj de transporte, el metrónomo y el sync del delay lo
// consumen, siempre en el hilo de audio. Aquí (hilo de control) solo se
// valida y se encola; la validación es por BITS (tempo::isFinite) porque el
// build usa -ffast-math, bajo el cual std::isfinite puede optimizarse a true.
void AudioEngine::setTempo(float bpm) {
    if (!tempo::isFinite(bpm)) return;
    pushParameter(DspParameterId::Tempo,
                  static_cast<float>(tempo::clampBpm(static_cast<double>(bpm))));
}

void AudioEngine::setTransportRunning(bool running) {
    pushParameter(DspParameterId::TransportRunning, running ? 1.0f : 0.0f);
}

void AudioEngine::setBeatsPerBar(int beats) {
    if (beats < TempoClock::kMinBeatsPerBar) beats = TempoClock::kMinBeatsPerBar;
    if (beats > TempoClock::kMaxBeatsPerBar) beats = TempoClock::kMaxBeatsPerBar;
    pushParameter(DspParameterId::BeatsPerBar, static_cast<float>(beats));
}

void AudioEngine::setMetronomeEnabled(bool enabled) {
    pushParameter(DspParameterId::MetronomeEnabled, enabled ? 1.0f : 0.0f);
}

void AudioEngine::setMetronomeVolume(float volume) {
    if (!tempo::isFinite(volume)) return;
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    pushParameter(DspParameterId::MetronomeVolume, volume);
}

// ── Click del metrónomo (ADR 0031) ───────────────────────────────────────────
// Se valida AQUÍ (hilo de control) y otra vez en el Metronome (hilo de audio, defensa
// en profundidad): un valor fuera de rango no se encola, no se "recorta" a otro sonido.
void AudioEngine::setMetronomeSound(int sound) {
    if (sound < 0 || sound >= static_cast<int>(ClickSound::Count)) return;
    pushParameter(DspParameterId::MetronomeSound, static_cast<float>(sound));
}

void AudioEngine::setMetronomeAccent(bool enabled) {
    pushParameter(DspParameterId::MetronomeAccent, enabled ? 1.0f : 0.0f);
}

void AudioEngine::setMetronomeSubdivision(int perBeat) {
    if (perBeat < kMinClickSubdivision || perBeat > kMaxClickSubdivision) return;
    pushParameter(DspParameterId::MetronomeSubdivision, static_cast<float>(perBeat));
}

void AudioEngine::setDelayTempoSync(bool enabled, float beats) {
    // Orden fijo: primero la duración (si es válida), luego el interruptor, de
    // modo que al activar el sync el hilo de audio ya conoce su duración.
    if (tempo::isFinite(beats) && beats > 0.0f) {
        if (beats < 0.0625f) beats = 0.0625f;   // 1/64 de redonda
        if (beats > 16.0f)   beats = 16.0f;     // 4 compases de 4/4
        pushParameter(DspParameterId::DelaySyncBeats, beats);
    }
    pushParameter(DspParameterId::DelaySyncEnabled, enabled ? 1.0f : 0.0f);
}

void AudioEngine::setReverbMix(float mix) {
    pushParameter(DspParameterId::ReverbMix, mix);
}

void AudioEngine::setReverbRoom(float room) {
    pushParameter(DspParameterId::ReverbRoom, room);
}

void AudioEngine::setReverbDamp(float damp) {
    pushParameter(DspParameterId::ReverbDamp, damp);
}

void AudioEngine::setDelayMix(float mix) {
    pushParameter(DspParameterId::DelayMix, mix);
}

void AudioEngine::setDelayTime(float s) {
    pushParameter(DspParameterId::DelayTime, s);
}

void AudioEngine::setDelayFeedback(float fb) {
    pushParameter(DspParameterId::DelayFeedback, fb);
}

} // namespace eliner
