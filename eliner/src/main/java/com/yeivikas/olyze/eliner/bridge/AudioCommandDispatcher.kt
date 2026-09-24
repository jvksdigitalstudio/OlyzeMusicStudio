package com.yeivikas.olyze.eliner.bridge

import android.util.Log
import com.yeivikas.olyze.eliner.api.DspModuleType
import com.yeivikas.olyze.eliner.api.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.EngineErrorFlags
import com.yeivikas.olyze.eliner.services.PerformanceProfile
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializa TODA mutación del motor de audio — lifecycle (`start`/`stop`) y
 * comandos en caliente (nota/CC/pitch-bend/parámetros/FX chain) — sobre un
 * único hilo físico dedicado, antes de que lleguen a [delegate] (la
 * implementación JNI real).
 *
 * ## El problema que corrige (Fase 1 de estabilización — Objetivo A)
 *
 * `SpscCommandQueue` (`eliner/include/eliner/core/CommandQueue.h`, el ring
 * buffer lock-free que el motor nativo usa para recibir comandos desde
 * Kotlin) documenta su contrato explícitamente: *"Producer: Control thread
 * ONLY"*. Antes de esta clase, ese contrato se violaba en la práctica: dos
 * hilos reales de Android llamaban a `EliNerAudioBridge`/JNI
 * concurrentemente sin ninguna serialización —
 *   - el hilo **UI** (`noteOn` disparado por un toque en el teclado en
 *     pantalla, vía `MainViewModel`);
 *   - el hilo **`eliner-dsp`** (`MidiRouter`, corriendo en su propio
 *     `ExecutionLane.DSP` — ver `ThreadManager.kt` — que despacha eventos
 *     MIDI externos hacia `MidiToSynthBridge.consumer`).
 *
 * Se demostró — con una prueba determinista de intercalación, no solo por
 * análisis — que esto puede perder comandos silenciosamente (una escritura
 * pisa a la otra en el mismo slot del ring buffer) sin que
 * `droppedCommands` lo refleje: ver
 * `eliner/src/test/cpp/core/test_command_queue_interleaving_proof.cpp`.
 *
 * ## La solución elegida (Opción A del ADR de esta fase, no Opción B)
 *
 * En vez de convertir `SpscCommandQueue` en una cola MPSC en C++ (que
 * exigiría operaciones CAS en cada `push()`, más costo y superficie de
 * bugs en el ring buffer realtime que hoy es correcto y ya está
 * verificado — ver `test_command_queue_spsc_baseline.cpp`), esta clase
 * serializa arriba, en Kotlin, ANTES de cruzar a JNI: un único
 * [ExecutorService] de un solo hilo (`eliner-audio-commands`) recibe todas
 * las llamadas, sin importar de qué hilo Android vengan, y las reenvía a
 * [delegate] en el orden en que llegaron a la cola interna del executor
 * (FIFO, garantizado por `java.util.concurrent` incluso con múltiples
 * hilos productores concurrentes). El resultado: `SpscCommandQueue` sigue
 * siendo SPSC — y ahora eso es literalmente cierto, porque solo UN hilo
 * físico real llega jamás a `pushCommand()`.
 *
 * Deliberadamente NO reutiliza `ThreadManager`/`ExecutionLane.AUDIO` (que
 * sí existe, sin usar, para exactamente este propósito): esta clase es
 * autocontenida a propósito, sin tocar ni depender de la implementación
 * interna de `ThreadManager` (§42 — no tocar código estable sin necesidad
 * estricta), y sin arrastrar la maquinaria de corrutinas para algo que
 * `java.util.concurrent` resuelve de forma más simple y directamente
 * testeable (ver `AudioCommandDispatcherTest.kt`).
 *
 * ## Métodos con retorno (`start`/`insertModule`/`removeModule`/`moveModule`/`getModuleType`)
 *
 * Se despachan igual (mismo hilo único, mismo orden FIFO respecto a los
 * demás comandos) pero bloqueando al hilo llamador hasta obtener el
 * resultado, vía `executor.submit(...).get()`. Esto es seguro — nunca se
 * llama desde el hilo de audio realtime (ver nota en `EliNerAudioBridge`/
 * el JNI: el callback de Oboe nunca pasa por aquí) — y barato: el trabajo
 * real que se espera es siempre una llamada JNI no bloqueante (solo
 * empuja al ring buffer). No cambia la semántica ya documentada en
 * [EliNerAudioApi]: esos métodos ya eran "el comando fue aceptado", no
 * "el comando ya se aplicó" (aplicación real ocurre async en el callback
 * de audio) — ver el doc de la interfaz.
 *
 * ## Contrato de cierre — corregido tras auditoría final de Fase 1
 *
 * Antes de este fix, `executor.execute(...)`/`executor.submit(...)`
 * estaban FUERA de cualquier manejo de rechazo: tras [close], una llamada
 * como `noteOff(...)` podía propagar `RejectedExecutionException`
 * directamente hacia quien llamó — por ejemplo, el hilo `eliner-dsp`
 * (`MidiRouter`) procesando un evento MIDI que llegó en el instante exacto
 * del apagado, o la UI liberando una tecla justo cuando `onCleared()`
 * corre. Eso violaba el propio principio que motivó esta clase: un
 * productor (UI/MIDI) nunca debe poder crashear ni comportarse de forma
 * impredecible por culpa del ciclo de vida del motor de audio.
 *
 * Contrato explícito, verificado en `AudioCommandDispatcherTest.kt`:
 * ```
 * ANTES DE close()
 *     └─> aceptar comandos, ejecutarlos en el único hilo dispatcher
 *
 * DESPUÉS DE close()
 *     └─> rechazar comandos
 *         └─> NO lanzar excepción al llamador
 *             └─> NO llegar al JNI (delegate.xxx() nunca se invoca)
 *                 └─> NO llegar al SPSC (consecuencia directa de lo anterior)
 * ```
 *
 * Mecanismo: se captura específicamente [RejectedExecutionException] — NO
 * un `catch (Throwable)` genérico, que ocultaría bugs reales sin
 * distinguirlos de un rechazo esperado por cierre — alrededor de la
 * llamada a `execute()`/`submit()` en sí (no solo del cuerpo de la tarea,
 * que es el error original: el rechazo ocurre AL ENCOLAR, antes de que la
 * tarea exista). `ExecutorService` ya garantiza, como parte de su propio
 * contrato documentado en la JDK, que esta comprobación de "¿shutdown?"
 * es atómica frente a `execute()`/`submit()` concurrentes — no hay ventana
 * donde una tarea sea aceptada silenciosamente después de `shutdown()`
 * sin que se sepa; reinventar esa sincronización con un flag propio y
 * chequeo manual (`check-then-act`) sería estrictamente más débil que la
 * garantía que la JDK ya provee. `close()` en sí (`executor.shutdown()`)
 * es idempotente y segura de llamar concurrentemente varias veces — mismo
 * contrato documentado de `ExecutorService.shutdown()` — así que no se
 * añade ninguna protección adicional para eso.
 *
 * Para los métodos bloqueantes (`start`/`insertModule`/`removeModule`/
 * `moveModule`/`getModuleType`), un rechazo tras `close()` no puede
 * "descartarse silenciosamente" sin más — el llamador espera un valor de
 * retorno. Cada uno declara su propio valor de repliegue semánticamente
 * correcto (`false` para las operaciones de FX chain — "no se pudo
 * insertar/quitar/mover porque el motor ya se apagó"; `DspModuleType.NONE`
 * para `getModuleType` — el mismo valor "vacío" que ya usa el resto del
 * contrato cuando no hay motor). Un fallo REAL dentro de [block] (no un
 * rechazo por cierre) SÍ se propaga hacia el llamador síncrono, envuelto
 * por `Future.get()` en `ExecutionException` — deliberado: quien llama a
 * un método bloqueante está esperando el resultado y debe enterarse si
 * algo falló de verdad, a diferencia de `dispatchAsync` (fire-and-forget),
 * donde propagar haría crashear a un llamador que nunca esperó una
 * respuesta.
 *
 * ## Ownership
 *
 * Esta clase POSEE su executor — [close] puede llamarse una o más veces
 * de forma segura (ver contrato de cierre arriba), típicamente desde
 * quien la construye (hoy, `MainViewModel.onCleared()`, indirectamente
 * vía `AppServices.shutdown()`), después de que ya no debieran llegar más
 * comandos de ningún origen conocido — aunque, tras este fix, aunque
 * llegara uno tarde, ya no compromete la estabilidad del llamador.
 */
