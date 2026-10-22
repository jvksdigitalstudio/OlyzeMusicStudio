package com.yeivikas.olyze.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Curva del Mute del tapper: silencio al empezar y entrada gradual (manual de FL Studio). */
class TapMuteTest {

    @Test fun `the click is silent for the first two taps`() {
        assertEquals(0f, TapMute.levelFor(0), 0f)
        assertEquals(0f, TapMute.levelFor(1), 0f)
        assertEquals(0f, TapMute.levelFor(2), 0f)
    }

    @Test fun `it fades in from the third tap and is back to normal at the ideal number of taps`() {
        assertEquals(1f / 6f, TapMute.levelFor(3), 1e-6f)
        assertEquals(0.5f, TapMute.levelFor(5), 1e-6f)
        assertEquals(1f, TapMute.levelFor(TapStatus.IDEAL_TAPS), 0f)
    }

    @Test fun `it never exceeds full level however many taps there are`() {
        assertEquals(1f, TapMute.levelFor(50), 0f)
        assertEquals(1f, TapMute.levelFor(Int.MAX_VALUE), 0f)
    }

    @Test fun `the level never goes down as taps accumulate and stays within 0 to 1`() {
        var last = -1f
        for (taps in 0..30) {
            val level = TapMute.levelFor(taps)
            assertTrue("taps=$taps", level in 0f..1f)
            assertTrue("el nivel baja en taps=$taps", level >= last)
            last = level
        }
    }

    @Test fun `negative counts are treated as silence`() {
        assertEquals(0f, TapMute.levelFor(-3), 0f)
    }
}
