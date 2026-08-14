package com.yeivikas.olyze.eliner.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** §41: "parameter binding". Tests [MidiParameterBinding.evaluate] — pure
 *  math, no side effects, no Android — and the constructor's own
 *  `require()` validation. See [MidiStreamParserTest] for the
 *  NOT-EXECUTED disclaimer; applies here too. */
class MidiParameterBindingTest {

    private fun binding(
        outputMin: Float = 0f,
        outputMax: Float = 1f,
        invert: Boolean = false,
        curve: MidiBindingCurve = MidiBindingCurve.LINEAR,
    ) = MidiParameterBinding(
        id = "b1",
        sourceDeviceId = "d1",
        channel = 0,
        controller = 7,
        targetParameterId = "reverb.mix",
        outputMin = outputMin,
        outputMax = outputMax,
        invert = invert,
        curve = curve,
    )

    @Test
    fun `linear - zero maps to outputMin`() {
        assertEquals(0f, binding(outputMin = 0f, outputMax = 1f).evaluate(0), 0.0001f)
    }

    @Test
    fun `linear - max maps to outputMax`() {
        assertEquals(1f, binding(outputMin = 0f, outputMax = 1f).evaluate(127), 0.0001f)
    }

    @Test
    fun `linear - midpoint is proportionally between min and max`() {
        // 64/127 ≈ 0.5039
        val result = binding(outputMin = 0f, outputMax = 100f).evaluate(64)
        assertEquals(50.39f, result, 0.1f)
    }

    @Test
    fun `linear - respects a non-zero outputMin`() {
        assertEquals(20f, binding(outputMin = 20f, outputMax = 20f).evaluate(64), 0.0001f)
    }

    @Test
    fun `invert flips the direction`() {
        val b = binding(outputMin = 0f, outputMax = 1f, invert = true)
        assertEquals(1f, b.evaluate(0), 0.0001f)
        assertEquals(0f, b.evaluate(127), 0.0001f)
    }

    @Test
    fun `exponential curve - endpoints match linear, midpoint does not`() {
        val b = binding(outputMin = 0f, outputMax = 1f, curve = MidiBindingCurve.EXPONENTIAL)
        assertEquals(0f, b.evaluate(0), 0.0001f)
        assertEquals(1f, b.evaluate(127), 0.0001f)
        // (64/127)^2 ≈ 0.254 — meaningfully below the linear 0.504 midpoint.
        val mid = b.evaluate(64)
        assertEquals(0.254f, mid, 0.01f)
    }

    @Test
    fun `logarithmic curve - endpoints match linear, midpoint does not`() {
        val b = binding(outputMin = 0f, outputMax = 1f, curve = MidiBindingCurve.LOGARITHMIC)
        assertEquals(0f, b.evaluate(0), 0.0001f)
        assertEquals(1f, b.evaluate(127), 0.0001f)
        // sqrt(64/127) ≈ 0.710 — above the linear 0.504 midpoint.
        val mid = b.evaluate(64)
        assertEquals(0.710f, mid, 0.01f)
    }

    @Test
    fun `evaluate rejects out-of-range value`() {
        assertThrows(IllegalArgumentException::class.java) { binding().evaluate(128) }
        assertThrows(IllegalArgumentException::class.java) { binding().evaluate(-1) }
    }

    @Test
    fun `constructor rejects channel out of range`() {
        assertThrows(IllegalArgumentException::class.java) {
            MidiParameterBinding("b", "d", channel = 16, controller = 1, targetParameterId = "x", outputMin = 0f, outputMax = 1f)
        }
    }

    @Test
    fun `constructor rejects controller out of range`() {
        assertThrows(IllegalArgumentException::class.java) {
            MidiParameterBinding("b", "d", channel = 0, controller = 128, targetParameterId = "x", outputMin = 0f, outputMax = 1f)
        }
    }

    @Test
    fun `constructor rejects outputMin greater than outputMax`() {
        assertThrows(IllegalArgumentException::class.java) {
            MidiParameterBinding("b", "d", channel = 0, controller = 1, targetParameterId = "x", outputMin = 5f, outputMax = 1f)
        }
    }
}
