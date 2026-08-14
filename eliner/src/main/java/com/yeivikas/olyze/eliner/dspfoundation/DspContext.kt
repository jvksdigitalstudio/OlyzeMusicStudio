package com.yeivikas.olyze.eliner.dspfoundation

import com.yeivikas.olyze.eliner.api.RuntimeContext

/**
 * "El punto de acceso para toda la infraestructura futura" del sistema
 * DSP — aggregates every DSP Foundation component into one reference,
 * mirroring how `com.yeivikas.olyze.eliner.api.RuntimeContext` (Fase 2.5)
 * and `com.yeivikas.olyze.eliner.audiofoundation.AudioFoundationContext`
 * (Fase 3) aggregate their own layers.
 */
class DspContext(
    val parameterManager: DspParameterManager,
    val graph: DspGraph,
    val scheduler: DspScheduler,
    val bufferPool: DspBufferPool,
    val metrics: DspMetrics,
    val errorManager: DspErrorManager,
)

/**
 * Builds a [DspContext] from an existing [RuntimeContext] — the concrete
 * "Integración con Foundation Services" this phase asks for:
 * [DspErrorManager] reuses [RuntimeContext.logger] directly, no second
 * reference invented. [frameCount]/[channelCount] size the underlying
 * [DspBufferPool] — typically taken from
 * `com.yeivikas.olyze.eliner.audiofoundation.AudioFoundationContext`'s own
 * buffer/channel managers by whoever composes the full engine.
 */
fun createDspContext(
    runtimeContext: RuntimeContext,
    frameCount: Int,
    channelCount: Int,
): DspContext = DspContext(
    parameterManager = DspParameterManager(),
    graph = DspGraph(),
    scheduler = DspScheduler(),
    bufferPool = DspBufferPool(frameCount, channelCount),
    metrics = DspMetrics(),
    errorManager = DspErrorManager(runtimeContext.logger),
)
