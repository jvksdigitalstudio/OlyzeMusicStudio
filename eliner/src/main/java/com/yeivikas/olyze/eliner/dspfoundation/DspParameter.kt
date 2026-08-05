package com.yeivikas.olyze.eliner.dspfoundation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A single DSP parameter — every field the spec asks for: name,
 * identifier, current/default/min/max value, unit, and whether it's
 * automatable. Real validation, not a bag of fields: [currentValue],
 * [defaultValue] are guaranteed to sit within [minValue]..[maxValue] at
 * construction, and [withValue] always clamps rather than allowing an
 * out-of-range value through silently.
 */
data class DspParameter(
    val id: String,
    val name: String,
    val currentValue: Float,
    val defaultValue: Float,
    val minValue: Float,
    val maxValue: Float,
    val unit: String,
    val automatable: Boolean,
) {
    init {
        require(minValue <= maxValue) { "minValue ($minValue) must be <= maxValue ($maxValue) for parameter '$id'." }
        require(currentValue in minValue..maxValue) { "currentValue ($currentValue) out of range [$minValue, $maxValue] for parameter '$id'." }
        require(defaultValue in minValue..maxValue) { "defaultValue ($defaultValue) out of range [$minValue, $maxValue] for parameter '$id'." }
    }

    /** [value] clamped to [minValue]..[maxValue]. */
    fun clamp(value: Float): Float = value.coerceIn(minValue, maxValue)

    /** A copy of this parameter with [value] applied (clamped to range). */
    fun withValue(value: Float): DspParameter = copy(currentValue = clamp(value))
}

/** Published on [DspParameterManager.changes] whenever a parameter's value actually changes. */
data class DspParameterChange(val parameterId: String, val oldValue: Float, val newValue: Float)

/**
 * Registers and administers every [DspParameter] in the DSP subsystem —
 * "preparado para: Automatización, Presets, MIDI Learn, Control remoto,
 * Plugins." None of those five are implemented here; what's real is the
 * single mechanism all five will eventually drive through: [setValue] plus
 * [changes]. Automation would call `setValue` on a schedule; a preset
 * would call it once per saved parameter; MIDI Learn would map a CC number
 * to a parameter id and call `setValue` on each message — none of that
 * exists yet, but none of it needs `DspParameterManager` to change once it
 * does.
 */
class DspParameterManager {
    private val lock = Any()
    private val parameters = mutableMapOf<String, DspParameter>()

    private val _changes = MutableSharedFlow<DspParameterChange>(extraBufferCapacity = 64)
    val changes: SharedFlow<DspParameterChange> = _changes.asSharedFlow()

    /** Registers [parameter]. Throws if its id is already registered. */
    fun register(parameter: DspParameter) {
        synchronized(lock) {
            require(parameter.id !in parameters) { "Parameter '${parameter.id}' is already registered." }
            parameters[parameter.id] = parameter
        }
    }

    fun unregister(id: String): Boolean = synchronized(lock) { parameters.remove(id) != null }

    fun get(id: String): DspParameter? = synchronized(lock) { parameters[id] }

    fun getAll(): List<DspParameter> = synchronized(lock) { parameters.values.toList() }

    /**
     * Sets parameter [id] to [value] (clamped to its range). Returns
     * `false` if no parameter with [id] is registered. Publishes a
     * [DspParameterChange] on [changes] only if the clamped value is
     * actually different from the previous one.
     */
    fun setValue(id: String, value: Float): Boolean {
        val previous = synchronized(lock) { parameters[id] } ?: return false
        val updated = previous.withValue(value)
        synchronized(lock) { parameters[id] = updated }
        if (updated.currentValue != previous.currentValue) {
            _changes.tryEmit(DspParameterChange(id, previous.currentValue, updated.currentValue))
        }
        return true
    }

    /** Resets parameter [id] to its [DspParameter.defaultValue]. Returns `false` if no such parameter is registered. */
    fun resetToDefault(id: String): Boolean {
        val parameter = get(id) ?: return false
        return setValue(id, parameter.defaultValue)
    }
}
