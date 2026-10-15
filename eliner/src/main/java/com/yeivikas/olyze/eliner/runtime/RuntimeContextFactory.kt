package com.yeivikas.olyze.eliner.runtime

import android.app.Application
import com.yeivikas.olyze.eliner.configuration.ConfigurationService
import com.yeivikas.olyze.eliner.diagnostics.LoggerService
import com.yeivikas.olyze.eliner.events.EventBus
import com.yeivikas.olyze.eliner.resources.ResourceManager
import com.yeivikas.olyze.eliner.services.DeviceCapabilityManager
import com.yeivikas.olyze.eliner.services.PerformanceProfileManager
import com.yeivikas.olyze.eliner.services.ThreadManager
import com.yeivikas.olyze.eliner.services.TimeService

/**
 * Builds a [RuntimeContext] wired with the real default implementation of
 * every Foundation Service. [applicationContext] is needed for exactly one
 * reason: [DeviceCapabilityManager] must query real device capabilities,
 * which requires an [Application] context — pass `applicationContext`, never an
 * `Activity` context, to avoid leaking it (this factory holds no reference
 * to it beyond this call).
 *
 * This is a convenience, not a requirement — anything needing a
 * [RuntimeContext] built from different implementations (tests, a
 * different app) can construct one via [RuntimeContext]'s constructor
 * directly instead of calling this function.
 *
 * **Ownership:** the caller of this function owns the returned context's
 * [RuntimeContext.threadManager] and is responsible for calling
 * [com.yeivikas.olyze.eliner.services.TaskExecutor.shutdown] on it exactly
 * once, when this context is being discarded for good.
 * [EliNerRuntime] deliberately does NOT
 * do this itself — see its `shutdown()` doc comment for the lifecycle bug
 * (A-1) that caused, and why the fix is this explicit ownership split
 * rather than `EliNerRuntime` shutting down a dependency it was only lent.
 */
fun createDefaultRuntimeContext(applicationContext: Application): RuntimeContext {
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
