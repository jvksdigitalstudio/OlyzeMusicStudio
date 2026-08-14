package com.yeivikas.olyze.eliner.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §41: "MIDI Event validation" — the `init` block's `require()` calls
 *  in [MidiEvent], and the content-based `equals`/`hashCode` override
 *  (needed because a `ByteArray` field would otherwise get identity-based
 *  equality from the default data class implementation — a real
 *  correctness bug for anything that compares events). See
 *  [MidiStreamParserTest] for the NOT-EXECUTED disclaimer; applies here too. */
class MidiEventTest {

    private fun baseEvent(
        type: MidiEventType = MidiEventType.NOTE_ON,
        channel: Int? = 0,
        data1: Int = 60,
        data2: Int = 100,
        pitchBendValue: Int = 8192,
        sysex: ByteArray? = null,
    ) = MidiEvent(
        type = type,
        channel = channel,
        data1 = data1,
        data2 = data2,
        pitchBendValue = pitchBendValue,
        sysex = sysex,
        timestampNanos = 0L,
        sourcePortId = "p1",
    )

    @Test
    fun `valid channel-voice event constructs fine`() {
        baseEvent() // no exception = pass
    }

    @Test
    fun `null channel is valid for system messages`() {
        baseEvent(type = MidiEventType.CLOCK, channel = null, data1 = 0, data2 = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `channel above 15 is rejected`() {
        baseEvent(channel = 16)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `channel below 0 is rejected`() {
        baseEvent(channel = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `data1 above 127 is rejected`() {
        baseEvent(data1 = 128)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `data2 below 0 is rejected`() {
        baseEvent(data2 = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pitchBendValue above 16383 is rejected`() {
        baseEvent(pitchBendValue = 16384)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SYSTEM_EXCLUSIVE without a sysex payload is rejected`() {
        baseEvent(type = MidiEventType.SYSTEM_EXCLUSIVE, channel = null, sysex = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sysex payload over MAX_SYSEX_BYTES is rejected`() {
        baseEvent(
            type = MidiEventType.SYSTEM_EXCLUSIVE,
            channel = null,
            sysex = ByteArray(MidiEvent.MAX_SYSEX_BYTES + 1),
        )
    }

    @Test
    fun `sysex payload at exactly the cap is accepted`() {
        baseEvent(type = MidiEventType.SYSTEM_EXCLUSIVE, channel = null, sysex = ByteArray(MidiEvent.MAX_SYSEX_BYTES))
    }

    @Test
    fun `equals is content-based for sysex, not identity-based`() {
        val a = baseEvent(type = MidiEventType.SYSTEM_EXCLUSIVE, channel = null, sysex = byteArrayOf(1, 2, 3))
        val b = baseEvent(type = MidiEventType.SYSTEM_EXCLUSIVE, channel = null, sysex = byteArrayOf(1, 2, 3))
        // Two distinct ByteArray instances with the same contents — the
        // default data-class equals would say `false` here (array
        // identity); the override must say `true`.
        assertTrue(a == b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals distinguishes different sysex contents`() {
        val a = baseEvent(type = MidiEventType.SYSTEM_EXCLUSIVE, channel = null, sysex = byteArrayOf(1, 2, 3))
        val b = baseEvent(type = MidiEventType.SYSTEM_EXCLUSIVE, channel = null, sysex = byteArrayOf(1, 2, 4))
        assertFalse(a == b)
    }

    @Test
    fun `equals treats two null-sysex events as equal when other fields match`() {
        assertEquals(baseEvent(), baseEvent())
    }
}
