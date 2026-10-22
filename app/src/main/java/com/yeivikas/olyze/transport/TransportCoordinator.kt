package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.midi.MidiOutputApi
import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import com.yeivikas.olyze.eliner.api.transport.TransportPosition

/**
 * Orquesta lo que significa "reproducir", "pausar" y "rebobinar" para la app: coordina el
 * transporte del motor, los mensajes MIDI de transporte hacia fuera y el reloj MIDI.
 *
 * ## Responsabilidad única
 * La SECUENCIA. No guarda estado de reproducción (es del motor: [EliNerTransportApi.isRunning] y
 * [EliNerTransportApi.positionTicks]), no conoce la UI ni Compose y no genera temporización (eso es de
 * [MidiClock]). La única memoria que tiene es la de [rewoundWhileStopped], explicada abajo. Antes esta secuencia
 * vivía mezclada dentro de `MainViewModel`.
 *
 * ## Pausar, reanudar y rebobinar (ADR 0036)
 * El transporte tiene POSICIÓN. [togglePlay] alterna reproducir y PAUSAR: pausar conserva la posición y
 * reproducir continúa donde se paró. Volver al principio es [rewind], que NO detiene: si estaba sonando, sigue
 * sonando desde el primer tiempo. Hacia fuera, MIDI distingue lo mismo:
 *  - desde la posición 0 → `START` (empieza desde el principio);
 *  - desde cualquier otra → `Song Position Pointer` y `CONTINUE` (reanuda en mitad de la canción), en ese orden:
 *    un receptor aplica el SPP en el siguiente START/CONTINUE.
 *  - rebobinar sonando → `START` (salta al principio); rebobinar parado → `SPP 0`, para que el equipo externo
 *    quede colocado en el principio.
 *
 * ## Rebobinar y reproducir sin esperar al motor
 * El motor aplica `locate` en su siguiente bloque de audio (unos 10 ms). Si el usuario rebobina estando parado y
 * pulsa play enseguida, la posición que se lee del motor aún podría ser la vieja y saldría un SPP equivocado.
 * [rewoundWhileStopped] cubre exactamente esa ventana: el siguiente arranque sabe que parte de 0 sin consultar.
 *
 * ## Grabar (ADR 0035)
 * Grabar es un MODO del transporte, no una función suelta: [toggleRecord] arma la grabación y, si estaba
 * parado, arranca (el motor empieza por el conteo previo si hay uno configurado). Parar SIEMPRE desarma, como
 * en los DAW: la grabación termina con el transporte, y el siguiente «play» no vuelve a contar ni a grabar.
 * Mientras suena el conteo, el START y el reloj MIDI salen ya hacia fuera: un dispositivo externo se alinea
 * con el conteo igual que con cualquier arranque.
 *
 * ## Orden, y por qué importa
 *  - **Arrancar:** primero el motor (el primer pulso suena en el primer frame),
 *    luego START hacia fuera y por último el reloj, de modo que el dispositivo externo
 *    recibe START antes que el primer pulso de reloj.
 *  - **Parar:** primero se detiene el reloj (ningún pulso de reloj tras STOP), luego el
 *    motor, luego STOP hacia fuera y, al final, se silencian las notas que quedaran
 *    sonando (un STOP no debe dejar notas colgadas).
 *
 * @param silenceAllNotes silencia todas las voces; se inyecta como función para no
 *   depender de la API de audio completa.
 */
class TransportCoordinator(
    private val transport: EliNerTransportApi,
    private val midiOutput: MidiOutputApi,
    private val midiClock: MidiClock,
    private val silenceAllNotes: () -> Unit,
) {
    /** Se rebobinó con el transporte parado y aún no se ha arrancado: la posición de salida es 0 sin leer el motor. */
    private var rewoundWhileStopped = false

    /** Reproduce si estaba parado (desde donde esté); PAUSA si estaba en marcha. */
    fun togglePlay() {
        if (transport.isRunning.value) stopAll() else startAll()
    }

    /**
     * Botón de grabar. Parado: arma y arranca (con conteo si lo hay). En marcha sin grabar: arma, sin conteo
     * (no hay nada que contar si ya suena). Grabando: desarma y el transporte sigue reproduciendo; si estaba en
     * el conteo, el motor lo cancela (ver `setRecordArmed` en [EliNerTransportApi]).
     */
    fun toggleRecord() {
        if (transport.recordArmed.value) {
            transport.setRecordArmed(false)
            return
        }
        // El armado se pide ANTES que el arranque: el motor decide si hay conteo en el instante de arrancar.
        transport.setRecordArmed(true)
        if (!transport.isRunning.value) startAll()
    }

    /**
     * Vuelve al primer tiempo. NO detiene el transporte: sonando, sigue sonando desde el principio; parado,
     * queda colocado en el principio para el siguiente [togglePlay].
     */
    fun rewind() {
        transport.locate(0L)
        if (transport.isRunning.value) {
            midiOutput.sendStart()               // sonando: el equipo externo salta al principio
        } else {
            rewoundWhileStopped = true
            midiOutput.sendSongPosition(0)       // parado: el equipo externo queda colocado en el principio
        }
    }

    private fun startAll() {
        val position = if (rewoundWhileStopped) 0L else transport.positionTicks()
        rewoundWhileStopped = false
        transport.start()
        if (position <= 0L) {
            midiOutput.sendStart()
        } else {
            // Reanudar en mitad de la canción: primero dónde, después «continúa».
            midiOutput.sendSongPosition(TransportPosition.songPositionFor(position))
            midiOutput.sendContinue()
        }
        midiClock.start()
    }

    private fun stopAll() {
        rewoundWhileStopped = false // lo que importa a partir de ahora es la posición real del motor
        transport.setRecordArmed(false) // la grabación termina con el transporte
        midiClock.stop()
        transport.stop()
        midiOutput.sendStop()
        silenceAllNotes()
    }
}
