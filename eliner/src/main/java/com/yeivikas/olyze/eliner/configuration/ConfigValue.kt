package com.yeivikas.olyze.eliner.configuration

/**
 * A typed configuration value. Sealed so [ConfigurationService]'s typed
 * getters can exhaustively match on it without a runtime cast.
 *
 * Only 4 primitive variants — enough for "buffers, calidad DSP, perfiles"
 * mentioned in the spec (ints, floats, flags, and identifiers/names are all
 * representable). No `ListValue`/`MapValue`/nested structures: nothing in
 * this phase needs them, and inventing a config value tree with no real
 * consumer would be exactly the "código de relleno" this phase forbids.
 * Add a variant when a real setting needs it, not before.
 */
sealed interface ConfigValue {
    data class IntValue(val value: Int) : ConfigValue
    data class FloatValue(val value: Float) : ConfigValue
    data class BooleanValue(val value: Boolean) : ConfigValue
    data class StringValue(val value: String) : ConfigValue
}
