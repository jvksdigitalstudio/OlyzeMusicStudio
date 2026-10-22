package com.yeivikas.olyze.eliner.runtime

import com.yeivikas.olyze.eliner.configuration.Configuration
import com.yeivikas.olyze.eliner.diagnostics.Logger
import com.yeivikas.olyze.eliner.events.EventBus
import com.yeivikas.olyze.eliner.resources.Resources
import com.yeivikas.olyze.eliner.services.CapabilityProvider
import com.yeivikas.olyze.eliner.services.PerformanceProfileProvider
import com.yeivikas.olyze.eliner.services.TaskExecutor
import com.yeivikas.olyze.eliner.services.TimeProvider

/**
 * Shared references every future module will receive instead of importing
 * Foundation Services directly — exactly the "Runtime Context" the spec
 * asks for: "aquí vivirán las referencias comunes del motor... nunca
 * depender directamente de implementaciones concretas."
 *
 * Every property is typed to an interface **except [events]**. That one
 * exception is deliberate and documented, not an oversight: `EventBus`'s
 * `subscribe<T>()` is an `inline`/`reified` function, which Kotlin cannot
 * express on an interface — there is no `EventPublisher`/`EventBus`
 * contract split that preserves that API without losing the generic
 * subscription ergonomics every future module will want. Depending on the
 * concrete `EventBus` here is a real, structural trade-off, not a
 * shortcut.
 */
class RuntimeContext(
    val logger: Logger,
    val configuration: Configuration,
    val resources: Resources,
    val events: EventBus,
    val timeProvider: TimeProvider,
    val capabilityProvider: CapabilityProvider,
    val performanceProfileProvider: PerformanceProfileProvider,
    val threadManager: TaskExecutor,
)

// La fábrica `createDefaultRuntimeContext` (que instancia las implementaciones
// concretas de cada Foundation Service) vive desde la Fase 1.1 §20 en
// `com.yeivikas.olyze.eliner.runtime.createDefaultRuntimeContext`: `eliner.api`
// contiene solo contratos y vocabulario, nunca la composición de
// implementaciones. Ver docs/adr/0022-fase1.1-thread-manager-api-boundary.md.
