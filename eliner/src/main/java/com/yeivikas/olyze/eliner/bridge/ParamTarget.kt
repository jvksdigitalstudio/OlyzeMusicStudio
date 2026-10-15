package com.yeivikas.olyze.eliner.bridge

import com.yeivikas.olyze.eliner.api.audio.EliNerAudioApi

/**
 * Identifica QUÉ control continuo se está actualizando y CÓMO aplicárselo a la API
 * de audio. Es la clave de coalescencia de [AudioCommandDispatcher] (§14,
 * "latest-value").
 *
 * Vive aparte del despachador porque es VOCABULARIO (qué parámetros admiten
 * coalescer y a qué método de [EliNerAudioApi] corresponde cada uno), mientras el
 * despachador es MECANISMO (hilo único, colas, vaciado). Añadir un parámetro
 * coalescible es tocar solo este archivo.
 *
 * `data object`/`data class` dan `equals`/`hashCode` estructurales gratis, necesarios
 * para que dos llamadas a, p. ej., `setModuleParameter(2, 5, ...)` colapsen sobre LA
 * MISMA entrada del mapa en vez de crear entradas distintas por identidad de objeto.
 */
internal sealed interface ParamTarget {
    fun apply(delegate: EliNerAudioApi, value: Float)

    data object MasterVolume : ParamTarget {
        override fun apply(delegate: EliNerAudioApi, value: Float) = delegate.setMasterVolume(value)
    }
    data object ReverbMix : ParamTarget {
        override fun apply(delegate: EliNerAudioApi, value: Float) = delegate.setReverbMix(value)
    }
    data object ReverbRoom : ParamTarget {
        override fun apply(delegate: EliNerAudioApi, value: Float) = delegate.setReverbRoom(value)
    }
    data object ReverbDamp : ParamTarget {
        override fun apply(delegate: EliNerAudioApi, value: Float) = delegate.setReverbDamp(value)
    }
    data object DelayMix : ParamTarget {
        override fun apply(delegate: EliNerAudioApi, value: Float) = delegate.setDelayMix(value)
    }
    data object DelayTime : ParamTarget {
        override fun apply(delegate: EliNerAudioApi, value: Float) = delegate.setDelayTime(value)
    }
    data object DelayFeedback : ParamTarget {
        override fun apply(delegate: EliNerAudioApi, value: Float) = delegate.setDelayFeedback(value)
    }
    data class ModuleParameter(val slot: Int, val paramId: Int) : ParamTarget {
        override fun apply(delegate: EliNerAudioApi, value: Float) = delegate.setModuleParameter(slot, paramId, value)
    }
}
