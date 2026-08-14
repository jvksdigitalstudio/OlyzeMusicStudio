package com.yeivikas.olyze.eliner.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher

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

/**
 * Centralized, controlled access to every execution context EliNer uses.
 * "Toda tarea deberá pasar por este administrador" — this is that
 * administrator: nothing in EliNer should call `Thread(...).start()` or
 * `GlobalScope.launch` directly; everything goes through [scopeFor] (or,
 * more commonly, through [TaskScheduler]).
 *
 * Lane → dispatcher choice, and why:
 * - [ExecutionLane.AUDIO], [ExecutionLane.DSP], [ExecutionLane.RENDER] each
 *   get their own dedicated single-thread executor. These are the lanes
 *   real-time or near-real-time work will eventually run on; sharing them
 *   with a general-purpose pool ([Dispatchers.Default]) risks contention
 *   and unpredictable scheduling from unrelated background work — a single
 *   dedicated thread per lane avoids that, at the cost of not
 *   parallelizing within a lane. That trade-off is correct for these
 *   three: audio/DSP/render callbacks are inherently sequential per
 *   stream/frame anyway.
 * - [ExecutionLane.IO] uses [Dispatchers.IO] — Kotlin's own elastic pool
 *   sized for blocking I/O, exactly what it's for.
 * - [ExecutionLane.BACKGROUND] uses [Dispatchers.Default] — CPU-bound work
 *   that can be parallelized safely.
 *
 * No lane is created "just in case" beyond the five the spec names.
 */
class ThreadManager : TaskExecutor {
    private val lock = Any()
    private var shutDown = false

    // Each dedicated lane gets its own named, daemon thread so it can't
    // block JVM shutdown if something forgets to call shutdown().
    private val audioExecutor = newSingleThreadExecutor("eliner-audio")
    private val dspExecutor = newSingleThreadExecutor("eliner-dsp")
    private val renderExecutor = newSingleThreadExecutor("eliner-render")

    private val audioScope = CoroutineScope(audioExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val dspScope = CoroutineScope(dspExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val renderScope = CoroutineScope(renderExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun scopeFor(lane: ExecutionLane): CoroutineScope {
        check(!shutDown) { "ThreadManager was already shut down." }
        return when (lane) {
            ExecutionLane.AUDIO -> audioScope
            ExecutionLane.DSP -> dspScope
            ExecutionLane.RENDER -> renderScope
            ExecutionLane.IO -> ioScope
            ExecutionLane.BACKGROUND -> backgroundScope
        }
    }

    /**
     * Cancels every scope and shuts down the dedicated executors backing
     * [ExecutionLane.AUDIO]/[ExecutionLane.DSP]/[ExecutionLane.RENDER].
     * [Dispatchers.IO]/[Dispatchers.Default] are shared JVM-wide dispatchers
     * and are never shut down here — only this manager's own scopes over
     * them are cancelled.
     *
     * Idempotent-safe to call once; calling [scopeFor] afterward throws.
     */
    override fun shutdown() {
        synchronized(lock) {
            if (shutDown) return
            shutDown = true
        }
        audioScope.cancel()
        dspScope.cancel()
        renderScope.cancel()
        ioScope.cancel()
        backgroundScope.cancel()
        audioExecutor.shutdown()
        dspExecutor.shutdown()
        renderExecutor.shutdown()
    }

    private fun newSingleThreadExecutor(name: String) =
        Executors.newSingleThreadExecutor(NamedDaemonThreadFactory(name))

    /** Names threads for debuggability (thread dumps, profilers) and marks them daemon. */
    private class NamedDaemonThreadFactory(private val baseName: String) : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "$baseName-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
}
