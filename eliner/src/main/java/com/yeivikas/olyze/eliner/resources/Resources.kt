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
