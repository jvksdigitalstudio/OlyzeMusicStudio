package com.yeivikas.olyze.eliner.composition

import android.app.Application
import com.yeivikas.olyze.eliner.api.EliNerEngine
import com.yeivikas.olyze.eliner.api.EngineInitState
import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.midi.EliNerMidiApi
import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import com.yeivikas.olyze.eliner.bridge.AudioCommandDispatcher
import com.yeivikas.olyze.eliner.bridge.EliNerAudioBridge
import com.yeivikas.olyze.eliner.bridge.MidiOutputBridge
import com.yeivikas.olyze.eliner.bridge.MidiToSynthBridge
import com.yeivikas.olyze.eliner.diagnostics.LoggerService
import com.yeivikas.olyze.eliner.events.EventBus
import com.yeivikas.olyze.eliner.modules.midi.MidiFoundationModule
import com.yeivikas.olyze.eliner.services.DeviceCapabilityManager
import com.yeivikas.olyze.eliner.services.PerformanceProfileManager
import com.yeivikas.olyze.eliner.services.ThreadManager
import com.yeivikas.olyze.eliner.services.TimeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Implementación de [EliNerEngine] y raíz de composición del motor
 * (ADR 0027; origen: `AppServices`, Fase 1 de estabilización, Objetivo H).
 *
 * ## Responsabilidad única
 *
 * Construir, arrancar y apagar la infraestructura de audio/MIDI en el
 * orden correcto. No tiene estado de UI (BPM, play/record, teclado), no
 * tiene comandos de transporte y no sabe qué es un teclado en pantalla.
 *
 * ## Por qué vive en EliNer y no en la app
 *
 * Antes de ADR 0027 esta clase vivía en `:app` e importaba once clases
 * internas de EliNer (`bridge`, `services`, `diagnostics`, `events`,
 * `modules.midi`), contradiciendo la regla documentada de que `:app` solo
 * depende de la API pública. El conocimiento de *cómo se ensambla* el
 * motor — qué bus de eventos, qué ejecutor de tareas, qué orden de
 * apagado — es conocimiento del motor: cualquier otra app del ecosistema
 * que lo use debe obtenerlo gratis, sin copiarlo. Ahora la app solo ve
 * [EliNerEngine]; esta clase es `internal` al módulo y no es alcanzable
 * desde fuera.
 *
 * ## Límite honesto (documentado, no ocultado)
 *
 * El ideal completo vive en una subclase de `android.app.Application`,
 * creada una sola vez por proceso: sobreviviría a la recreación del
 * `ViewModel`/`Activity` y podría compartirse con un segundo `ViewModel`
 * (Mixer, Sequencer). Hoy el `ViewModel` principal sigue siendo quien
 * crea la instancia (vía `EliNerEngineFactory`). Mover esa creación a una
 * `Application` custom exige tocar `AndroidManifest.xml` y no se puede
 * verificar por compilación en este entorno; con la fábrica actual ese
 * cambio es de una línea en la app y no requiere tocar EliNer.
 */