class AudioCommandDispatcher(
    private val delegate: EliNerAudioApi,
) : EliNerAudioApi {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eliner-audio-commands").apply { isDaemon = true }
    }

    /** Observable/informativa — el mecanismo real de exclusión que
     *  previene comandos post-cierre es el propio `ExecutorService`
     *  rechazando `execute()`/`submit()` (ver doc de clase); este flag
     *  solo existe para que un llamador (o un test) pueda preguntar
     *  "¿ya está cerrado?" sin depender de provocar un rechazo. */
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    // ── Estado de solo lectura — StateFlow es thread-safe por diseño, y
    //    los getters nativos que lo respaldan (refreshStats()) solo leen
    //    atomics del lado C++ (ver AudioEngine.h) — no necesitan pasar por
    //    el executor: no mutan gEngine, y no hay ordenamiento que preservar
    //    respecto a los comandos, que sí lo necesitan. ──
    override val isRunning: StateFlow<Boolean> get() = delegate.isRunning
    override val sampleRate: StateFlow<Int> get() = delegate.sampleRate
    override val bufferSize: StateFlow<Int> get() = delegate.bufferSize
    override val cpuLoad: StateFlow<Float> get() = delegate.cpuLoad
    override val activeVoices: StateFlow<Int> get() = delegate.activeVoices
    override val droppedCommands: StateFlow<Long> get() = delegate.droppedCommands
    override val xrunCount: StateFlow<Int> get() = delegate.xrunCount
    override val lastError: StateFlow<EngineErrorFlags> get() = delegate.lastError
    override val maxChainSlots: Int get() = delegate.maxChainSlots

    override fun refreshStats() = delegate.refreshStats()

    // ── Lifecycle — serializado con el resto de comandos a propósito
    //    (Objetivo D: un único mecanismo serializa lifecycle Y comandos
    //    en caliente sobre el mismo hilo, así que start()/stop() quedan
    //    correctamente ordenados respecto a cualquier noteOn/CC en vuelo). ──
    override fun start(profile: PerformanceProfile): Boolean = dispatchBlocking(fallback = false) { delegate.start(profile) }
    override fun stop() = dispatchAsync { delegate.stop() }
    override fun clearErrors() = dispatchAsync { delegate.clearErrors() }

    // ── Note / MIDI-level control — el path de alta frecuencia que
    //    motivó este fix. ──
    override fun noteOn(channel: Int, note: Int, velocity: Int) = dispatchAsync { delegate.noteOn(channel, note, velocity) }
    override fun noteOff(channel: Int, note: Int) = dispatchAsync { delegate.noteOff(channel, note) }
    override fun allNotesOff() = dispatchAsync { delegate.allNotesOff() }
    override fun sendCC(channel: Int, cc: Int, value: Int) = dispatchAsync { delegate.sendCC(channel, cc, value) }
    override fun setPitchBend(channel: Int, semitones: Float) = dispatchAsync { delegate.setPitchBend(channel, semitones) }

    // ── Master / FX controls ──
    override fun setMasterVolume(volume: Float) = dispatchAsync { delegate.setMasterVolume(volume) }
    override fun setReverbMix(mix: Float) = dispatchAsync { delegate.setReverbMix(mix) }
    override fun setDelayMix(mix: Float) = dispatchAsync { delegate.setDelayMix(mix) }
    override fun setDelayTime(seconds: Float) = dispatchAsync { delegate.setDelayTime(seconds) }
    override fun setDelayFeedback(feedback: Float) = dispatchAsync { delegate.setDelayFeedback(feedback) }

    // ── Dynamic FX chain ──
    override fun insertModule(slot: Int, type: DspModuleType): Boolean = dispatchBlocking(fallback = false) { delegate.insertModule(slot, type) }
    override fun removeModule(slot: Int): Boolean = dispatchBlocking(fallback = false) { delegate.removeModule(slot) }
    override fun moveModule(fromSlot: Int, toSlot: Int): Boolean = dispatchBlocking(fallback = false) { delegate.moveModule(fromSlot, toSlot) }
    override fun setModuleParameter(slot: Int, paramId: Int, value: Float) = dispatchAsync { delegate.setModuleParameter(slot, paramId, value) }

    /**
     * Lectura de introspección — también serializada (no es un atomic del
     * lado nativo, es `mSlotTypesShadow`, un array plano — ver el doc de
     * `AudioEngine::getModuleType` — así que leerlo desde cualquier hilo
     * sin pasar por el mismo hilo único que lo escribe sería, en sí
     * mismo, una data race no atómica).
     */
    override fun getModuleType(slot: Int): DspModuleType = dispatchBlocking(fallback = DspModuleType.NONE) { delegate.getModuleType(slot) }

    /**
     * Encola [block] para ejecución fire-and-forget en el hilo único.
     * Tras [close], `execute()` lanza [RejectedExecutionException] —
     * capturada aquí específicamente (no `Throwable` genérico): un
     * rechazo por cierre es un caso esperado y documentado, no un bug.
     * El descarte es silencioso hacia el llamador (mismo contrato
     * "best-effort delivery" que `CommandQueue.h` ya documenta para el
     * overflow del lado nativo) — solo se registra en Logcat.
     */
    private fun dispatchAsync(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(TAG, "Comando de audio falló en eliner-audio-commands", t)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Comando descartado: dispatcher ya cerrado (${e.message})")
        }
    }

    /**
     * Encola [block] y espera su resultado en el hilo llamador. Tras
     * [close], `submit()` lanza [RejectedExecutionException] — capturada
     * aquí específicamente, devolviendo [fallback] (nunca invoca
     * [block], nunca llega a JNI/SPSC). Si el hilo llamador es
     * interrumpido mientras espera, se re-marca la interrupción (buena
     * práctica estándar de Java/Kotlin para `InterruptedException`, no
     * relacionado con el cierre del dispatcher) y también se devuelve
     * [fallback]. Cualquier OTRA excepción — un fallo real dentro de
     * [block] — se deja propagar tal cual: quien llama a un método
     * bloqueante espera el resultado y debe enterarse si algo falló de
     * verdad (ver doc de clase).
     */
    private fun <T> dispatchBlocking(fallback: T, block: () -> T): T = try {
        executor.submit(Callable { block() }).get()
    } catch (e: RejectedExecutionException) {
        Log.w(TAG, "Comando bloqueante descartado: dispatcher ya cerrado, devolviendo valor por defecto (${e.message})")
        fallback
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Log.w(TAG, "Comando bloqueante interrumpido mientras esperaba, devolviendo valor por defecto", e)
        fallback
    }

    /**
     * Detiene el hilo dedicado. Segura de llamar más de una vez (ver doc
     * de clase — `ExecutorService.shutdown()` es idempotente). Los
     * comandos ya encolados antes de esta llamada se ejecutan igual
     * (`shutdown()`, no `shutdownNow()`) — solo se rechazan los que
     * lleguen después, con el contrato descrito en el doc de clase.
     */
    fun close() {
        closed.set(true)
        executor.shutdown()
    }

    private companion object {
        const val TAG = "AudioCommandDispatcher"
    }
}
