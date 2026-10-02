package com.yeivikas.olyze.eliner.api

/**
 * Estado observable de [EliNerEngine.start] (Fase 1.1 §15).
 *
 * Antes de esta fase, `EliNerEngine.start()` no exponía absolutamente
 * ningún estado intermedio — era una llamada bloqueante que, al volver,
 * o ya había arrancado todo o había lanzado una excepción sin capturar.
 * La UI (`MainViewModel`) no tenía forma de mostrar "arrancando el
 * motor..." ni de reaccionar a un fallo real de arranque (dispositivo de
 * audio ocupado por otra app, stream exclusivo robado por el sistema,
 * etc.) más que con un crash o un estado silenciosamente roto.
 */
sealed interface EngineInitState {
    /** [EliNerEngine.start] todavía no fue llamado. */
    data object NotStarted : EngineInitState

    /**
     * El arranque está en curso — el motor de audio (`openStream()` →
     * DSP graph → `requestStart()`, del lado nativo, vía
     * [com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi.start]) y/o la MIDI
     * Foundation todavía se están inicializando en segundo plano. La UI
     * puede mostrar un indicador de carga; NO debe asumir que
     * el despachador de comandos de audio y `midi`
     * ya aceptan comandos con efecto real (los aceptan igual — best
     * effort — pero el motor real puede no estar `RUNNING` todavía).
     */
    data object Starting : EngineInitState

    /** El motor de audio arrancó con éxito y la MIDI Foundation quedó
     *  registrada — listo para uso normal. */
    data object Ready : EngineInitState

    /**
     * El arranque terminó en fallo — real (el motor nativo rechazó el
     * `start()`, ver [reason]) o por timeout (ver
     * el timeout de arranque del motor, 5 s). La UI debe poder mostrar esto de
     * forma explícita en vez de comportarse como si el motor estuviera
     * silenciosamente listo.
     */
    data class Failed(val reason: String) : EngineInitState
}
