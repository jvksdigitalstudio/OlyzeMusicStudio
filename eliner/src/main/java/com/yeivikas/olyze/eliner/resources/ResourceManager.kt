package com.yeivikas.olyze.eliner.resources

/**
 * Something that knows where certain resources live — e.g. a future
 * "bundled assets" provider, a future "user samples on disk" provider, a
 * future "downloaded content" provider. [ResourceManager] doesn't care how
 * a provider finds things, only that it can answer [locate].
 *
 * No implementation of this interface exists yet in this phase — that's
 * explicitly future work ("no implementar todavía carga de audio").
 */
interface ResourceProvider {
    /** Whether this provider is able to resolve resources of [category]. */
    fun supports(category: ResourceCategory): Boolean

    /** Resolves [id] to a location, or null if this provider doesn't have it. */
    fun locate(id: ResourceId): ResourceLocation?
}

/**
 * The contract [com.yeivikas.olyze.eliner.runtime.RuntimeContext] should
 * depend on instead of [ResourceManager] directly. Added in Fase 2.5, same
 * reasoning as `eliner.diagnostics.Logger`. Deliberately only exposes
 * [locate] — provider registration stays on the concrete [ResourceManager],
 * since only the composition root wires up providers, not general
 * consumers.
 */
interface Resources {
    fun locate(id: ResourceId): ResourceLocation?
}

/**
 * Coordinates [ResourceProvider]s and resolves [ResourceId]s to
 * [ResourceLocation]s. This is the "arquitectura y contratos" the spec
 * asks for — it does not open, read, or cache any actual resource bytes.
 * Once a real provider (e.g. a filesystem-backed sample provider) exists,
 * it registers here and this class needs no changes to support it.
 *
 * Providers are tried in registration order; the first one that resolves
 * an id wins. Thread-safe via `synchronized`, same rationale as
 * [com.yeivikas.olyze.eliner.core.ModuleRegistry]: registration isn't a
 * real-time-audio-thread operation.
 */
class ResourceManager : Resources {
    private val lock = Any()
    private val providers = mutableListOf<ResourceProvider>()

    fun registerProvider(provider: ResourceProvider) = synchronized(lock) {
        providers.add(provider)
    }

    fun unregisterProvider(provider: ResourceProvider): Boolean = synchronized(lock) {
        providers.remove(provider)
    }

    /** Resolves [id] using the first registered provider that supports its category and has it. */
    override fun locate(id: ResourceId): ResourceLocation? {
        val providersSnapshot = synchronized(lock) { providers.toList() }
        for (provider in providersSnapshot) {
            if (!provider.supports(id.category)) continue
            provider.locate(id)?.let { return it }
        }
        return null
    }

    /** Number of currently registered providers. */
    fun providerCount(): Int = synchronized(lock) { providers.size }
}
