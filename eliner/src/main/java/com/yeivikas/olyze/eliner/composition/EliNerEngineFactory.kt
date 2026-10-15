package com.yeivikas.olyze.eliner.composition

import android.app.Application
import com.yeivikas.olyze.eliner.api.EliNerEngine

/**
 * Único punto público de construcción de [EliNerEngine].
 *
 * Es la única clase de `eliner.composition` visible para las aplicaciones:
 * la implementación ([DefaultEliNerEngine]) es `internal`. Las apps
 * dependen de `eliner.api.*` más esta fábrica y nada más.
 */
object EliNerEngineFactory {
    /**
     * Crea un motor NUEVO, todavía sin arrancar (ver [EliNerEngine.start]).
     * Cada llamada devuelve una instancia independiente con su propio
     * ámbito de corrutinas, hilos y bus de eventos; el llamador es dueño
     * de ella y debe invocar [EliNerEngine.shutdown] exactamente una vez.
     */
    fun create(application: Application): EliNerEngine = DefaultEliNerEngine(application)
}
