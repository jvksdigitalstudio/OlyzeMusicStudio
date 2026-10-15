package com.yeivikas.olyze.eliner.api.audio

/**
 * Fase 1.1 §22 (ADR 0023) — dirección arquitectónica mínima para el
 * "sistema de parámetros", sin construir automation/smoothing/curvas que
 * nada usa todavía (prohibido explícitamente por el prompt de esta fase:
 * "no implementar una arquitectura gigantesca si todavía no es necesaria").
 *
 * Lo que SÍ existía antes de este archivo: una colección creciente de
 * métodos concretos en [EliNerAudioApi] (`setMasterVolume`, `setReverbMix`,
 * `setDelayTime`, ...) — funcionalmente correcta, pero sin ningún lugar
 * donde consultar, de forma programática, CUÁLES son todos los parámetros
 * que existen, cuál es su rango real, o su unidad — cada nueva pantalla de
 * UI que necesitara esa información habría tenido que ir a leer los
 * comentarios de `AudioEngine.h`/`CommandQueue.h` (C++) a mano. Ese vacío
 * es precisamente lo que este archivo llena, y nada más.
 *
 * [ParameterMetadata] tiene deliberadamente SOLO los campos que el prompt
 * de esta fase pide como base ("id, name, min, max, default, unit") más
 * [target] (necesario para poder aplicar el valor sin un `when` nuevo por
 * cada consumidor). Los campos que el prompt menciona pero NO pide
 * implementar todavía — `curve` (respuesta no lineal), `smoothing`
 * (rampa/interpolación), `automationSupported`, `midiMappingSupported` —
 * se documentan aquí como EXTENSIÓN FUTURA explícita, no se construyen:
 * cuando exista un consumidor real (automation, MIDI Learn), se añaden a
 * [ParameterMetadata] entonces, con ese caso de uso real delante, en vez
 * de adivinar su forma ahora.
 */
data class ParameterMetadata(
    /** Identificador estable — para logging/diagnóstico/serialización futura, nunca para lógica condicional (usa [target] para eso). */
    val id: String,
    val displayName: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: ParameterUnit,
    /** Cómo aplicar [ParameterCatalog.applyDefault] o un valor arbitrario — ver [ParameterTarget]. */
    val target: ParameterTarget,
)

enum class ParameterUnit { LINEAR_0_1, BIPOLAR_MINUS1_1, SECONDS, SEMITONES, MODULE_LOCAL_0_1 }

/**
 * Cómo aplicar un valor de este parámetro contra un [EliNerAudioApi] real —
 * evita que [ParameterCatalog] (o cualquier consumidor futuro, p. ej. un
 * panel de mezcla genérico) necesite un `when (id)` propio y potencialmente
 * desincronizado del catálogo.
 */
sealed interface ParameterTarget {
    fun apply(audio: EliNerAudioApi, value: Float)

    data object MasterVolume : ParameterTarget { override fun apply(audio: EliNerAudioApi, value: Float) = audio.setMasterVolume(value) }
    data object ReverbMix : ParameterTarget { override fun apply(audio: EliNerAudioApi, value: Float) = audio.setReverbMix(value) }
    data object ReverbRoom : ParameterTarget { override fun apply(audio: EliNerAudioApi, value: Float) = audio.setReverbRoom(value) }
    data object ReverbDamp : ParameterTarget { override fun apply(audio: EliNerAudioApi, value: Float) = audio.setReverbDamp(value) }
    data object DelayMix : ParameterTarget { override fun apply(audio: EliNerAudioApi, value: Float) = audio.setDelayMix(value) }
    data object DelayTime : ParameterTarget { override fun apply(audio: EliNerAudioApi, value: Float) = audio.setDelayTime(value) }
    data object DelayFeedback : ParameterTarget { override fun apply(audio: EliNerAudioApi, value: Float) = audio.setDelayFeedback(value) }

    /**
     * Parámetros locales a un módulo de la cadena FX dinámica (p. ej.
     * [ReverbParam]/[DelayParam]) — `slot` identifica QUÉ instancia (hay
     * hasta [EliNerAudioApi.maxChainSlots] simultáneas), a diferencia de
     * los de arriba, que son únicos y globales (un solo master, sin buses).
     */
    data class ModuleParameter(val slot: Int, val paramId: Int) : ParameterTarget {
        override fun apply(audio: EliNerAudioApi, value: Float) = audio.setModuleParameter(slot, paramId, value)
    }
}

