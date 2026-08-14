package com.yeivikas.olyze.eliner.modules.audio

/** The pipeline stages, in signal-flow order — matches the spec's conceptual diagram exactly. */
enum class AudioPipelineStage { INPUT, ENGINE, DSP, MIXER, MASTER, OUTPUT }

/**
 * The audio signal-flow architecture: Input → Engine → DSP (futuro) →
 * Mixer (futuro) → Master (futuro) → Output. This class only records the
 * *order* — it does not process a single sample. "No implementar
 * procesamiento. Solo construir la infraestructura."
 */
class AudioPipeline {
    /** Fixed, ordered stage list — the shape future DSP/Mixer/Master engines will slot into. */
    val stages: List<AudioPipelineStage> = listOf(
        AudioPipelineStage.INPUT,
        AudioPipelineStage.ENGINE,
        AudioPipelineStage.DSP,
        AudioPipelineStage.MIXER,
        AudioPipelineStage.MASTER,
        AudioPipelineStage.OUTPUT,
    )

    /** The stage immediately after [stage], or null if [stage] is the last one. */
    fun nextStage(stage: AudioPipelineStage): AudioPipelineStage? {
        val index = stages.indexOf(stage)
        return stages.getOrNull(index + 1)
    }

    /** The stage immediately before [stage], or null if [stage] is the first one. */
    fun previousStage(stage: AudioPipelineStage): AudioPipelineStage? {
        val index = stages.indexOf(stage)
        return if (index <= 0) null else stages.getOrNull(index - 1)
    }
}
