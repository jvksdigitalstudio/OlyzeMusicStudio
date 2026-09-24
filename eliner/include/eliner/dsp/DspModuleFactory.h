#pragma once
#include "DspModule.h"

namespace eliner {

// Allocates a new module of `type` at `sampleRate`. CONTROL-THREAD ONLY —
// this calls `new`, which is not realtime-safe (see AudioEngine::
// insertModule(), the only caller). Returns nullptr for any DspModuleType
// that has no implementation yet (there is no placeholder/stub module —
// per this project's conventions, an unimplemented type simply fails to
// insert rather than pretending to insert something inert; see ADR 0010
// for the broader project stance on not pretending stubs are real).
DspModule* createDspModule(DspModuleType type, int sampleRate);

} // namespace eliner