/**
 * Fuente de verdad ÚNICA para "qué parámetros existen" a nivel de master
 * (no depende de leer `AudioEngine.h`/`CommandQueue.h` a mano). No incluye
 * los module-local (`ReverbParam`/`DelayParam`) como entradas fijas — esos
 * dependen de qué tipo de módulo ocupa cada slot en tiempo de ejecución;
 * [forModule] los construye bajo demanda.
 *
 * [MASTER] cubre las 8 entradas del `enum class DspParameterId` nativo
 * (`CommandQueue.h`) — antes de esta fase, dos de ellas (`ReverbRoom`,
 * `ReverbDamp`) no tenían NINGÚN setter público en Kotlin que las
 * alcanzara (ver ADR 0023): este catálogo es, en parte, lo que hizo
 * visible ese hueco al enumerar la superficie real contra el enum nativo
 * completo, en vez de contra la lista de métodos que ya existían en
 * [EliNerAudioApi].
 */
object ParameterCatalog {
    val MASTER: List<ParameterMetadata> = listOf(
        ParameterMetadata("master.volume", "Master Volume", 0f, 1f, 1f, ParameterUnit.LINEAR_0_1, ParameterTarget.MasterVolume),
        ParameterMetadata("master.reverb.mix", "Reverb Send", 0f, 1f, 0f, ParameterUnit.LINEAR_0_1, ParameterTarget.ReverbMix),
        ParameterMetadata("master.reverb.room", "Reverb Room Size", 0f, 1f, 0.5f, ParameterUnit.LINEAR_0_1, ParameterTarget.ReverbRoom),
        ParameterMetadata("master.reverb.damp", "Reverb Damping", 0f, 1f, 0.5f, ParameterUnit.LINEAR_0_1, ParameterTarget.ReverbDamp),
        ParameterMetadata("master.delay.mix", "Delay Send", 0f, 1f, 0f, ParameterUnit.LINEAR_0_1, ParameterTarget.DelayMix),
        ParameterMetadata("master.delay.time", "Delay Time", 0.01f, 2f, 0.3f, ParameterUnit.SECONDS, ParameterTarget.DelayTime),
        ParameterMetadata("master.delay.feedback", "Delay Feedback", 0f, 0.95f, 0.3f, ParameterUnit.LINEAR_0_1, ParameterTarget.DelayFeedback),
    )

    /**
     * Metadata para los parámetros del módulo en [slot], según su
     * [DspModuleType] — construida bajo demanda porque, a diferencia de
     * [MASTER], depende de qué módulo real ocupa ese slot en este momento.
     * Devuelve lista vacía para [DspModuleType.NONE] o un tipo sin
     * parámetros conocidos.
     */
    fun forModule(slot: Int, type: DspModuleType): List<ParameterMetadata> = when (type) {
        DspModuleType.REVERB -> listOf(
            ParameterMetadata("module.$slot.reverb.mix", "Mix", 0f, 1f, 0.5f, ParameterUnit.MODULE_LOCAL_0_1, ParameterTarget.ModuleParameter(slot, ReverbParam.MIX)),
            ParameterMetadata("module.$slot.reverb.room", "Room", 0f, 1f, 0.5f, ParameterUnit.MODULE_LOCAL_0_1, ParameterTarget.ModuleParameter(slot, ReverbParam.ROOM)),
            ParameterMetadata("module.$slot.reverb.damp", "Damp", 0f, 1f, 0.5f, ParameterUnit.MODULE_LOCAL_0_1, ParameterTarget.ModuleParameter(slot, ReverbParam.DAMP)),
        )
        DspModuleType.DELAY -> listOf(
            ParameterMetadata("module.$slot.delay.mix", "Mix", 0f, 1f, 0.5f, ParameterUnit.MODULE_LOCAL_0_1, ParameterTarget.ModuleParameter(slot, DelayParam.MIX)),
            ParameterMetadata("module.$slot.delay.time", "Time", 0.01f, 2f, 0.3f, ParameterUnit.SECONDS, ParameterTarget.ModuleParameter(slot, DelayParam.TIME)),
            ParameterMetadata("module.$slot.delay.feedback", "Feedback", 0f, 0.95f, 0.3f, ParameterUnit.MODULE_LOCAL_0_1, ParameterTarget.ModuleParameter(slot, DelayParam.FEEDBACK)),
        )
        DspModuleType.NONE -> emptyList()
    }
}
