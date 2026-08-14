package com.yeivikas.olyze.eliner.configuration

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Where [ConfigurationService] actually stores values. An interface, not a
 * concrete class, on purpose: the spec explicitly says this layer must NOT
 * depend on `SharedPreferences` or the UI. [InMemoryConfigurationStore] is
 * the only implementation that exists today; a future persisted store
 * (backed by DataStore, a file, whatever `:app` decides) can be injected
 * later without `eliner.configuration` ever importing anything Android-UI
 * related.
 */
interface ConfigurationStore {
    fun get(key: String): ConfigValue?
    fun set(key: String, value: ConfigValue)
    fun remove(key: String)
    fun contains(key: String): Boolean
    fun keys(): Set<String>
}

/** Default, non-persisted [ConfigurationStore]. Thread-safe, in-process only. */
class InMemoryConfigurationStore : ConfigurationStore {
    private val lock = Any()
    private val values = mutableMapOf<String, ConfigValue>()

    override fun get(key: String): ConfigValue? = synchronized(lock) { values[key] }
    override fun set(key: String, value: ConfigValue) = synchronized(lock) { values[key] = value }
    override fun remove(key: String) { synchronized(lock) { values.remove(key) } }
    override fun contains(key: String): Boolean = synchronized(lock) { key in values }
    override fun keys(): Set<String> = synchronized(lock) { values.keys.toSet() }
}

/** A [key] whose value changed, published on [ConfigurationService.changes]. */
data class ConfigChange(
    val key: String,
    val oldValue: ConfigValue?,
    val newValue: ConfigValue?,
)

/**
 * The contract [com.yeivikas.olyze.eliner.runtime.RuntimeContext] should
 * depend on instead of [ConfigurationService] directly. Added in Fase 2.5,
 * same reasoning as `eliner.diagnostics.Logger`.
 */
interface Configuration {
    fun getInt(key: String, default: Int): Int
    fun getFloat(key: String, default: Float): Float
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String): String
    fun setInt(key: String, value: Int)
    fun setFloat(key: String, value: Float)
    fun setBoolean(key: String, value: Boolean)
    fun setString(key: String, value: String)
    fun contains(key: String): Boolean
    fun keys(): Set<String>
    fun remove(key: String)
}

/**
 * Central configuration service for everything EliNer will need to
 * configure: engine preferences, buffer sizes, DSP quality, performance
 * profiles, project defaults. This phase doesn't define any of those keys
 * itself — that's for whichever future module owns that setting — it only
 * provides the mechanism: typed get/set backed by a swappable
 * [ConfigurationStore], with change notifications.
 *
 * Zero Android imports. Zero dependency on any other Foundation Service.
 */
class ConfigurationService(private val store: ConfigurationStore = InMemoryConfigurationStore()) : Configuration {
    private val _changes = MutableSharedFlow<ConfigChange>(extraBufferCapacity = 32)
    val changes: SharedFlow<ConfigChange> = _changes.asSharedFlow()

    override fun getInt(key: String, default: Int): Int =
        (store.get(key) as? ConfigValue.IntValue)?.value ?: default

    override fun getFloat(key: String, default: Float): Float =
        (store.get(key) as? ConfigValue.FloatValue)?.value ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        (store.get(key) as? ConfigValue.BooleanValue)?.value ?: default

    override fun getString(key: String, default: String): String =
        (store.get(key) as? ConfigValue.StringValue)?.value ?: default

    override fun setInt(key: String, value: Int) = set(key, ConfigValue.IntValue(value))
    override fun setFloat(key: String, value: Float) = set(key, ConfigValue.FloatValue(value))
    override fun setBoolean(key: String, value: Boolean) = set(key, ConfigValue.BooleanValue(value))
    override fun setString(key: String, value: String) = set(key, ConfigValue.StringValue(value))

    override fun contains(key: String): Boolean = store.contains(key)
    override fun keys(): Set<String> = store.keys()

    override fun remove(key: String) {
        val old = store.get(key)
        if (old == null) return
        store.remove(key)
        _changes.tryEmit(ConfigChange(key, oldValue = old, newValue = null))
    }

    private fun set(key: String, value: ConfigValue) {
        val old = store.get(key)
        if (old == value) return // no-op writes shouldn't spam `changes`
        store.set(key, value)
        _changes.tryEmit(ConfigChange(key, oldValue = old, newValue = value))
    }
}
