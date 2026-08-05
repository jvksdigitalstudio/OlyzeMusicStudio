package com.yeivikas.olyze.eliner.api

import android.content.Context
import com.yeivikas.olyze.eliner.configuration.Configuration
import com.yeivikas.olyze.eliner.configuration.ConfigurationService
import com.yeivikas.olyze.eliner.diagnostics.Logger
import com.yeivikas.olyze.eliner.diagnostics.LoggerService
import com.yeivikas.olyze.eliner.events.EventBus
import com.yeivikas.olyze.eliner.resources.ResourceManager
import com.yeivikas.olyze.eliner.resources.Resources
import com.yeivikas.olyze.eliner.services.CapabilityProvider
import com.yeivikas.olyze.eliner.services.DeviceCapabilityManager
import com.yeivikas.olyze.eliner.services.PerformanceProfileManager
import com.yeivikas.olyze.eliner.services.PerformanceProfileProvider
import com.yeivikas.olyze.eliner.services.TaskExecutor
import com.yeivikas.olyze.eliner.services.ThreadManager
import com.yeivikas.olyze.eliner.services.TimeProvider
import com.yeivikas.olyze.eliner.services.TimeService

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

/**
 * Builds a [RuntimeContext] wired with the real default implementation of
 * every Foundation Service. [applicationContext] is needed for exactly one
 * reason: [DeviceCapabilityManager] must query real device capabilities,
 * which requires a [Context] — pass `applicationContext`, never an
 * `Activity` context, to avoid leaking it (this factory holds no reference
 * to it beyond this call).
 *
 * This is a convenience, not a requirement — anything needing a
 * [RuntimeContext] built from different implementations (tests, a
 * different app) can construct one via [RuntimeContext]'s constructor
 * directly instead of calling this function.
 */
fun createDefaultRuntimeContext(applicationContext: Context): RuntimeContext {
    val capabilityManager = DeviceCapabilityManager(applicationContext)
    return RuntimeContext(
        logger = LoggerService(),
        configuration = ConfigurationService(),
        resources = ResourceManager(),
        events = EventBus(),
        timeProvider = TimeService(),
        capabilityProvider = capabilityManager,
        performanceProfileProvider = PerformanceProfileManager(capabilityManager),
        threadManager = ThreadManager(),
    )
}
