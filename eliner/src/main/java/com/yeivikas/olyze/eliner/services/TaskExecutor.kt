package com.yeivikas.olyze.eliner.services

import kotlinx.coroutines.CoroutineScope

/**
 * The five execution lanes named explicitly in the Fase 2 spec — exactly
 * that list, nothing added speculatively.
 */
enum class ExecutionLane { AUDIO, DSP, RENDER, IO, BACKGROUND }

/**
 * The part of [ThreadManager] other services are allowed to depend on.
 *
 * [shutdown] was added in Fase 2.5: `EliNerRuntime` needs to be able to
 * shut down execution resources during its own shutdown sequence, not
 * just hand out scopes — this is genuinely part of "administering
 * execution", not an audio/DSP-specific concern, so it belongs on the
 * contract, not bolted on as a second interface.
 */
interface TaskExecutor {
    /** The [CoroutineScope] tasks for [lane] should run on. */
    fun scopeFor(lane: ExecutionLane): CoroutineScope

    /** Cancels every scope and releases dedicated execution resources. */
    fun shutdown()
}
