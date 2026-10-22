package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.midi.MidiConsumer
import com.yeivikas.olyze.eliner.api.midi.MidiEvent
import com.yeivikas.olyze.eliner.api.midi.MidiEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Traduce eventos MIDI de un controlador externo (recibidos vía
 * [com.yeivikas.olyze.eliner.api.midi.EliNerMidiApi.inputEvents]/[MidiConsumer])
 * en llamadas al motor de audio real ([EliNerAudioApi]) — el enlace que
 * falta entre "MIDI Foundation ya descubre y parsea eventos" y "algo
 * realmente suena".
 *
 * Antes vivía como `MainViewModel.handleExternalMidiEvent`, una función
 * privada dentro del ViewModel — responsabilidad de "traducir protocolo
 * MIDI a llamadas del motor" mezclada con orquestación de transporte/UI,
 * algo que el propio comentario original ya señalaba como mal ubicado
 * ("debería vivir en su propia clase... se metió en el ViewModel por
 * rapidez"). Extraerla aquí no cambia ningún comportamiento — solo le da
 * a esta responsabilidad su propio lugar, con su propio test unitario
 * posible sin necesidad de instanciar un ViewModel completo.
 *
 * Deliberadamente síncrona y mínima, igual que el contrato de
 * [MidiConsumer]: solo los tipos de mensaje que ya tienen una superficie
 * directa en [EliNerAudioApi] (nota/CC/pitch bend) se manejan. NOTE_ON con
 * velocity 0 se trata como NOTE_OFF, según el propio estándar MIDI
 * (mensajes NOTE_ON con "running status" codifican así un note-off en
 * lugar de un byte de estado NOTE_OFF separado).
 *
 * No es dueña del ciclo de vida de `EliNerMidiApi`: expone [consumer] para
 * que quien la posea (hoy, `MainViewModel`) lo registre/desregistre contra
 * `EliNerMidiApi.registerConsumer`/`unregisterConsumer` — esta clase nunca
 * llama a esos métodos por sí misma.
 */
class MidiToSynthBridge(private val audio: EliNerAudioApi) {

    // Fase 1.1 §17: el estado interno es por (canal, nota), no solo por nota.
    // El motor nativo ya distingue canales (NoteOff/PitchBend se dirigen a
    // las voces del canal correcto); si este rastreo siguiera siendo solo por
    // número de nota, un NoteOff del canal 1 apagaría el resalte de una tecla
    // que el canal 0 sigue manteniendo sonando. Clave = canal shl 7 or nota
    // (canal 0-15, nota 0-127 -> 11 bits, sin colisiones).
    private val activeKeys = HashSet<Int>()
    private val activeKeysLock = Any()

    private val _externalActiveNotes = MutableStateFlow<Set<Int>>(emptySet())

    /** Notas actualmente sonando porque un controlador MIDI EXTERNO (USB/
     *  Bluetooth) las mantiene presionadas, en CUALQUIER canal (la UI solo
     *  necesita saber qué teclas resaltar) — separado de las notas del
     *  teclado táctil en pantalla, que la UI rastrea por su cuenta, para
     *  que esta clase no necesite conocer gestos de UI. Una nota sigue
     *  presente mientras al menos un canal la mantenga presionada. */
    val externalActiveNotes: StateFlow<Set<Int>> = _externalActiveNotes.asStateFlow()

    /** Registrar contra `EliNerMidiApi.registerConsumer(consumer)`. */
    val consumer = MidiConsumer { event -> handle(event) }

    private fun setKeyActive(channel: Int, note: Int, active: Boolean) {
        synchronized(activeKeysLock) {
            val key = (channel shl 7) or note
            if (active) activeKeys.add(key) else activeKeys.remove(key)
            _externalActiveNotes.value = activeKeys.mapTo(HashSet()) { it and 0x7F }
        }
    }

    /**
     * Contrato de canal (§17): `event.channel` es 0-15 cero-basado y se
     * propaga tal cual hasta el motor nativo (`audio.noteOn(channel, ...)`);
     * los eventos de sistema (`channel == null`) no llegan a esta rama de
     * mensajes de voz. NOTE_ON/NOTE_OFF/PITCH_BEND se enrutan por canal en
     * el motor; CONTROL_CHANGE llega con su canal pero el motor hoy lo trata
     * como control global de master (ver `AudioEngine::sendCC`).
     * Aftertouch (canal/polifónico) y PROGRAM_CHANGE no tienen todavía
     * superficie en [EliNerAudioApi]: se descartan aquí de forma explícita
     * (ver `else`), no por omisión — el canal ya viaja en [MidiEvent] para
     * cuando exista un consumidor.
     */
    private fun handle(event: MidiEvent) {
        val channel = event.channel ?: 0
        when (event.type) {
            MidiEventType.NOTE_ON -> {
                if (event.data2 > 0) {
                    audio.noteOn(channel, event.data1, event.data2)
                    setKeyActive(channel, event.data1, true)
                } else {
                    audio.noteOff(channel, event.data1)
                    setKeyActive(channel, event.data1, false)
                }
            }
            MidiEventType.NOTE_OFF -> {
                audio.noteOff(channel, event.data1)
                setKeyActive(channel, event.data1, false)
            }
            MidiEventType.CONTROL_CHANGE -> audio.sendCC(channel, event.data1, event.data2)
            MidiEventType.PITCH_BEND -> {
                // pitchBendValue es de 14 bits, 0-16383, 8192 = centro.
                // Se convierte a +/-2 semitonos (rango de pitch-bend de
                // noteToHz() en el motor), misma convención que ya usaban
                // MIDI CC 10 / el antiguo OlyzeMidiManager.sendPitchBend.
                val semitones = (event.pitchBendValue - PITCH_BEND_CENTER) /
                    PITCH_BEND_CENTER.toFloat() * PITCH_BEND_RANGE_SEMITONES
                audio.setPitchBend(channel, semitones)
            }
            // CLOCK/START/STOP/CONTINUE ya los maneja MidiClockEngine; SysEx/aftertouch: sin superficie en el motor todavía.
            else -> Unit
        }
    }

    private companion object {
        const val PITCH_BEND_CENTER = 8192
        const val PITCH_BEND_RANGE_SEMITONES = 2f
    }
}
