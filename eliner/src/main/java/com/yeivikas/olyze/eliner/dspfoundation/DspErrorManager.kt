package com.yeivikas.olyze.eliner.dspfoundation

import com.yeivikas.olyze.eliner.core.EngineError
import com.yeivikas.olyze.eliner.core.EngineErrorSeverity
import com.yeivikas.olyze.eliner.diagnostics.Logger

/**
 * DSP Diagnostics — "integrar Logger y Diagnostics para registrar errores
 * específicos del sistema DSP." Reuses [EngineError]/[EngineErrorSeverity]
 * (Core Foundation) and [Logger] (Diagnostics), exactly like
 * `com.yeivikas.olyze.eliner.modules.audio.AudioErrorManager` (Fase 4) —
 * one error shape for the whole engine, not a third parallel type.
 *
 * "No lanzar excepciones innecesarias durante el procesamiento" — this
 * class never throws; it only records and forwards.
 */
class DspErrorManager(private val logger: Logger) {

    fun reportError(code: String, message: String, severity: EngineErrorSeverity, cause: Throwable? = null) {
        logger.log(EngineError(code = code, message = message, severity = severity, moduleId = MODULE_ID, cause = cause))
    }

    private companion object {
        const val MODULE_ID = "dsp-foundation"
    }
}
