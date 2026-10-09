#include "EngineHandle.h"

namespace eliner::jni {

// Inicialización constante (constexpr): sin orden de inicialización estática
// que vigilar entre unidades de traducción.
std::mutex                   gEngineMutex;
std::unique_ptr<AudioEngine> gEngine;

} // namespace eliner::jni
