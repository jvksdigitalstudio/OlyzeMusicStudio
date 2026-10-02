package com.yeivikas.olyze.eliner.api

import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.midi.EliNerMidiApi
import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import kotlinx.coroutines.flow.StateFlow

/**
 * Punto de entrada ÚNICO de EliNer para una aplicación.
 *
 * Una app (Olyze Music Studio, o cualquier otra app del ecosistema)
 * obtiene una instancia mediante `EliNerEngineFactory.create(...)` y a
 * partir de ahí solo depende de `eliner.api.*`: no conoce hilos, buses de
 * eventos, loggers, proveedores de tiempo, perfiles de rendimiento ni los
 * puentes internos. Toda esa construcción, el orden de arranque y el
 * orden de apagado pertenecen al motor, no a la app.
 *
 * ```
 * App (UI / ViewModel)
 *        │   solo ve eliner.api.*
 *        ▼
 * EliNerEngine ── audio ──▶ EliNerAudioApi
 *              ├─ midi ───▶ EliNerMidiApi
 *              └─ midiOutput ▶ MidiOutputApi
 *        │   (implementación interna: eliner.composition)
 *        ▼
 * bridge / modules.midi / services …
 * ```
 *
 * ## Ciclo de vida
 *
 * 1. [start] — exactamente una vez; no bloquea al llamador. Progreso en [state].
 * 2. Uso normal vía [audio], [midi], [midiOutput].
 * 3. [shutdown] — exactamente una vez; apaga fuentes MIDI primero y el
 *    motor de audio después, de modo que ningún comando llega a un motor
 *    ya destruido. Tras [shutdown] la instancia no es reutilizable.
 */
interface EliNerEngine {
    /** Comandos y parámetros del sintetizador/DSP. */
    val audio: EliNerAudioApi

    /** API MIDI genérica: dispositivos, eventos de entrada, bindings. */
    val midi: EliNerMidiApi

    /** Salida MIDI de conveniencia hacia el dispositivo activo. */
    val midiOutput: MidiOutputApi

    /**
     * Teclas actualmente pulsadas por dispositivos MIDI EXTERNOS (número de
     * nota 0..127), para que la UI las refleje en su teclado.
     */
    val externalActiveNotes: StateFlow<Set<Int>>

    /** Estado observable del arranque. Ver [EngineInitState]. */
    val state: StateFlow<EngineInitState>

    /**
     * Arranca el motor de audio (con el perfil de rendimiento recomendado
     * para el dispositivo) y la MIDI Foundation. Llamar exactamente una vez.
     * No bloquea: observar [state] (`Starting` → `Ready`/`Failed`).
     *
     * @throws IllegalStateException si ya fue llamado.
     */
    fun start()

    /** Apaga todo en el orden correcto. Seguro frente a un [start] en curso. */
    fun shutdown()
}
