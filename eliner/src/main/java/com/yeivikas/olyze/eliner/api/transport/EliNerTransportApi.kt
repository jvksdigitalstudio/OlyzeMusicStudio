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

    /**
     * Intensidad de CADA tiempo del compás (silencio, suave, normal, acento). Sustituye al antiguo
     * interruptor de "acento del primer tiempo": ese era el caso particular «primer tiempo acentuado,
     * resto normales» (o «todos normales»).
     */
    val beatPattern: StateFlow<BeatPattern>

    /** Unidad de pulso = denominador del compás (4 = negra, 8 = corchea…). Ver [BeatUnit]. */
    val beatUnit: StateFlow<BeatUnit>

    /** Clicks por pulso: solo pulso, corcheas, tresillos o semicorcheas. */
    val clickSubdivision: StateFlow<ClickSubdivision>

    /** Swing de la subdivisión del click (ADR 0039). */
    val clickSwing: StateFlow<ClickSwing>

    /** Configuración deseada del delay sincronizado al tempo. */
    val delaySync: StateFlow<DelayTempoSync>

    /**
     * `true` si hay una grabación ARMADA (ADR 0035). Hoy solo gobierna el conteo previo y el [clickMode]: la
     * captura de audio/MIDI llegará en una fase posterior y colgará de este mismo estado.
     */
    val recordArmed: StateFlow<Boolean>

    /** Compases de conteo previo a grabar (ver [CountInLength]). */
    val countInLength: StateFlow<CountInLength>

    /** Cuándo suena el click (ver [ClickMode]). */
    val clickMode: StateFlow<ClickMode>

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
     * Arranca el transporte DESDE LA POSICIÓN ACTUAL (ADR 0036): el primer tiempo si es la primera vez o se
     * rebobinó con [locate]`(0)`, o el punto donde se pausó con [stop]. El primer pulso suena en el primer
     * frame. Si ya está en marcha no hace nada (no reinicia el compás). Con la grabación armada y un conteo
     * configurado, empieza por el conteo (ADR 0035).
     */
    fun start()

    /**
     * Detiene el transporte CONSERVANDO la posición y la fase dentro del pulso (la «pausa» de un DAW, ADR 0036):
     * el siguiente [start] continúa justo donde se paró. Para volver al primer tiempo, [locate]`(0)`.
     */
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

    /**
     * Fija la intensidad de cada tiempo. Se aplica de una vez (el motor nunca oye un patrón a medias).
     * No cambia con el compás por sí mismo: quien cambia el compás decide si restablecer el patrón
     * (ver [BeatPattern.defaultFor]).
     */
    fun setBeatPattern(pattern: BeatPattern)

    /**
     * Atenuación TEMPORAL del click del metrónomo, 0.0 (mudo) a 1.0 (sin atenuar), independiente del volumen
     * del usuario ([setMetronomeVolume]) y multiplicada con él. La usa el «Mute» del tap tempo: silenciar el
     * click mientras se mide y volver a entrar gradualmente. Es estado deseado: tras reiniciar el motor se
     * reaplica el último valor puesto aquí. Un valor no finito se ignora; fuera de 0–1 se recorta.
     */
    fun setMetronomeDuck(level: Float)

    /**
     * Reinicia el compás: el siguiente bloque de audio empieza en el tiempo 1 (el «Sync» del tapper de FL
     * Studio: cada toque reinicia el tiempo 1). Con el transporte parado no hace nada. Es una ORDEN, no un
     * estado: no se coalesce ni se reenvía tras reiniciar el motor. No compensa la latencia de salida de audio.
     */
    fun resyncBar()

    /**
     * Fija la unidad de pulso (el denominador del compás). El tempo sigue en negras por minuto; solo
     * cambia cuánto dura cada pulso del metrónomo.
     */
    fun setBeatUnit(unit: BeatUnit)

    /** Elige cuántos clicks suenan por pulso. Se resincroniza en cada pulso. */
    fun setClickSubdivision(subdivision: ClickSubdivision)

    /** Elige el swing de la subdivisión; se aplica en el siguiente pulso. Solo suena con 2 o 4 clicks por pulso. */
    fun setClickSwing(swing: ClickSwing)

    /**
     * Latencia de salida de audio estimada, en milisegundos: lo que tarda el sonido en oírse desde que el motor lo
     * renderiza (ADR 0037). `null` mientras no hay medida válida. Se mantiene al día SOLO mientras alguien
     * recoge [pulse] (la UI visible): es lo que la necesita, y no se muestrea en segundo plano.
     */
    val outputLatencyMillis: StateFlow<Float?>

    /**
     * Posición del transporte en ticks, SONDEADA mientras alguien la recoge (flujo frío, ≈ 30 Hz) y emitiendo solo
     * cuando cambia. Es el equivalente de [pulse] para mostrar la posición en pantalla; con el transporte parado
     * emite una vez y calla. Para leer el valor puntualmente (p. ej. al reanudar) usa [positionTicks].
     */
    val positionUpdates: Flow<Long>

    /**
     * Posición actual del transporte en ticks ([TransportPosition.TICKS_PER_QUARTER] por negra; 0 = primer
     * tiempo del compás 1). Es la que el motor PUBLICÓ en el último bloque de audio: con el transporte en marcha
     * avanza; parado o en pausa se conserva. Durante el conteo previo a grabar es la posición donde empezará la
     * grabación (ADR 0036). Lectura atómica, segura desde cualquier hilo y sin bloquear.
     *
     * Ojo: refleja el motor, no lo que se acaba de pedir. Una orden de [locate] tarda hasta un bloque de audio
     * (unos 10 ms) en verse aquí.
     */
    fun positionTicks(): Long

    /**
     * Coloca el transporte en [ticks] (recortado a `[0, TransportPosition.MAX_TICKS]`), en marcha o no. Si está
     * en marcha, sigue sonando desde ahí; un conteo previo en curso se cancela. Volver al principio es
     * `locate(0)`.
     *
     * Es una ORDEN de un solo uso, no estado deseado: no se reaplica si el motor se recrea, y varias seguidas antes
     * de que el motor las reciba se funden en la última. Viaja siempre ANTES que un [start] pedido después, de
     * modo que `locate(x)` seguido de `start()` arranca desde `x`.
     */
    fun locate(ticks: Long)

    /**
     * Arma o desarma la grabación (ADR 0035). Con la grabación armada, [start] desde parado empieza por el
     * conteo previo ([setCountInLength]). Armar con el transporte YA en marcha no inicia ningún conteo.
     * Desarmar DURANTE el conteo lo cancela y el transporte sigue reproduciendo. Es estado deseado: tras
     * reiniciar el motor se reaplica.
     */
    fun setRecordArmed(armed: Boolean)

    /** Fija los compases de conteo previo. No afecta a un conteo que ya esté sonando. */
    fun setCountInLength(length: CountInLength)

    /** Elige cuándo suena el click (reproduciendo y grabando, solo grabando o solo el conteo). */
    fun setClickMode(mode: ClickMode)

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
        val DEFAULT_BEAT_PATTERN: BeatPattern = BeatPattern.DEFAULT
        val DEFAULT_BEAT_UNIT: BeatUnit = BeatUnit.QUARTER
        val DEFAULT_CLICK_SUBDIVISION: ClickSubdivision = ClickSubdivision.NONE

        /** Debe coincidir con `kDefaultClickSwingPercent` (ClickSound.h): ADR 0039. */
        val DEFAULT_CLICK_SWING: ClickSwing = ClickSwing.STRAIGHT

        // ADR 0035. Deben coincidir con kCountInBarsDefault (CountIn.h) y con el modo por defecto del motor
        // (Always): TransportConstantsMatchNativeTest lo comprueba para el conteo.
        val DEFAULT_COUNT_IN_LENGTH: CountInLength = CountInLength.ONE_BAR
        val DEFAULT_CLICK_MODE: ClickMode = ClickMode.ALWAYS
    }
}

/** Configuración del delay sincronizado al tempo (ver [EliNerTransportApi.setDelayTempoSync]). */
data class DelayTempoSync(
    val enabled: Boolean,
    val division: NoteDivision,
)
