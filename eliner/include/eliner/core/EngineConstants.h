#pragma once
// Constantes compartidas del motor de audio. Header hoja: sin dependencias.
//
// Vivían al principio de AudioEngine.h; se separan para que los módulos
// pequeños (VoicePool, futuros consumidores) las usen sin arrastrar Oboe ni la
// clase AudioEngine completa.

namespace eliner {

constexpr int    kMaxVoices      = 32;   // polyphony
constexpr int    kPreferredSampleRate = 48000; // requested from Oboe; the
                                                // stream's ACTUAL negotiated
                                                // rate (which may differ per
                                                // device) is what DSP objects
                                                // are built with — see start().
constexpr int    kChannels       = 2;    // stereo
constexpr double kTwoPi          = 6.28318530717958647692;

} // namespace eliner
