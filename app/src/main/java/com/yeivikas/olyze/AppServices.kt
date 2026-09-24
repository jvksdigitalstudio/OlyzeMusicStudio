package com.yeivikas.olyze

import android.app.Application
import com.yeivikas.olyze.eliner.api.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.EliNerMidiApi
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Application Composition Root para el motor de audio/MIDI (Fase 1 de
 * estabilización — Objetivo H).
 *
 * ## El problema que corrige
 *
 * Antes de esta clase, `MainViewModel` construía directamente TODA la
 * infraestructura de audio y MIDI — `AudioCommandDispatcher`,
 * `ThreadManager`, `EventBus`, `LoggerService`, `TimeService`,
 * `DeviceCapabilityManager`, `PerformanceProfileManager`,
 * `MidiFoundationModule`, `MidiOutputBridge`, `MidiToSynthBridge` — junto
 * con su propio estado de UI (BPM, play/record, teclado) y sus comandos
 * de transporte. Eso es exactamente la lista de responsabilidades
 * mezcladas que la auditoría de esta fase identificó: audio
 * infrastructure + MIDI infrastructure + threading + event bus + device
 * capabilities + performance profile + bridges internos + UI state, todo
 * en una sola clase.
 *
 * ## La solución — Application Services, no otro mega-manager
 *
 * Esta clase hace SOLO una cosa: construir, arrancar y apagar la
 * infraestructura de audio/MIDI, en el orden correcto. No tiene UI state
 * (BPM, play/record, teclado — eso sigue en `MainViewModel`, que es
 * exactamente su responsabilidad), no tiene comandos de transporte, no
 * sabe qué es un teclado en pantalla. `MainViewModel` sigue siendo quien
 * construye ESTA clase (ver su límite documentado más abajo), pero ya no
 * construye ninguna pieza de infraestructura él mismo — solo consume las
 * tres superficies públicas que expone: [audio], [midi], [midiManager] +
 * [midiToSynth]/[performanceProfileManager] para lo poco que
 * `MainViewModel` todavía necesita orquestar directamente (arrancar el
 * motor con el perfil recomendado, registrar el consumer MIDI).
 *
 * Dirección real lograda:
 * ```
 * MainViewModel (UI state, comandos de UI)
 *        │
 *        ▼
 * AppServices (Application Services — este archivo)
 *        │
 *        ▼
 * EliNerAudioApi / EliNerMidiApi (EliNer Public API)
 *        │
 *        ▼
 * EliNerAudioBridge / MidiFoundationModule (Engines)
 * ```
 *
 * ## Límite honesto de este fix (documentado, no ocultado)
 *
 * El ideal completo de "Application Composition Root" (ver el prompt de
 * esta fase, sección 11) viviría en una subclase de `android.app.
 * Application`, construida una sola vez para todo el proceso — así
 * sobreviviría a la recreación de `MainViewModel`/`MainActivity` y podría
 * compartirse con un futuro segundo ViewModel (Mixer, Sequencer) sin
 * duplicar el motor. Esta fase NO llega hasta ahí: mover la construcción
 * a una `Application` custom exige tocar `AndroidManifest.xml` y no se
 * puede verificar por compilación en este entorno (ver limitaciones de
 * build) — el riesgo de ese cambio, sin poder confirmarlo compilando,
 * supera el beneficio dentro del alcance de ESTA fase (estabilización,
 * no una reestructuración de Application). `MainViewModel` sigue siendo
 * quien construye [AppServices] hoy — ver su propio comentario de clase.
 * Queda documentado como trabajo de seguimiento explícito, no oculto ni
 * declarado como resuelto.
 */
class AppServices(context: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Device capability → performance profile (Fase 6 §14-15) ──
    val performanceProfileManager =
        PerformanceProfileManager(DeviceCapabilityManager(context))

    // ── Audio engine — ver AudioCommandDispatcher para el porqué del
    //    wrapping (Objetivo A/D de esta fase). ──
    private val audioDispatcher = AudioCommandDispatcher(EliNerAudioBridge.getInstance())
    val audio: EliNerAudioApi = audioDispatcher

    // ── MIDI Foundation (entrada + salida — ver MidiOutputBridge para el
    //    porqué de "una sola implementación", no dos). ──
    private val midiThreadManager = ThreadManager()
    private val midiEventBus = EventBus()
    private val midiLogger = LoggerService()
    private val midiTimeProvider = TimeService()
    val midi: EliNerMidiApi = MidiFoundationModule.create(
        context = context,
        eventBus = midiEventBus,
        logger = midiLogger,
        taskExecutor = midiThreadManager,
        timeProvider = midiTimeProvider,
    )
    val midiManager = MidiOutputBridge(
        midi = midi,
        timeProvider = midiTimeProvider,
        scope = scope,
    )
    val midiToSynth = MidiToSynthBridge(audio)

    /**
     * Arranca el motor de audio (con el perfil de rendimiento recomendado
     * para este dispositivo) y la MIDI Foundation (descubrimiento de
     * dispositivos + consumer registrado). Llamar exactamente una vez.
     */
    fun start() {
        val profile = performanceProfileManager.applyRecommended()
        audio.start(profile)
        midi.registerConsumer(midiToSynth.consumer)
        midi.start()
    }

    /**
     * Apaga todo, en el orden correcto (Objetivo D / Crítico 3 de esta
     * fase): primero toda fuente de comandos MIDI, después el motor de
     * audio — así ningún comando puede llegar a un motor ya destruido.
     * Ver el comentario que tenía `MainViewModel.onCleared()` antes de
     * esta extracción para el detalle completo del porqué de este orden.
     */
    fun shutdown() {
        midiManager.close()
        midi.stop()
        midi.unregisterConsumer(midiToSynth.consumer)
        midi.shutdown()
        midiThreadManager.shutdown()
        audio.allNotesOff()
        audio.stop()
        audioDispatcher.close()
        scope.cancel()
    }
}
