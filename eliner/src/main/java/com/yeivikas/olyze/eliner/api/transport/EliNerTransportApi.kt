package com.yeivikas.olyze.eliner.api.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tempo, transporte y metrónomo del motor (ADR 0028).
 *
 * Dominio propio, separado de [com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi]
 * a propósito: añadir métodos a una interfaz pública rompe a todo el que la
 * implemente (principio de API estable, Fase 6 §16), así que el tiempo
 * musical vive en su propio contrato, igual que `api.audio` y `api.midi`.
 *
 * ## Qué es el tempo en EliNer
 *
 * El motor nativo tiene UN reloj de transporte con precisión de muestra,
 * dueño único del tiempo musical: el metrónomo y el delay sincronizado lo
 * consumen, y cualquier secuenciador futuro lo hará también. Esta API es su
 * superficie de control; no calcula tiempo por su cuenta.
 *
 * ## Estado deseado y reaplicación
 *
 * Esta API guarda el estado DESEADO (los [StateFlow]) y el motor nativo lo
 * recibe como comandos. El motor nativo se crea de nuevo en cada arranque,
 * así que la implementación reaplica todo el estado cuando el motor queda
 * listo: llamar a [setTempo] o [setMetronomeEnabled] mientras el motor aún
 * arranca NO se pierde — queda reflejado en los flujos y se aplica al
 * arrancar.
 *
 * ## Hilos
 *
 * Todos los métodos son seguros desde cualquier hilo y no bloquean (encolan
 * en el motor). Las entradas no finitas se ignoran y las fuera de rango se
 * limitan.
 */
interface EliNerTransportApi {

    /** Tempo deseado, en BPM (negras por minuto), dentro de [MIN_TEMPO_BPM]..[MAX_TEMPO_BPM]. */
    val tempoBpm: StateFlow<Float>

    /** `true` mientras el transporte está en marcha (el reloj emite pulsos). */
    val isRunning: StateFlow<Boolean>

    /** Pulsos por compás deseados, en [MIN_BEATS_PER_BAR]..[MAX_BEATS_PER_BAR]. */
    val beatsPerBar: StateFlow<Int>

    val metronomeEnabled: StateFlow<Boolean>

    /** Volumen del metrónomo, 0.0–1.0 (curva cuadrática en el motor). */
    val metronomeVolume: StateFlow<Float>

    /** Sonido del click del metrónomo (ADR 0031). */
    val metronomeSound: StateFlow<MetronomeSound>

    /** `true` si el primer tiempo del compás suena acentuado (más agudo y más fuerte). */
    val metronomeAccent: StateFlow<Boolean>

    /** Clicks por pulso: solo pulso, corcheas, tresillos o semicorcheas. */
    val clickSubdivision: StateFlow<ClickSubdivision>

    /** Configuración deseada del delay sincronizado al tempo. */
    val delaySync: StateFlow<DelayTempoSync>

    /**
     * Posición de pulso REAL del motor (no la deseada), para indicadores
     * visuales (ADR 0029). Emite solo cuando cambia, a lo sumo ~60 veces por
     * segundo.
     *
     * Es un flujo FRÍO que sondea el motor mientras alguien lo recolecta: si
     * nadie lo observa (la app en segundo plano, el panel cerrado) no cuesta
     * nada. A diferencia de los [StateFlow] de arriba, no refleja lo que se
     * pidió sino lo que el reloj de audio está haciendo, así que no emite nada
     * mientras el motor aún arranca. Ver [BeatPulse] sobre su sincronía con el
     * oído.
     */
    val pulse: Flow<BeatPulse>

    /**
     * Fija el tempo. Cambiarlo con el transporte en marcha conserva la fase
     * musical (ni pulsos repetidos ni omitidos) y, si el delay está
     * sincronizado, reajusta su tiempo sin clicks.
     */
    fun setTempo(bpm: Float)

    /**
     * Arranca el transporte desde el primer tiempo del compás: el primer
     * pulso suena en el primer frame. Si ya está en marcha no hace nada
     * (no reinicia el compás).
     */
    fun start()

    /** Detiene el transporte. El siguiente [start] vuelve al primer tiempo. */
    fun stop()

    fun setBeatsPerBar(beats: Int)

    /**
     * Activa o desactiva el click. El metrónomo solo suena con el transporte
     * en marcha ([start]). Va por un bus propio: no pasa por los efectos y no
     * lo atenúa el volumen master.
     */
    fun setMetronomeEnabled(enabled: Boolean)

    fun setMetronomeVolume(volume: Float)

    /** Elige el sonido del click. Se aplica al siguiente click, sin cortar el que suena. */
    fun setMetronomeSound(sound: MetronomeSound)

    /** Activa/desactiva el acento del primer tiempo. */
    fun setMetronomeAccent(enabled: Boolean)

    /** Elige cuántos clicks suenan por pulso. Se resincroniza en cada pulso. */
    fun setClickSubdivision(subdivision: ClickSubdivision)

    /**
     * Sincroniza el tiempo del delay al tempo: con [enabled], el eco dura
     * [division] al tempo actual (a 120 BPM, corchea con puntillo = 0,375 s)
     * y sigue los cambios de tempo. Si la duración no cabe en el delay se
     * pliega por octavas (la mitad conserva el ritmo; recortar lo rompería).
     *
     * Activo por defecto con [NoteDivision.EIGHTH_DOTTED]: a 120 BPM coincide
     * con el tiempo de delay histórico del motor. Fijar el tiempo del delay
     * a mano ([com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi.setDelayTime])
     * lo desactiva; esta llamada lo reactiva.
     */
    fun setDelayTempoSync(enabled: Boolean, division: NoteDivision = NoteDivision.EIGHTH_DOTTED)

    companion object {
        // Estos límites DEBEN coincidir con tempo::kMinBpm / kMaxBpm
        // (eliner/include/eliner/transport/TempoSync.h). El motor limita por
        // su cuenta, así que un desajuste no rompe nada, pero la UI mostraría
        // un rango distinto del real.
        const val MIN_TEMPO_BPM: Float = 20f
        const val MAX_TEMPO_BPM: Float = 300f
        const val DEFAULT_TEMPO_BPM: Float = 120f

        // Idem TempoClock::kMinBeatsPerBar / kMaxBeatsPerBar.
        const val MIN_BEATS_PER_BAR: Int = 1
        const val MAX_BEATS_PER_BAR: Int = 16
        const val DEFAULT_BEATS_PER_BAR: Int = 4

        const val DEFAULT_METRONOME_VOLUME: Float = 0.7f

        // Valores por defecto del click: deben coincidir con los del motor (Metronome.h) para que
        // la UI no muestre algo distinto de lo que suena antes del primer envío.
        val DEFAULT_METRONOME_SOUND: MetronomeSound = MetronomeSound.CLASSIC
        const val DEFAULT_METRONOME_ACCENT: Boolean = true
        val DEFAULT_CLICK_SUBDIVISION: ClickSubdivision = ClickSubdivision.NONE
    }
}

/** Configuración del delay sincronizado al tempo (ver [EliNerTransportApi.setDelayTempoSync]). */
data class DelayTempoSync(
    val enabled: Boolean,
    val division: NoteDivision,
)
