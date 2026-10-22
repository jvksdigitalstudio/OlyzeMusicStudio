package com.yeivikas.olyze.eliner.api.transport

/**
 * Intensidad de un tiempo del compás. Debe coincidir con `eliner::BeatLevel`
 * (eliner/include/eliner/transport/ClickSound.h); [nativeId] es lo que cruza al motor y
 * `TransportConstantsMatchNativeTest` lo comprueba contra ese header.
 */
enum class BeatLevel(val nativeId: Int) {
    /** El tiempo no suena (ni sus subdivisiones). */
    MUTE(0),

    /** Suave. */
    SOFT(1),

    /** Normal. */
    MEDIUM(2),

    /** Acento: más fuerte y más agudo. */
    ACCENT(3),
}

/**
 * Patrón de intensidad por tiempo del metrónomo: el nivel ([BeatLevel]) de cada uno de los hasta
 * [MAX_BEATS] tiempos del compás. Inmutable.
 *
 * Siempre tiene [MAX_BEATS] entradas aunque el compás tenga menos tiempos: cambiar de compás no pierde lo
 * que el usuario puso en los tiempos que vuelven a aparecer, y el motor no tiene que saber la longitud.
 *
 * ## Empaquetado hacia el motor
 * 2 bits por tiempo, 8 tiempos por palabra: [packedLow] = tiempos 0–7 y [packedHigh] = 8–15 (16 bits cada
 * una, positivas como `Int`). Viajan juntas en UN comando, así que el motor nunca aplica un patrón a
 * medias. El formato es el de `ClickSound.h` (`beatLevelOf`).
 */
class BeatPattern private constructor(private val levels: List<BeatLevel>) {

    /** Nivel del tiempo [beat]. Un tiempo fuera de 0..15 devuelve [BeatLevel.MEDIUM]. */
    operator fun get(beat: Int): BeatLevel = levels.getOrNull(beat) ?: BeatLevel.MEDIUM

    /** Copia con el tiempo [beat] en [level]. Un tiempo fuera de 0..15 devuelve este mismo patrón. */
    fun with(beat: Int, level: BeatLevel): BeatPattern {
        if (beat !in 0 until MAX_BEATS || levels[beat] == level) return this
        return BeatPattern(levels.toMutableList().also { it[beat] = level })
    }

    /** Tiempos 0–7 empaquetados (16 bits). */
    val packedLow: Int get() = pack(0)

    /** Tiempos 8–15 empaquetados (16 bits). */
    val packedHigh: Int get() = pack(BEATS_PER_WORD)

    private fun pack(from: Int): Int {
        var word = 0
        for (i in 0 until BEATS_PER_WORD) word = word or (levels[from + i].nativeId shl (BITS_PER_BEAT * i))
        return word
    }

    override fun equals(other: Any?) = other is BeatPattern && other.levels == levels
    override fun hashCode() = levels.hashCode()
    override fun toString() = "BeatPattern(${levels.joinToString("") { it.nativeId.toString() }})"

    companion object {
        /** Tiempos que admite un patrón: igual que `EliNerTransportApi.MAX_BEATS_PER_BAR`. */
        const val MAX_BEATS: Int = 16

        // Formato empaquetado: DEBE coincidir con ClickSound.h (lo comprueba el test de contrato).
        internal const val BITS_PER_BEAT: Int = 2
        internal const val BEATS_PER_WORD: Int = 8

        /** Primer tiempo acentuado y el resto normales: el sonido histórico del metrónomo. */
        val DEFAULT: BeatPattern = of(listOf(BeatLevel.ACCENT))

        /** Patrón con [levels] (los tiempos que falten, normales; los que sobren, descartados). */
        fun of(levels: List<BeatLevel>): BeatPattern =
            BeatPattern(List(MAX_BEATS) { levels.getOrNull(it) ?: BeatLevel.MEDIUM })

        /** Inversa de [packedLow]/[packedHigh]. Un valor de nivel desconocido se lee como normal. */
        fun fromPacked(low: Int, high: Int): BeatPattern = of(
            List(MAX_BEATS) { beat ->
                val word = if (beat < BEATS_PER_WORD) low else high
                val id = (word shr (BITS_PER_BEAT * (beat % BEATS_PER_WORD))) and 0x3
                BeatLevel.values().first { it.nativeId == id }
            },
        )

        /**
         * Patrón por defecto de un compás. Acento en el primer tiempo y el resto normales, salvo en los
         * compases COMPUESTOS (pulso de corchea o semicorchea, 6 o más pulsos en grupos de tres: 6/8, 9/8,
         * 12/8, 12/16…), que agrupan de tres en tres: acento al inicio, normal al comienzo de cada otro
         * grupo y suave en el resto (6/8 = ● ∙ ∙ ◐ ∙ ∙).
         */
        fun defaultFor(beatsPerBar: Int, unit: BeatUnit): BeatPattern {
            val compound = (unit == BeatUnit.EIGHTH || unit == BeatUnit.SIXTEENTH) &&
                beatsPerBar >= 6 && beatsPerBar % 3 == 0
            if (!compound) return DEFAULT
            return of(
                List(beatsPerBar.coerceAtMost(MAX_BEATS)) { beat ->
                    when {
                        beat == 0 -> BeatLevel.ACCENT
                        beat % 3 == 0 -> BeatLevel.MEDIUM
                        else -> BeatLevel.SOFT
                    }
                },
            )
        }
    }
}
