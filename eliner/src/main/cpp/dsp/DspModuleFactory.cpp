#include "DspModuleFactory.h"
#include "Reverb.h"
#include "Delay.h"

namespace eliner {

DspModule* createDspModule(DspModuleType type, int sampleRate) {
    switch (type) {
        case DspModuleType::Reverb: return new Reverb(sampleRate);
        case DspModuleType::Delay:  return new Delay(sampleRate);
        case DspModuleType::None:   return nullptr;
    }
    return nullptr; // Unreachable for a valid enumerator today. No
                     // `default:` on purpose — GCC/Clang both warn
                     // (-Wswitch) when a switch over an enum omits a
                     // case, so adding a new DspModuleType and forgetting
                     // to handle it here surfaces as a build warning, not
                     // silence. It is only a warning, not a build failure
                     // (this target doesn't build with -Werror) — worth
                     // upgrading to -Werror=switch in a future phase if
                     // this factory grows more cases.
}

} // namespace eliner