internal class DefaultEliNerEngine(context: Application) : EliNerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Device capability → performance profile (Fase 6 §14-15) ──
    private val performanceProfileManager =
        PerformanceProfileManager(DeviceCapabilityManager(context))

    // ── Audio engine — ver AudioCommandDispatcher para el porqué del
    //    wrapping (Objetivo A/D de esta fase). ──
    private val audioDispatcher = AudioCommandDispatcher(EliNerAudioBridge.getInstance())
    override val audio: EliNerAudioApi = audioDispatcher

    // ── MIDI Foundation (entrada + salida — ver MidiOutputBridge para el
    //    porqué de "una sola implementación", no dos). ──
    private val midiThreadManager = ThreadManager()
    private val midiEventBus = EventBus()
    private val midiLogger = LoggerService()
    private val midiTimeProvider = TimeService()
    override val midi: EliNerMidiApi = MidiFoundationModule.create(
        context = context,
        eventBus = midiEventBus,
        logger = midiLogger,
        taskExecutor = midiThreadManager,
        timeProvider = midiTimeProvider,
    )
    private val midiOutputBridge = MidiOutputBridge(
        midi = midi,
        timeProvider = midiTimeProvider,
        scope = scope,
    )
    override val midiOutput: MidiOutputApi = midiOutputBridge

    private val midiToSynth = MidiToSynthBridge(audio)
    override val externalActiveNotes: StateFlow<Set<Int>> = midiToSynth.externalActiveNotes

    // ── Arranque asíncrono, con estado observable (Fase 1.1 §15) ─────────
    //
    // **El problema que corrige.** `MainViewModel.init { services.start() }`
    // corre en el hilo que construye el ViewModel — en el flujo real de
    // Android (`viewModels()`/`ViewModelProvider` desde
    // `MainActivity.onCreate()`), eso es el hilo principal. La `start()`
    // ANTERIOR llamaba a `audio.start(profile)` — que internamente bloquea
    // (`AudioCommandDispatcher.dispatchBlocking`, `executor.submit(...).get()`)
    // hasta que el motor nativo termina `openStream()` → DSP graph →
    // `requestStart()` — de forma completamente síncrona, en ese mismo
    // hilo. En cada arranque de la app, eso bloqueaba el hilo principal
    // durante toda la negociación real con el HAL de audio de Android:
    // exactamente el defecto que describe el prompt de esta fase.
    //
    // **La solución.** `start()` ya NO bloquea al llamador. Lanza el
    // trabajo real en [scope] (que esta clase ya posee y cancela en
    // [shutdown] — no una corrutina huérfana sin dueño) y publica su
    // progreso en [state], observable por la UI sin bloquear nada.
    private val _initState = MutableStateFlow<EngineInitState>(EngineInitState.NotStarted)
    override val state: StateFlow<EngineInitState> = _initState.asStateFlow()

    // Ownership explícito del job de arranque — es lo que permite que
    // [shutdown] sepa qué cancelar en vez de dejarlo flotando sin dueño
    // (la violación exacta que el prompt de esta fase prohíbe: "no
    // esconder el problema con simplemente lanzar otra coroutine sin
    // definir ownership").
    private var startJob: Job? = null

    /**
     * Arranca el motor de audio (con el perfil de rendimiento recomendado
     * para este dispositivo) y la MIDI Foundation (descubrimiento de
     * dispositivos + consumer registrado). Llamar exactamente una vez.
     *
     * No bloquea al llamador — el trabajo real corre en [scope]; el
     * resultado se observa vía [state] (`Starting` → `Ready`/`Failed`).
     *
     * ## Timeout
     *
     * Si `audio.start()` no resuelve en [kStartTimeoutMs], [state]
     * pasa a `Failed` igualmente — un dispositivo de audio que nunca
     * responde no debe dejar la UI esperando indefinidamente un estado
     * `Starting` que nunca cambia. El intento subyacente en el motor
     * nativo no se aborta de forma forzosa en ese instante (ver
     * "Límite honesto de cancelación" más abajo) — lo que se garantiza es
     * que la UI deja de esperar y puede reaccionar.
     *
     * ## Límite honesto de cancelación (documentado, no ocultado)
     *
     * [startJob] es cancelable (ver [shutdown]) y la llamada bloqueante
     * real (`audio.start(profile)`, ejecutando dentro de [runInterruptible])
     * SÍ responde a esa cancelación — `runInterruptible` interrumpe
     * (`Thread.interrupt()`) el hilo que está bloqueado en
     * `Future.get()` dentro de `AudioCommandDispatcher.dispatchBlocking()`,
     * que YA captura `InterruptedException` explícitamente (ver esa
     * clase) y devuelve `false` sin propagar. Eso hace que ESTA
     * corrutina deje de esperar de inmediato. Lo que NO garantiza: que
     * la llamada nativa `delegate.start(profile)` en sí, que ya fue
     * encolada y puede estar ejecutándose en el hilo único
     * `eliner-audio-commands`, se detenga a mitad de camino — interrumpir
     * al hilo que ESPERA el resultado no cancela el trabajo que YA se le
     * entregó a otro hilo. Esto es seguro de todas formas, no por
     * casualidad: cualquier `stop()`/`close()` posterior encolado en el
     * mismo `AudioCommandDispatcher` (ver [shutdown]) se ejecuta
     * DESPUÉS de ese `start()` en el mismo hilo único, por el mismo
     * contrato FIFO ya verificado en ADR 0014/0015 — así que, sin
     * importar si el motor nativo terminó de arrancar antes o después de
     * que esta corrutina se rindiera, la secuencia de apagado real que
     * seguirá lo alcanza y lo cierra correctamente, en orden.
     */
    override fun start() {
        check(_initState.value == EngineInitState.NotStarted) {
            "EliNerEngine.start() debe llamarse exactamente una vez (estado actual: ${_initState.value})"
        }
        _initState.value = EngineInitState.Starting
        startJob = scope.launch {
            try {
                val profile = performanceProfileManager.applyRecommended()
                val started = withTimeoutOrNull(kStartTimeoutMs) {
                    runInterruptible(Dispatchers.IO) { audio.start(profile) }
                }
                // `when` como EXPRESIÓN (exhaustiva sobre Boolean?): un único punto
                // de asignación al estado, sin ramas que asignen por separado.
                _initState.value = when (started) {
                    null -> EngineInitState.Failed(
                        "timeout arrancando el motor de audio (>${kStartTimeoutMs}ms) — ver audio.lastError/xrunCount",
                    )
                    false -> EngineInitState.Failed(
                        "el motor de audio rechazó el arranque — ver audio.lastError",
                    )
                    true -> {
                        midi.registerConsumer(midiToSynth.consumer)
                        midi.start()
                        EngineInitState.Ready
                    }
                }
            } catch (e: CancellationException) {
                throw e // cancelación cooperativa real (p. ej. shutdown() durante el arranque) — no ocultar, no reportar como Failed.
            } catch (t: Throwable) {
                _initState.value = EngineInitState.Failed(t.message ?: t::class.simpleName ?: "fallo desconocido durante el arranque")
            }
        }
    }

    /**
     * Apaga todo, en el orden correcto (Objetivo D / Crítico 3 de esta
     * fase): primero toda fuente de comandos MIDI, después el motor de
     * audio — así ningún comando puede llegar a un motor ya destruido.
     * Ver el comentario que tenía `MainViewModel.onCleared()` antes de
     * esta extracción para el detalle completo del porqué de este orden.
     *
     * ## Lifecycle-safe frente a un arranque en curso (§15)
     *
     * Si [start] todavía está `Starting` cuando esto se llama (p. ej. el
     * usuario sale de la app de inmediato), [startJob] se cancela —
     * NO se espera (`join`) a que termine, para no reintroducir el mismo
     * bloqueo del hilo llamador que esta sección existe para eliminar.
     * Ver el doc de [start], sección "Límite honesto de cancelación",
     * para exactamente qué garantiza esa cancelación y por qué el resto
     * de esta secuencia de apagado sigue siendo segura de todas formas
     * (el contrato FIFO de [AudioCommandDispatcher] ya se encarga de que
     * `stop()`/`close()` alcancen al motor después de cualquier `start()`
     * que haya quedado en vuelo, sin importar el resultado de esa
     * cancelación).
     */
    override fun shutdown() {
        startJob?.cancel()
        midiOutputBridge.close()
        midi.stop()
        midi.unregisterConsumer(midiToSynth.consumer)
        midi.shutdown()
        midiThreadManager.shutdown()
        audio.allNotesOff()
        audio.stop()
        audioDispatcher.close()
        scope.cancel()
    }

    private companion object {
        // Un dispositivo de audio que no responde a openStream()/
        // requestStart() en este margen se trata como fallo de arranque
        // — ver doc de [start]. 5s es generoso frente al caso normal
        // (decenas de ms) y consistente con timeouts típicos de
        // inicialización de hardware en Android; no hay una medición
        // real del percentil 99 de este dispositivo/HAL específico
        // disponible en este entorno — valor de partida razonable,
        // documentado como tal, no una medición.
        const val kStartTimeoutMs = 5_000L
    }
}
