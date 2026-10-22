package com.yeivikas.olyze.eliner.api.audio

/** The four profiles named explicitly in the Fase 2 spec — no more, no fewer. */
enum class PerformanceProfile {
    /** Engine picks automatically, based on [DeviceCapabilities]. */
    AUTOMATIC,

    /** Favors stability over raw performance on lower-end/older devices. */
    COMPATIBILITY,

    /** Favors maximum performance on high-end devices, at higher resource cost. */
    ULTRA,

    /** User has overridden individual settings directly — this manager stops recommending. */
    MANUAL,
}
