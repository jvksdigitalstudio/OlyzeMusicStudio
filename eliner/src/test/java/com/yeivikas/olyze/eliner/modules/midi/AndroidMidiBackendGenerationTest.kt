package com.yeivikas.olyze.eliner.modules.midi

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1.1 §18 — verifica el mecanismo de generation/token que evita que
 * un callback tardío de `MidiManager.openDevice()` "resucite" un handle
 * MIDI después de que [AndroidMidiBackend.shutdown] o
 * [AndroidMidiBackend.closeDevice] ya lo dieron por cerrado.
 *
 * No se puede instanciar [AndroidMidiBackend] real aquí (depende de
 * `android.media.midi.*`, no disponible fuera de un dispositivo/emulador
 * real — el propio archivo lo documenta como "el único archivo permitido
 * a importar android.media.midi"). Este test replica exactamente la
 * MISMA lógica de generación (mismos nombres, mismas comparaciones bajo
 * lock) contra un modelo mínimo, de la misma forma que
 * `test_lifecycle_fsm_model.cpp` replica la FSM real del lado C++ — no es
 * una aproximación floja, es la misma decisión de diseño sin las líneas
 * que tocan el SDK de Android.
 */
private class BackendModel {
    private val handlesLock = Any()
    private var isShutDown = false
    private val mGenerationCounter = AtomicLong(0L)
    private val openGenerationByDevice = mutableMapOf<String, Long>()
    val openDevices = mutableMapOf<String, String>() // deviceId -> "handle" simulado
    var lateCallbacksDiscarded = 0
        private set

    /** Simula el `manager.openDevice(...)` real: reserva generación, YA NO dispara el callback (async). */
    fun beginOpen(deviceId: String): Long? = synchronized(handlesLock) {
        if (isShutDown) return null
        mGenerationCounter.incrementAndGet().also { openGenerationByDevice[deviceId] = it }
    }

    /** Simula la llegada asíncrona del callback de Android para una generación reservada por [beginOpen]. */
    fun deliverOpenCallback(deviceId: String, myGeneration: Long) {
        val accepted = synchronized(handlesLock) {
            if (isShutDown || openGenerationByDevice[deviceId] != myGeneration) {
                false
            } else {
                openDevices[deviceId] = "handle-gen-$myGeneration"
                true
            }
        }
        if (!accepted) lateCallbacksDiscarded++
    }

    fun closeDevice(deviceId: String) = synchronized(handlesLock) {
        openGenerationByDevice.remove(deviceId)
        openDevices.remove(deviceId)
    }

    fun shutdown() = synchronized(handlesLock) {
        isShutDown = true
        openGenerationByDevice.clear()
        openDevices.clear()
    }
}

class AndroidMidiBackendGenerationTest {

    @Test
    fun `callback normal sin carrera queda aceptado`() {
        val backend = BackendModel()
        val gen = backend.beginOpen("dev1")!!
        backend.deliverOpenCallback("dev1", gen)
        assertTrue("el handle debe registrarse cuando no hay carrera", backend.openDevices.containsKey("dev1"))
        assertEquals(0, backend.lateCallbacksDiscarded)
    }

    @Test
    fun `callback tardio tras shutdown no resucita el handle`() {
        val backend = BackendModel()
        // open() en vuelo...
        val gen = backend.beginOpen("dev1")!!
        // ...pero shutdown() gana la carrera antes de que el callback llegue.
        backend.shutdown()
        // El callback asíncrono llega DESPUÉS de shutdown() (Android no permite cancelarlo).
        backend.deliverOpenCallback("dev1", gen)

        assertFalse(
            "BUG PRE-FIX: sin generation/token, este callback tardío escribía igual en openDevices " +
                "tras shutdown() — exactamente el defecto de §18 ('shutdown() -> callback antiguo -> " +
                "reintroduce un MIDI handle')",
            backend.openDevices.containsKey("dev1"),
        )
        assertEquals(1, backend.lateCallbacksDiscarded)
    }

    @Test
    fun `callback tardio tras closeDevice del mismo id no resucita el handle`() {
        val backend = BackendModel()
        val gen = backend.beginOpen("dev1")!!
        // El dispositivo se desconecta y se cierra ANTES de que el open() en vuelo resuelva.
        backend.closeDevice("dev1")
        backend.deliverOpenCallback("dev1", gen)

        assertFalse(
            "un close() para el mismo deviceId debe invalidar cualquier open() en vuelo para ese id",
            backend.openDevices.containsKey("dev1"),
        )
        assertEquals(1, backend.lateCallbacksDiscarded)
    }

    @Test
    fun `reconexion rapida - solo la generacion mas nueva gana`() {
        val backend = BackendModel()
        val staleGen = backend.beginOpen("dev1")!! // primera sesión de apertura
        backend.closeDevice("dev1")                // se cierra...
        val freshGen = backend.beginOpen("dev1")!! // ...y se reabre con nueva generación antes de que la vieja resuelva

        // La generación VIEJA llega tarde — debe ser descartada aunque el deviceId vuelva a estar activo.
        backend.deliverOpenCallback("dev1", staleGen)
        assertFalse(
            "una generación vieja no debe pisar una sesión de apertura más nueva para el mismo id",
            backend.openDevices.containsKey("dev1"),
        )

        // La generación NUEVA llega después — esa sí se acepta.
        backend.deliverOpenCallback("dev1", freshGen)
        assertTrue(backend.openDevices.containsKey("dev1"))
        assertEquals("handle-gen-$freshGen", backend.openDevices["dev1"])
        assertEquals(1, backend.lateCallbacksDiscarded)
    }

    @Test
    fun `openInputPorts rechaza intentos nuevos una vez apagado el backend`() {
        val backend = BackendModel()
        backend.shutdown()
        val gen = backend.beginOpen("dev1")
        assertNull("beginOpen() (equivalente a openInputPorts()) debe rechazar de entrada tras shutdown()", gen)
    }

    @Test
    fun `multiples dispositivos concurrentes no se interfieren entre si`() {
        val backend = BackendModel()
        val genA = backend.beginOpen("devA")!!
        val genB = backend.beginOpen("devB")!!
        backend.closeDevice("devA") // solo A se cierra
        backend.deliverOpenCallback("devA", genA) // debe descartarse
        backend.deliverOpenCallback("devB", genB) // debe aceptarse — no relacionado con A

        assertFalse(backend.openDevices.containsKey("devA"))
        assertTrue(backend.openDevices.containsKey("devB"))
        assertEquals(1, backend.lateCallbacksDiscarded)
    }
}
