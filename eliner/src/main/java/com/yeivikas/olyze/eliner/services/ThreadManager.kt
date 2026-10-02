package com.yeivikas.olyze.eliner.services

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

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
    // ── Protocolo de acceso al estado de cierre (Fase 1.1 §19, ADR 0022) ──
    //
    // `shutDown` es un latch MONÓTONO (false -> true, una sola vez, nunca
    // vuelve atrás) sin invariante compuesto con ningún otro campo. Sus dos
    // accesos tienen necesidades distintas, y por eso el mecanismo también
    // lo es:
    //
    //  - ESCRITURA / exclusión de [shutdown]: bajo [lifecycleLock], durante
    //    TODO el cuerpo de la secuencia de cierre (no solo el cambio del
    //    flag). Así un segundo shutdown() concurrente espera al primero y,
    //    al retornar cualquier llamada, TODO ya está cancelado (antes, el
    //    segundo llamador retornaba mientras el primero aún estaba
    //    cancelando). Sostener el lock aquí no puede bloquear ni interbloquear:
    //    CoroutineScope.cancel() y ExecutorService.shutdown() no esperan
    //    a nada, y el lock es reentrante para el mismo hilo.
    //
    //  - LECTURA en [scopeFor]: SIN lock (camino caliente, llamado desde
    //    cualquier lane). Solo necesita VISIBILIDAD de la escritura, y ese es
    //    exactamente el contrato de `@Volatile` (relación happens-before
    //    escritura -> lecturas posteriores). Antes era un `var` plano
    //    escrito bajo lock pero leído sin él: sin happens-before, el modelo
    //    de memoria de Java permitía a un hilo seguir viendo `false`
    //    indefinidamente.
    //
    // `check(!shutDown)` en [scopeFor] es un fail-fast de MEJOR ESFUERZO, no
    // una garantía: entre el check y el uso del scope devuelto otro hilo
    // puede ejecutar shutdown() (TOCTOU inevitable — ni un lock lo evitaría,
    // porque el scope se devuelve fuera de él). Es benigno por construcción:
    // lanzar en un scope ya cancelado no ejecuta el bloque (el Job nace
    // cancelado). La seguridad real la da la cancelación, no este check.
    private val lifecycleLock = Any()
    @Volatile private var shutDown = false

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
     * Idempotente y seguro ante llamadas concurrentes: el primer llamador
     * ejecuta la secuencia completa; cualquier otro (concurrente o posterior)
     * espera a que termine y retorna — al volver de CUALQUIER llamada, todos
     * los scopes están cancelados y los executors dedicados en shutdown.
     * Llamar a [scopeFor] después lanza [IllegalStateException] (fail-fast de
     * mejor esfuerzo, ver el protocolo de acceso arriba).
     */
    override fun shutdown() {
        synchronized(lifecycleLock) {
            if (shutDown) return
            // Flag primero: cierra cuanto antes la ventana en la que scopeFor()
            // aún entregaría scopes vivos.
            shutDown = true
            audioScope.cancel()
            dspScope.cancel()
            renderScope.cancel()
            ioScope.cancel()
            backgroundScope.cancel()
            audioExecutor.shutdown()
            dspExecutor.shutdown()
            renderExecutor.shutdown()
        }
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
