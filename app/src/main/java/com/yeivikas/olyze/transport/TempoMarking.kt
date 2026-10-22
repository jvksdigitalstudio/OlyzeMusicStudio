package com.yeivikas.olyze.transport

/**
 * Nombre italiano del tempo ("Allegro", "Andante"…) para un valor de BPM, como en los metrónomos y
 * los DAW. Solo presentación: función pura, sin estado.
 *
 * ## Tabla
 * Las convenciones históricas se SOLAPAN (Allegro se cita de 120 a 156, Moderato de 108 a 120…), así
 * que aquí se fija una partición SIN huecos ni solapes por límite inferior: cada tempo cae en
 * exactamente un nombre. Los límites siguen los valores habituales de los metrónomos mecánicos
 * (Maelzel) y los de los DAW:
 *
 * | Nombre | Desde (BPM) |
 * |---|---|
 * | Grave | 20 (por debajo de 40) |
 * | Largo | 40 |
 * | Larghetto | 60 |
 * | Adagio | 66 |
 * | Andante | 76 |
 * | Moderato | 108 |
 * | Allegro | 120 |
 * | Vivace | 156 |
 * | Presto | 176 |
 * | Prestissimo | 200 |
 *
 * El BPM es siempre en negras por minuto.
 */
object TempoMarking {

    private data class Band(val from: Float, val name: String)

    // Orden ASCENDENTE por límite inferior; la primera banda cubre todo lo que quede por debajo.
    private val bands = listOf(
        Band(Float.NEGATIVE_INFINITY, "Grave"),
        Band(40f, "Largo"),
        Band(60f, "Larghetto"),
        Band(66f, "Adagio"),
        Band(76f, "Andante"),
        Band(108f, "Moderato"),
        Band(120f, "Allegro"),
        Band(156f, "Vivace"),
        Band(176f, "Presto"),
        Band(200f, "Prestissimo"),
    )

    /** Nombre del tempo para [bpm]. Un valor no finito (NaN) se trata como el más lento. */
    fun forBpm(bpm: Float): String {
        if (bpm.isNaN()) return bands.first().name
        return bands.last { bpm >= it.from }.name
    }
}
