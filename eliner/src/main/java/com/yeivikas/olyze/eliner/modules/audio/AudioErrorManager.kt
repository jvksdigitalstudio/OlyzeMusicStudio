package com.yeivikas.olyze.eliner.modules.audio

import com.yeivikas.olyze.eliner.core.EngineError
import com.yeivikas.olyze.eliner.core.EngineErrorSeverity
import com.yeivikas.olyze.eliner.diagnostics.Logger

/**
 * Centralized audio error reporting — "todos los errores deberán
 * integrarse con Logger, Diagnostics, Runtime."
 *
 * Deliberately reuses [EngineError]/[EngineErrorSeverity] (Core Foundation,
 * Fase 1) instead of defining a parallel `AudioError` type — one error
 * shape for the whole engine, not two. [Logger] *is* the Diagnostics
 * integration (it lives in `eliner.diagnostics`), so reporting through it
 * satisfies "Logger, Diagnostics" as a single path, not two separate ones.
 *
 * Runtime integration is already covered by a different, existing path:
 * once `AudioEngine` is registered as an
 * [com.yeivikas.olyze.eliner.core.EliNerModule], any exception thrown from
 * its `onStart`/`onStop` is already caught and reported by
 * `com.yeivikas.olyze.eliner.core.EliNerCore`, which
 * `com.yeivikas.olyze.eliner.runtime.EliNerRuntime` forwards to [Logger]
 * (see Fase 2.5). [AudioErrorManager] is the complementary path for
 * *in-flight* audio errors (xruns, etc.) that aren't module lifecycle
 * failures.
 *
 * "No lanzar excepciones innecesarias dentro del procesamiento" — this
 * class never throws; it only records and forwards.
 */
class AudioErrorManager(private val logger: Logger) {

    fun reportError(code: String, message: String, severity: EngineErrorSeverity, cause: Throwable? = null) {
        logger.log(EngineError(code = code, message = message, severity = severity, moduleId = MODULE_ID, cause = cause))
    }

    fun reportXrun() = reportError("AUDIO_XRUN", "Audio buffer xrun detected.", EngineErrorSeverity.WARNING)
    fun reportUnderrun() = reportError("AUDIO_UNDERRUN", "Audio buffer underrun.", EngineErrorSeverity.WARNING)
    fun reportOverrun() = reportError("AUDIO_OVERRUN", "Audio buffer overrun.", EngineErrorSeverity.WARNING)

    private companion object {
        const val MODULE_ID = "audio-engine"
    }
}
