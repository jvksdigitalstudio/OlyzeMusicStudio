package com.yeivikas.olyze.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Colección persistente de presets del metrónomo: guardar, listar y borrar.
 *
 * ## Responsabilidad única
 * Las REGLAS de la colección (sin duplicados, tope, orden) y su persistencia a través de un
 * [PresetStore]. No conoce el motor (aplicar un preset es [applyTo]), ni la UI, ni Android.
 *
 * ## Reglas
 *  - **Sin duplicados:** un preset idéntico a uno existente no se vuelve a guardar.
 *  - **Tope de [maxPresets]:** al llegar al máximo no se guarda otro (hay que borrar antes); nunca se
 *    descarta uno en silencio.
 *  - **Orden de guardado:** el más reciente al final.
 *  - **Lo corrupto se descarta:** al cargar, una línea que no decodifica se ignora; la lista se
 *    reescribe limpia en el siguiente cambio.
 *
 * ## Hilos
 * Sin sincronización: se usa desde el hilo principal de la UI.
 */
class MetronomePresetLibrary(
    private val store: PresetStore,
    val maxPresets: Int = DEFAULT_MAX_PRESETS,
) {
    private val _presets = MutableStateFlow(store.readAll().mapNotNull(MetronomePresetCodec::decode).take(maxPresets))

    /** Los presets guardados, del más antiguo al más reciente. */
    val presets: StateFlow<List<MetronomePreset>> = _presets.asStateFlow()

    /** `true` si [preset] se puede guardar ahora: no está ya guardado y queda hueco. */
    fun canAdd(preset: MetronomePreset): Boolean = preset !in _presets.value && _presets.value.size < maxPresets

    /** Guarda [preset] si [canAdd]. Devuelve si se guardó. */
    fun add(preset: MetronomePreset): Boolean {
        if (!canAdd(preset)) return false
        update(_presets.value + preset)
        return true
    }

    /** Borra [preset] si está guardado. */
    fun remove(preset: MetronomePreset) {
        if (preset in _presets.value) update(_presets.value - preset)
    }

    private fun update(list: List<MetronomePreset>) {
        _presets.value = list
        store.writeAll(list.map(MetronomePresetCodec::encode))
    }

    companion object {
        const val DEFAULT_MAX_PRESETS = 20
    }
}
