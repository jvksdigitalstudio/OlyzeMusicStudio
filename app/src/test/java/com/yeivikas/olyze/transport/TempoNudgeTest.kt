package com.yeivikas.olyze.transport

import com.yeivikas.olyze.eliner.api.transport.EliNerTransportApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nudge: tempo TEMPORAL mientras se mantiene pulsado (manual de FL Studio); la restauración la hace el ViewModel. */
class TempoNudgeTest {

    @Test fun `nudging up adds the fixed fraction to the base tempo`() {
        assertEquals(120f * 1.04f, TempoNudge.target(120f, 1), 1e-4f)
    }

    @Test fun `nudging down subtracts the fixed fraction from the base tempo`() {
        assertEquals(120f * 0.96f, TempoNudge.target(120f, -1), 1e-4f)
    }

    @Test fun `direction zero leaves the tempo untouched`() {
        assertEquals(133.3f, TempoNudge.target(133.3f, 0), 0f)
    }

    @Test fun `any positive or negative direction counts as one step - the amount does not scale`() {
        assertEquals(TempoNudge.target(100f, 1), TempoNudge.target(100f, 7), 0f)
        assertEquals(TempoNudge.target(100f, -1), TempoNudge.target(100f, -9), 0f)
    }

    @Test fun `the nudged tempo never leaves the engine range`() {
        assertEquals(EliNerTransportApi.MAX_TEMPO_BPM, TempoNudge.target(EliNerTransportApi.MAX_TEMPO_BPM, 1), 0f)
        assertEquals(EliNerTransportApi.MIN_TEMPO_BPM, TempoNudge.target(EliNerTransportApi.MIN_TEMPO_BPM, -1), 0f)
    }

    @Test fun `the effect is relative, so it scales with the tempo`() {
        val slow = TempoNudge.target(60f, 1) - 60f
        val fast = TempoNudge.target(240f, 1) - 240f
        assertEquals(4f, fast / slow, 1e-3f)
    }

    @Test fun `the nudge is audible but small - between 1 and 10 percent`() {
        assertTrue(TempoNudge.FRACTION in 0.01f..0.10f)
    }
}
