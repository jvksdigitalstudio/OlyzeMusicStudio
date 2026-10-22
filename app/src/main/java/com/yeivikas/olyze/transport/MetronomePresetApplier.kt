package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi

/**
 * Aplica un preset al transporte del motor: tempo, compás, patrón, sonido, subdivisión y volumen.
 *
 * Es una extensión aparte (y no un método del preset) para que el preset siga siendo un valor puro sin
 * conocer la API del motor. NO toca el estado de reproducción ni si el metrónomo está activado: cargar un
 * preset cambia CÓMO suena, no si suena. El compás y el patrón se aplican TAL CUAL vienen guardados (no
 * se restablece el patrón por defecto: lo guardado es justo lo que el usuario quería).
 */
fun EliNerTransportApi.applyPreset(preset: MetronomePreset) {
    setTempo(preset.bpm)
    setBeatsPerBar(preset.beatsPerBar)
    setBeatUnit(preset.beatUnit)
    setBeatPattern(preset.pattern)
    setMetronomeSound(preset.sound)
    setClickSubdivision(preset.subdivision)
    setMetronomeVolume(preset.volume)
}
