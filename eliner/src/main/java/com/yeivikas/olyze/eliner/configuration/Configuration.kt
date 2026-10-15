package com.yeivikas.olyze.eliner.configuration

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
