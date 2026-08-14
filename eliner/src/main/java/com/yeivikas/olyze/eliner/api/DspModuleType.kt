package com.yeivikas.olyze.eliner.api

/**
 * Kotlin-side mirror of the native `DspModuleType` enum
 * (`eliner/include/eliner/dsp/DspModule.h`, Fase 7 — DSP Graph real).
 *
 * Identifies what kind of effect occupies a slot in the engine's dynamic
 * FX chain (see [EliNerAudioApi]'s "Dynamic FX chain" section). This is
 * about the REAL native chain reachable through [EliNerAudioApi] /
 * `EliNerAudioBridge` — it has nothing to do with the disconnected
 * `eliner.modules.audio` / `eliner.dspfoundation` stack from Fases 4-5
 * (see `docs/adr/0010-fase6-frontera-native-dsp.md` for why that stack
 * stays untouched). Different package, different purpose, deliberately
 * not reusing any of its types.
 *
 * [nativeId] must stay in sync with the C++ `enum class DspModuleType`
 * values — append-only on both sides, never renumber an existing one.
 */
enum class DspModuleType(val nativeId: Int) {
    REVERB(0),
    DELAY(1),

    /**
     * Sentinel: "no module in this slot" — never a real, loadable module.
     * Matches the native `DspModuleType::None` (0xFF).
     */
    NONE(0xFF);

    companion object {
        fun fromNativeId(id: Int): DspModuleType =
            entries.firstOrNull { it.nativeId == id } ?: NONE
    }
}

/**
 * Module-local parameter ids for [DspModuleType.REVERB], matching the
 * native `Reverb::Param` enum (`eliner/include/eliner/fx/Reverb.h`). Pass
 * one of these as `paramId` to [EliNerAudioApi.setModuleParameter] when
 * the target slot holds a Reverb module.
 */
object ReverbParam {
    const val MIX: Int = 0   // 0.0 – 1.0 wet mix
    const val ROOM: Int = 1  // 0.0 – 1.0
    const val DAMP: Int = 2  // 0.0 – 1.0
}

/**
 * Module-local parameter ids for [DspModuleType.DELAY], matching the
 * native `Delay::Param` enum (`eliner/include/eliner/fx/Delay.h`). Pass
 * one of these as `paramId` to [EliNerAudioApi.setModuleParameter] when
 * the target slot holds a Delay module.
 */
object DelayParam {
    const val MIX: Int = 0       // 0.0 – 1.0
    const val TIME: Int = 1      // 0.01 – 2.0 seconds
    const val FEEDBACK: Int = 2  // 0.0 – 0.95
}
