package com.yeivikas.olyze.eliner.api

import kotlin.reflect.KClass

/**
 * Registry of Foundation Services, keyed by contract type (an interface
 * like `com.yeivikas.olyze.eliner.diagnostics.Logger`, not a concrete
 * class where a contract exists).
 *
 * **This is explicitly NOT a Service Locator.** The spec is direct about
 * this ("No utilizar Service Locator global. No utilizar variables
 * estáticas innecesarias"), and the distinction is real, not cosmetic:
 * - No `object ServiceRegistry` / no top-level `val`. This class only
 *   exists as an instance, owned by one [EliNerRuntime] and reachable only
 *   through it — nothing can reach a `ServiceRegistry` via a static/global
 *   reference the way the Service Locator anti-pattern requires.
 * - Nothing in `eliner.core`, `eliner.services`, `eliner.diagnostics`,
 *   `eliner.events`, `eliner.configuration`, or `eliner.resources` reaches
 *   into a `ServiceRegistry` to pull its own dependencies — every
 *   Foundation Service still receives what it needs via constructor
 *   injection, exactly as built in Fase 2. This registry exists so
 *   *external* code (a future module, or `:app` itself) can discover a
 *   service by contract without `EliNerRuntime` needing a getter method
 *   per service.
 *
 * Thread-safe via `synchronized`, same rationale as
 * [com.yeivikas.olyze.eliner.core.ModuleRegistry]: registration isn't a
 * real-time-audio-thread operation.
 */
class ServiceRegistry {
    private val lock = Any()
    private val services = mutableMapOf<KClass<*>, Any>()

    /** Registers [instance] under contract [type]. Replaces any previous registration for [type]. */
    fun <T : Any> register(type: KClass<T>, instance: T) {
        synchronized(lock) { services[type] = instance }
    }

    /** Reified convenience for [register] — `register<Logger>(loggerService)`. */
    inline fun <reified T : Any> register(instance: T) = register(T::class, instance)

    /** Looks up the service registered under contract [type], or null if none is. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(type: KClass<T>): T? = synchronized(lock) { services[type] as? T }

    /** Reified convenience for [get] — `get<Logger>()`. */
    inline fun <reified T : Any> get(): T? = get(T::class)

    /**
     * Replaces the service registered under [type] with [instance] and
     * returns the previous one, or null if none was registered. Distinct
     * from [register] only in return value — exists so a caller can
     * explicitly confirm a replacement happened, per the spec's explicit
     * "reemplazar servicios" requirement.
     */
    fun <T : Any> replace(type: KClass<T>, instance: T): T? {
        val previous = get(type)
        register(type, instance)
        return previous
    }

    /** Whether a service is registered under contract [type]. */
    fun isRegistered(type: KClass<*>): Boolean = synchronized(lock) { type in services }

    /** Number of currently registered services. */
    fun size(): Int = synchronized(lock) { services.size }
}
