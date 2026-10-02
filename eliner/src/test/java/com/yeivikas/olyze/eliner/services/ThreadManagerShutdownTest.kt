package com.yeivikas.olyze.eliner.services

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fase 1.1 §19 — protocolo de cierre de [ThreadManager] (ver el comentario
 * "Protocolo de acceso al estado de cierre" de esa clase y ADR 0022).
 *
 * Réplica ejecutada de la estructura de sincronización (sin JVM):
 * eliner/src/test/cpp/core/test_thread_manager_shutdown_protocol_model.cpp.
 *
 * EJECUTADO: NO en el entorno de desarrollo (sin kotlinc/JDK completo);
 * pendiente de `./gradlew :eliner:testDebugUnitTest`.
 */
class ThreadManagerShutdownTest {

    @Test
    fun `scopeFor throws after shutdown`() {
        val tm = ThreadManager()
        tm.shutdown()
        for (lane in ExecutionLane.values()) {
            try {
                tm.scopeFor(lane)
                fail("scopeFor($lane) debía lanzar tras shutdown()")
            } catch (expected: IllegalStateException) {
                // fail-fast de mejor esfuerzo, documentado en ThreadManager
            }
        }
    }

    @Test
    fun `shutdown is idempotent`() {
        val tm = ThreadManager()
        tm.shutdown()
        tm.shutdown()
        tm.shutdown()
    }

    @Test
    fun `when ANY concurrent shutdown call returns, every scope is already cancelled`() {
        repeat(50) {
            val tm = ThreadManager()
            val scopes = ExecutionLane.values().map { tm.scopeFor(it) }
            val callers = 8
            val start = CountDownLatch(1)
            val done = CountDownLatch(callers)
            val violations = AtomicInteger(0)
            repeat(callers) {
                Thread {
                    start.await()
                    tm.shutdown()
                    // Postcondición: al volver de CUALQUIER llamada, todo está cancelado
                    // (antes, un 2º llamador concurrente volvía mientras el 1º aún cancelaba).
                    if (scopes.any { it.isActive }) violations.incrementAndGet()
                    done.countDown()
                }.start()
            }
            start.countDown()
            assertTrue("los llamadores deben terminar (sin interbloqueo)", done.await(10, TimeUnit.SECONDS))
            assertEquals(0, violations.get())
        }
    }

    @Test
    fun `work launched on a scope obtained before shutdown never runs after it`() {
        val tm = ThreadManager()
        val scope = tm.scopeFor(ExecutionLane.BACKGROUND)
        tm.shutdown()
        val ran = AtomicBoolean(false)
        val job = scope.launch { ran.set(true) } // TOCTOU benigno: el Job nace cancelado
        runBlocking { job.join() }
        assertTrue(job.isCancelled)
        assertFalse("el bloque no debe ejecutarse en un scope ya cancelado", ran.get())
    }

    @Test
    fun `dedicated lanes run on their own named daemon thread`() {
        val tm = ThreadManager()
        try {
            for ((lane, prefix) in listOf(
                ExecutionLane.AUDIO to "eliner-audio-",
                ExecutionLane.DSP to "eliner-dsp-",
                ExecutionLane.RENDER to "eliner-render-",
            )) {
                val name = arrayOfNulls<String>(1)
                val daemon = AtomicBoolean(false)
                val latch = CountDownLatch(1)
                tm.scopeFor(lane).launch {
                    name[0] = Thread.currentThread().name
                    daemon.set(Thread.currentThread().isDaemon)
                    latch.countDown()
                }
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertTrue("lane $lane en hilo '${name[0]}'", name[0]!!.startsWith(prefix))
                assertTrue(daemon.get())
            }
        } finally {
            tm.shutdown()
        }
    }
}
