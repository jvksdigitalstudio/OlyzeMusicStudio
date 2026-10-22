package com.yeivikas.olyze.ui.components.piano

/**
 * Cuenta cuántos dedos mantienen cada nota SONANDO, para que el motor reciba
 * `noteOn` solo en la transición 0→1 y `noteOff` solo en la 1→0.
 *
 * ## Por qué existe
 * En modo de doble fila la MISMA nota MIDI puede sonar desde las dos tiras a la
 * vez (la tecla N de la inferior y la N-12 de la superior suenan N: tocar octavas
 * a través de la división es el objetivo del modo). Dos dedos pueden, por tanto,
 * mantener legítimamente la misma nota. Sin este contador, soltar UNO de los dos
 * enviaría un `noteOff` crudo que corta la nota aunque el otro dedo siga
 * pulsado (el motor libera toda voz con ese canal+nota, no "una pulsación").
 *
 * ## Responsabilidad
 * Solo CONTABILIDAD pura: no conoce Compose, ni el motor, ni callbacks. Devuelve
 * si hay que reenviar el evento; quien lo llama decide a quién (así los
 * callbacks pueden cambiar entre recomposiciones sin quedar obsoletos).
 *
 * ## Hilos
 * Sin sincronización: se usa desde el hilo principal de la UI.
 */
internal class NoteHoldTracker {

    private val counts = mutableMapOf<Int, Int>()

    /** `true` si no hay ninguna nota mantenida según este contador. */
    val isEmpty: Boolean get() = counts.isEmpty()

    /**
     * Registra una pulsación de [note]. Devuelve `true` si es la PRIMERA (0→1) y por
     * tanto hay que enviar `noteOn`; `false` si la nota ya sonaba por otro dedo.
     */
    fun press(note: Int): Boolean {
        val count = (counts[note] ?: 0) + 1
        counts[note] = count
        return count == 1
    }

    /**
     * Registra la liberación de [note]. Devuelve `true` si era la ÚLTIMA (1→0) y
     * hay que enviar `noteOff`; `false` si otro dedo sigue manteniéndola.
     *
     * Una liberación de una nota que este contador no conocía devuelve `true`: se
     * reenvía el `noteOff` en vez de tragárselo, que es el lado seguro (peor caso,
     * un `noteOff` redundante; lo contrario dejaría una nota colgada).
     */
    fun release(note: Int): Boolean {
        val count = (counts[note] ?: 1) - 1
        return if (count <= 0) {
            counts.remove(note)
            true
        } else {
            counts[note] = count
            false
        }
    }

    /**
     * Olvida TODAS las notas mantenidas y las devuelve, para que el llamador les
     * envíe `noteOff`. Se usa cuando el cambio de modo de fila destruye las tiras
     * mientras un dedo sostiene una tecla: sin esto la nota quedaría colgada. Solo
     * afecta a lo que ESTE contador cree mantenido: no es un "all notes off" global.
     */
    fun releaseAll(): List<Int> {
        val held = counts.keys.toList()
        counts.clear()
        return held
    }
}
