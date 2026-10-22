package com.yeivikas.olyze.eliner.bridge

import android.util.Log
import com.yeivikas.olyze.eliner.api.audio.DspModuleType
import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.audio.EngineErrorFlags
import com.yeivikas.olyze.eliner.api.audio.PerformanceProfile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow

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
 *
 * ## Cola acotada, backpressure y coalescing (Fase 1.1 §14)
 *
 * **El problema que corrige.** `Executors.newSingleThreadExecutor()` (la
 * implementación anterior a esta sección) respalda su único hilo con un
 * `LinkedBlockingQueue<Runnable>` **sin límite de capacidad** — cada
 * `execute()`/`submit()` que llega mientras el hilo dispatcher está
 * ocupado se acumula indefinidamente en memoria Java, sin ningún tope.
 * Con el tráfico actual (nota/CC básicos disparados por toques en
 * pantalla) esto no se manifiesta. Se vuelve peligroso en cuanto el DAW
 * incorpore automation, parameter modulation de alta frecuencia o varios
 * dispositivos MIDI físicos enviando CC continuo — un productor más
 * rápido que el consumidor ya no tiene ningún freno.
 *
 * **No todos los comandos son iguales — y por eso no se tratan igual.**
 * Esta clase distingue dos categorías, según la semántica real del dato
 * que transportan:
 *
 * 1. **Eventos discretos y ordenados** — `noteOn`/`noteOff`/`allNotesOff`/
 *    `sendCC`/`setPitchBend`/`start`/`stop`/`clearErrors`/
 *    `insertModule`/`removeModule`/`moveModule`. Cada llamada es un
 *    evento propio con significado individual — perder o fusionar dos
 *    `noteOn` distintos sería perder una nota real. Van al
 *    [orderedExecutor], una cola **acotada** (ver [kOrderedQueueCapacity]
 *    — mismo valor que `EngineCommandQueue` del lado nativo,
 *    `CommandQueue.h`, por consistencia arquitectónica deliberada, no
 *    coincidencia) con backpressure real: si se llena, la llamada
 *    entrante se rechaza (`RejectedExecutionException`, la misma vía ya
 *    usada para el rechazo post-`close()`) en vez de crecer sin límite.
 *
 * 2. **Parámetros de valor último ("latest-value")** —
 *    `setMasterVolume`/`setReverbMix`/`setDelayMix`/`setDelayTime`/
 *    `setDelayFeedback`/`setModuleParameter`. Estos representan "el valor
 *    actual de un control continuo", no un evento discreto — si un
 *    fader se mueve 50 veces en 100ms, lo único que el motor de audio
 *    necesita aplicar es el ÚLTIMO valor; las 49 llamadas intermedias no
 *    tienen ningún efecto audible distinguible si nunca llegan a
 *    aplicarse. [pendingParams] retiene, por clave de parámetro
 *    ([ParamTarget]), únicamente el valor más reciente recibido —
 *    coalescing real, no solo un límite de cola — y [flushScheduled]
 *    garantiza que como máximo UNA tarea de "aplicar pendientes" esté
 *    encolada en el [orderedExecutor] a la vez, sin importar cuántas
 *    llamadas a `setXxx()` lleguen mientras tanto: eso es lo que evita
 *    que una ráfaga de parámetros desplace o retrase eventos de nota que
 *    llegan intercalados (siguen siendo, como máximo, un único hueco en
 *    la cola FIFO, no N). Cuando esa tarea corre, aplica TODOS los
 *    parámetros pendientes en ese instante en un solo lote — batching
 *    real, no una tarea por parámetro.
 *
 * **Por qué no una cola de prioridad real.** El resto del sistema (DSP
 * chain, MIDI) depende de que `insertModule`→`moveModule`→
 * `removeModule` para el mismo slot se apliquen en el orden exacto de
 * llamada (ver ADR 0016, §16) — introducir reordenamiento por prioridad
 * rompería esa garantía FIFO para ganar algo que el mecanismo de
 * coalescing de arriba ya resuelve de la forma que realmente importa:
 * evitar que un parámetro de alta frecuencia ACAPARE la cola, no
 * reordenar eventos discretos entre sí. Si en el futuro aparece una
 * categoría de evento que sí necesite saltarse la fila (p. ej. un
 * "panic"/all-notes-off de emergencia), ese es un caso nuevo a diseñar
 * explícitamente, no una generalización prematura de este mecanismo.
 *
 * **Decisión de alcance, documentada, no oculta:** los rechazos por cola
 * llena (bounded submission) se registran en Logcat (mismo canal que el
 * rechazo por cierre) pero NO se contabilizan todavía en un contador
 * público observable equivalente a `droppedCommands` (que sí existe,
 * pero cuenta descartes del lado NATIVO — un descarte aquí, en Kotlin,
 * nunca llega a tocar el SPSC). Añadir esa métrica implica extender
 * [EliNerAudioApi] (contrato público, con más de un implementador) —
 * fuera del alcance mínimo de este hardening; queda anotado como
 * follow-up explícito en el ADR de esta fase, no como omisión.
 */
