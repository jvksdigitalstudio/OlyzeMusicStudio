package com.yeivikas.olyze.eliner.audiofoundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Formats audio can arrive in — from a device input or a loaded file. */
enum class InputSampleFormat { PCM_16, PCM_24, PCM_32 }

/** Formats the engine processes internally. Only two, on purpose — see [ProcessingSampleFormat.STUDIO_FLOAT64]. */
enum class ProcessingSampleFormat {
    /** Standard processing precision — compatible with every device this project supports. */
    FLOAT_32,

    /** "Modo Studio" — higher precision, higher CPU/memory cost. Not gated by capability here; that's Performance Profile's call, made elsewhere. */
    STUDIO_FLOAT64,
}

/** Formats a future Export Engine will be able to write to. Not implemented — this phase only defines the vocabulary. */
enum class ExportSampleFormat { PCM_16, PCM_24, FLOAT_32_WAV, STUDIO_FLOAT64 }

/**
 * Tracks which [InputSampleFormat]/[ProcessingSampleFormat]/[ExportSampleFormat]
 * are currently selected, and validates whether a given combination makes
 * sense — e.g. exporting at [ProcessingSampleFormat.STUDIO_FLOAT64]
 * precision while processing at [ProcessingSampleFormat.FLOAT_32] would
 * fabricate precision that was never computed.
 *
 * No actual conversion, decoding, or export happens here — "no
 * implementar exportación. Solo la infraestructura."
 */
class AudioFormatManager {
    private val _processingFormat = MutableStateFlow(ProcessingSampleFormat.FLOAT_32)
    val processingFormat: StateFlow<ProcessingSampleFormat> = _processingFormat.asStateFlow()

    fun setProcessingFormat(format: ProcessingSampleFormat) {
        _processingFormat.value = format
    }

    /**
     * Whether [export] is a sensible choice given the current
     * [processingFormat] — an export format can't claim more precision
     * than what was actually processed.
     */
    fun isExportFormatValid(export: ExportSampleFormat): Boolean = when (export) {
        ExportSampleFormat.STUDIO_FLOAT64 ->
            _processingFormat.value == ProcessingSampleFormat.STUDIO_FLOAT64
        ExportSampleFormat.PCM_16, ExportSampleFormat.PCM_24, ExportSampleFormat.FLOAT_32_WAV ->
            true // any processing precision can be safely down-converted to these
    }
}
