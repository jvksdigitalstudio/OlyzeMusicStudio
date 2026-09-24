package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.EliNerAudioApi
import com.yeivikas.olyze.eliner.api.MidiConsumer
import com.yeivikas.olyze.eliner.api.MidiEvent
import com.yeivikas.olyze.eliner.api.MidiEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Traduce eventos MIDI de un controlador externo (recibidos vía
 * [com.yeivikas.olyze.eliner.api.EliNerMidiApi.inputEvents]/[MidiConsumer])
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

    private val _externalActiveNotes = MutableStateFlow<Set<Int>>(emptySet())

    /** Notas actualmente sonando porque un controlador MIDI EXTERNO (USB/
     *  Bluetooth) las mantiene presionadas — separado de las notas del
     *  teclado táctil en pantalla, que la UI rastrea por su cuenta, para
     *  que esta clase no necesite conocer gestos de UI. */
    val externalActiveNotes: StateFlow<Set<Int>> = _externalActiveNotes.asStateFlow()

    /** Registrar contra `EliNerMidiApi.registerConsumer(consumer)`. */
    val consumer = MidiConsumer { event -> handle(event) }

    private fun handle(event: MidiEvent) {
        val channel = event.channel ?: 0
        when (event.type) {
            MidiEventType.NOTE_ON -> {
                if (event.data2 > 0) {
                    audio.noteOn(channel, event.data1, event.data2)
                    _externalActiveNotes.value = _externalActiveNotes.value + event.data1
                } else {
                    audio.noteOff(channel, event.data1)
                    _externalActiveNotes.value = _externalActiveNotes.value - event.data1
                }
            }
            MidiEventType.NOTE_OFF -> {
                audio.noteOff(channel, event.data1)
                _externalActiveNotes.value = _externalActiveNotes.value - event.data1
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