class AudioCommandDispatcher(
    private val delegate: EliNerAudioApi,
) : EliNerAudioApi {

    private val orderedExecutor: ExecutorService = ThreadPoolExecutor(
        /* corePoolSize = */ 1,
        /* maximumPoolSize = */ 1,
        /* keepAliveTime = */ 0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(kOrderedQueueCapacity),
        { runnable -> Thread(runnable, "eliner-audio-commands").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(), // explícito aunque sea el default de ThreadPoolExecutor:
                                           // lanza RejectedExecutionException tanto por cola llena
                                           // como por shutdown() — un único camino de rechazo, ya
                                           // manejado en dispatchAsync()/dispatchBlocking()/submitOrdered().
    )

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

    // ── Master / FX controls — parámetros "latest-value", coalescibles
    //    (§14 — ver doc de clase). Cada llamada actualiza únicamente el
    //    valor pendiente para su [ParamTarget]; el motor de audio solo
    //    llega a ver el último valor de cada uno por lote aplicado. ──
    override fun setMasterVolume(volume: Float) = setParameter(ParamTarget.MasterVolume, volume)
    override fun setReverbMix(mix: Float) = setParameter(ParamTarget.ReverbMix, mix)
    override fun setReverbRoom(room: Float) = setParameter(ParamTarget.ReverbRoom, room)
    override fun setReverbDamp(damp: Float) = setParameter(ParamTarget.ReverbDamp, damp)
    override fun setDelayMix(mix: Float) = setParameter(ParamTarget.DelayMix, mix)
    override fun setDelayTime(seconds: Float) = setParameter(ParamTarget.DelayTime, seconds)
    override fun setDelayFeedback(feedback: Float) = setParameter(ParamTarget.DelayFeedback, feedback)

    // ── Dynamic FX chain ──
    override fun insertModule(slot: Int, type: DspModuleType): Boolean = dispatchBlocking(fallback = false) { delegate.insertModule(slot, type) }
    override fun removeModule(slot: Int): Boolean = dispatchBlocking(fallback = false) { delegate.removeModule(slot) }
    override fun moveModule(fromSlot: Int, toSlot: Int): Boolean = dispatchBlocking(fallback = false) { delegate.moveModule(fromSlot, toSlot) }
    override fun setModuleParameter(slot: Int, paramId: Int, value: Float) = setParameter(ParamTarget.ModuleParameter(slot, paramId), value)

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
     * Tras [close] — o si [orderedExecutor] está a capacidad (§14) —
     * `execute()` lanza [RejectedExecutionException], capturada
     * específicamente aquí (no `Throwable` genérico): ambos son casos
     * esperados y documentados, no bugs. El descarte es silencioso hacia
     * el llamador (mismo contrato "best-effort delivery" que
     * `CommandQueue.h` ya documenta para el overflow del lado nativo) —
     * solo se registra en Logcat.
     */
    private fun dispatchAsync(block: () -> Unit) {
        submitOrdered(block)
    }

    /**
     * Igual que [dispatchAsync], pero devuelve si la tarea quedó
     * realmente encolada — usado internamente por [scheduleFlushIfNeeded]
     * (§14), que necesita saber si debe revertir su propia reserva de
     * "ya hay un flush en camino" cuando el envío es rechazado (cola
     * llena o dispatcher cerrado), para no dejar el coalescing
     * permanentemente bloqueado. Los llamadores fire-and-forget
     * ordinarios (`noteOn`, etc., vía [dispatchAsync]) no necesitan este
     * valor: para ellos, un rechazo ya es terminal y solo se loguea.
     */
    private fun submitOrdered(block: () -> Unit): Boolean = try {
        orderedExecutor.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "Comando de audio falló en eliner-audio-commands", t)
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        Log.w(TAG, "Comando descartado: cola llena o dispatcher cerrado (${e.message})")
        false
    }

    /**
     * Encola [block] y espera su resultado en el hilo llamador. Tras
     * [close] — o si [orderedExecutor] está a capacidad (§14) —
     * `submit()` lanza [RejectedExecutionException] — capturada aquí
     * específicamente, devolviendo [fallback] (nunca invoca [block],
     * nunca llega a JNI/SPSC). Si el hilo llamador es interrumpido
     * mientras espera, se re-marca la interrupción (buena práctica
     * estándar de Java/Kotlin para `InterruptedException`, no relacionado
     * con el cierre del dispatcher) y también se devuelve [fallback].
     * Cualquier OTRA excepción — un fallo real dentro de [block] — se
     * deja propagar tal cual: quien llama a un método bloqueante espera
     * el resultado y debe enterarse si algo falló de verdad (ver doc de
     * clase).
     */
    private fun <T> dispatchBlocking(fallback: T, block: () -> T): T = try {
        orderedExecutor.submit(Callable { block() }).get()
    } catch (e: RejectedExecutionException) {
        Log.w(TAG, "Comando bloqueante descartado: cola llena o dispatcher cerrado, devolviendo valor por defecto (${e.message})")
        fallback
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Log.w(TAG, "Comando bloqueante interrumpido mientras esperaba, devolviendo valor por defecto", e)
        fallback
    }

    // ── Coalescing de parámetros "latest-value" (§14) ────────────────────
    // (Las claves de coalescencia — qué parámetro y cómo aplicarlo — están en ParamTarget.kt.)
    // ConcurrentHashMap: escrito desde CUALQUIER hilo productor (UI,
    // eliner-dsp) vía setParameter(), leído/vaciado únicamente desde
    // dentro de orderedExecutor (drainAndApplyPendingParams(), que SIEMPRE
    // corre en el hilo eliner-audio-commands) — necesita ser thread-safe
    // en la escritura concurrente; no necesita ningún lock adicional
    // porque ConcurrentHashMap ya lo garantiza para put()/remove(key,value).
    private val pendingParams = ConcurrentHashMap<ParamTarget, Float>()

    // Garantiza como máximo UNA tarea de flush encolada a la vez — el
    // corazón del coalescing: N llamadas a setParameter() mientras un
    // flush ya está pendiente o ejecutándose NO encolan N tareas nuevas,
    // solo actualizan pendingParams (ver setParameter()).
    private val flushScheduled = AtomicBoolean(false)

    private fun setParameter(target: ParamTarget, value: Float) {
        pendingParams[target] = value // put() simple: la llamada más reciente para esta clave siempre gana.
        scheduleFlushIfNeeded()
    }

    private fun scheduleFlushIfNeeded() {
        if (!flushScheduled.compareAndSet(false, true)) return // ya hay un flush reservado — esta llamada solo actualizó el valor pendiente, nada más que hacer.
        val accepted = submitOrdered {
            flushScheduled.set(false) // liberar ANTES de drenar: si una nueva
                                       // llamada a setParameter() llega mientras
                                       // este flush está corriendo, debe poder
                                       // reservar un flush SIGUIENTE en vez de
                                       // creer que el actual todavía la cubre.
            drainAndApplyPendingParams()
        }
        if (!accepted) {
            // Rechazado (cola llena o dispatcher cerrado) — nunca llegó a
            // encolarse, así que nadie va a limpiar flushScheduled por
            // nosotros. Revertir la reserva es lo que evita dejar el
            // coalescing permanentemente bloqueado (todo setParameter()
            // futuro vería flushScheduled==true para siempre y nunca
            // volvería a intentar encolar un flush real).
            flushScheduled.set(false)
        }
    }

    // Audio-command-thread-only (siempre corre dentro de orderedExecutor).
    // Aplica, en un solo lote, todos los valores pendientes en el instante
    // en que se lee el snapshot — batching real, no una tarea por
    // parámetro. `remove(key, value)` (la forma condicional de
    // ConcurrentHashMap) solo elimina la entrada si sigue siendo EXACTAMENTE
    // el valor que este snapshot vio — si una llamada más nueva a
    // setParameter() escribió un valor distinto entre el snapshot y esta
    // línea, esa entrada se conserva para el PRÓXIMO flush en vez de
    // perderse (evita la ventana de carrera "snapshot leído, valor más
    // nuevo pisado y perdido").
    private fun drainAndApplyPendingParams() {
        val batch = pendingParams.entries.toList()
        for ((target, value) in batch) {
            pendingParams.remove(target, value)
            target.apply(delegate, value)
        }
    }

    /**
     * Detiene el hilo dedicado. Segura de llamar más de una vez (ver doc
     * de clase — `ExecutorService.shutdown()` es idempotente). Los
     * comandos ya encolados antes de esta llamada se ejecutan igual
     * (`shutdown()`, no `shutdownNow()`) — solo se rechazan los que
     * lleguen después, con el contrato descrito en el doc de clase. No se
     * fuerza un flush final de [pendingParams]: cualquier parámetro
     * pendiente sin flush ya encolado en el momento de [close] sigue el
     * mismo contrato "best-effort" que el resto de comandos descartados
     * por cierre — el motor de audio de todas formas está dejando de
     * existir, no hay ningún callback ni UI esperando ese valor.
     */
    fun close() {
        closed.set(true)
        orderedExecutor.shutdown()
    }

    private companion object {
        const val TAG = "AudioCommandDispatcher"

        // Capacidad de la cola acotada del hilo dispatcher (§14). Mismo
        // valor que `EngineCommandQueue` del lado nativo (256 — ver
        // `eliner/include/eliner/core/CommandQueue.h`), a propósito: ambas
        // colas existen para el mismo motivo (absorber una ráfaga de
        // comandos entre dos drenajes consecutivos del consumidor) y no
        // hay ninguna razón arquitectónica para que una sea más generosa
        // que la otra — si 256 es "generous headroom above worst-case
        // per-callback traffic" para el lado nativo (ver comentario
        // original en CommandQueue.h), lo mismo aplica aquí, un nivel
        // arriba en la misma tubería.
        const val kOrderedQueueCapacity = 256
    }
}
