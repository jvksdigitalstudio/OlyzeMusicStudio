package com.yeivikas.olyze.eliner.core

/**
 * Fase 1.1 §20 (ADR 0022): el subconjunto de [EliNerCore] que
 * `eliner.api.ModuleLoader` necesita — registro/baja/consulta de módulos —
 * y nada más. Antes, `ModuleLoader` (que vive en `eliner.api`, la frontera
 * pública) dependía directamente de la clase CONCRETA `EliNerCore`
 * (lifecycle, `errors`, `snapshot()`, `start()`/`stop()` incluidos), la
 * misma inversión de dependencia que ya se corrigió en `RuntimeContext`
 * (ver ese archivo): frontera pública dependiendo de una implementación
 * interna concreta, en vez de al revés.
 *
 * Deliberadamente NO expone `state`/`errors`/`start()`/`stop()`/`snapshot()`
 * — `ModuleLoader` no los usa (ver su código); ampliar esta interfaz para
 * "quizás haga falta después" sería la arquitectura especulativa que esta
 * fase prohíbe. Cuando un consumidor real necesite más superficie, se
 * amplía entonces, con ese caso de uso real delante.
 *
 * Vive en `eliner.core` (no en `eliner.api`) porque `EliNerCore` la
 * implementa y `eliner.core` no importa nada de `eliner.api` — ver el
 * comentario de "Dependency direction" en [EliNerCore]; este archivo no lo
 * cambia, solo aprovecha que un contrato puede vivir junto a su única
 * implementación real sin que eso implique que sus CONSUMIDORES deban
 * depender de esa implementación.
 */
interface ModuleRegistryContract {
    fun registerModule(module: EliNerModule)
    fun unregisterModule(id: String): Boolean
    fun getModule(id: String): EliNerModule?
    fun isModuleRegistered(id: String): Boolean
}
