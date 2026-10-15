package com.yeivikas.olyze.transport

import android.content.SharedPreferences

/**
 * Almacenamiento de las líneas de preset ya serializadas ([MetronomePresetCodec]). Una interfaz mínima
 * para que la [MetronomePresetLibrary] no dependa de Android y se pruebe en JVM con un doble en memoria.
 */
interface PresetStore {
    /** Todas las líneas guardadas, en orden. Vacío si no hay nada. */
    fun readAll(): List<String>

    /** Sustituye TODO lo guardado por [lines]. */
    fun writeAll(lines: List<String>)
}

/**
 * [PresetStore] sobre `SharedPreferences`: una sola clave con las líneas separadas por `\n` (el
 * formato del codec no contiene saltos de línea). `apply()` escribe en segundo plano: no bloquea la UI.
 */
class SharedPreferencesPresetStore(private val prefs: SharedPreferences) : PresetStore {

    override fun readAll(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    override fun writeAll(lines: List<String>) {
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "presets_v1"
    }
}
