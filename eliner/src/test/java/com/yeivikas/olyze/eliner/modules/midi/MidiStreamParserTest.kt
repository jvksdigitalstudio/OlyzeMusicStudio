package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.MidiEvent
import com.yeivikas.olyze.eliner.api.MidiEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §41: "MIDI Event parsing" is listed first among test priorities — this
 * is that test. Pure Kotlin, zero `android.*` dependency, matching §24's
 * "el Core pueda probarse independientemente de UI" (and, more directly
 * relevant here, independientemente de Android entirely — this doesn't
 * even touch `android.media.midi`).
 *
 * NOTE per this project's own anti-fabrication rule (§45/§38): these
 * tests were written and reviewed for logical correctness against the
 * MIDI spec, but this development environment has no JVM/Gradle test
 * runner available (no network/NDK — same limitation documented in every
 * build-status section of this project's reports). They have **not**
 * been executed. Whoever runs `./gradlew :eliner:testDebugUnitTest`
 * first should treat that as the actual verification, not this file's
 * existence.
 */
class MidiStreamParserTest {

    private fun feed(parser: MidiStreamParser, vararg bytes: Int): List<MidiEvent> {
        val out = mutableListOf<MidiEvent>()
        val buf = bytes.map { it.toByte() }.toByteArray()
        parser.feed(buf, 0, buf.size, timestampNanos = 1000L, onEvent = { out.add(it) })
        return out
    }

    @Test
    fun `simple Note On`() {
        val events = feed(MidiStreamParser("p1"), 0x90, 60, 100)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(MidiEventType.NOTE_ON, e.type)
        assertEquals(0, e.channel)
        assertEquals(60, e.data1)
        assertEquals(100, e.data2)
        assertEquals("p1", e.sourcePortId)
        assertEquals(1000L, e.timestampNanos)
    }

    @Test
    fun `running status - three notes, one status byte`() {
        // A compliant keyboard omitting the repeated 0x90 — the exact
        // case this parser exists to handle correctly (class doc, point 1).
        val events = feed(MidiStreamParser("p1"), 0x90, 60, 100, 62, 101, 64, 102)
        assertEquals(3, events.size)
        assertEquals(listOf(60, 62, 64), events.map { it.data1 })
        assertEquals(listOf(100, 101, 102), events.map { it.data2 })
        assertTrue(events.all { it.type == MidiEventType.NOTE_ON })
    }

    @Test
    fun `Note On with velocity 0 is Note Off`() {
        val events = feed(MidiStreamParser("p1"), 0x90, 60, 0)
        assertEquals(1, events.size)
        assertEquals(MidiEventType.NOTE_OFF, events[0].type)
    }

    @Test
    fun `explicit Note Off`() {
        val events = feed(MidiStreamParser("p1"), 0x80, 60, 64)
        assertEquals(1, events.size)
        assertEquals(MidiEventType.NOTE_OFF, events[0].type)
        assertEquals(64, events[0].data2)
    }

    @Test
    fun `control change`() {
        val events = feed(MidiStreamParser("p1"), 0xB1, 7, 127)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(MidiEventType.CONTROL_CHANGE, e.type)
        assertEquals(1, e.channel)
        assertEquals(7, e.data1)
        assertEquals(127, e.data2)
    }

    @Test
    fun `program change - single data byte, data2 stays zero`() {
        val events = feed(MidiStreamParser("p1"), 0xC2, 5)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(MidiEventType.PROGRAM_CHANGE, e.type)
        assertEquals(2, e.channel)
        assertEquals(5, e.data1)
        assertEquals(0, e.data2)
    }

    @Test
    fun `channel aftertouch - single data byte`() {
        val events = feed(MidiStreamParser("p1"), 0xD0, 90)
        assertEquals(1, events.size)
        assertEquals(MidiEventType.CHANNEL_AFTERTOUCH, events[0].type)
        assertEquals(90, events[0].data1)
    }

    @Test
    fun `polyphonic aftertouch`() {
        val events = feed(MidiStreamParser("p1"), 0xA3, 60, 50)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(MidiEventType.POLYPHONIC_AFTERTOUCH, e.type)
        assertEquals(3, e.channel)
        assertEquals(60, e.data1)
        assertEquals(50, e.data2)
    }

    @Test
    fun `pitch bend - center value`() {
        // 14-bit center = 8192 = 0x2000 -> LSB 0x00, MSB 0x40
        val events = feed(MidiStreamParser("p1"), 0xE0, 0x00, 0x40)
        assertEquals(1, events.size)
        assertEquals(MidiEventType.PITCH_BEND, events[0].type)
        assertEquals(8192, events[0].pitchBendValue)
    }

    @Test
    fun `pitch bend - max value`() {
        val events = feed(MidiStreamParser("p1"), 0xE0, 0x7F, 0x7F)
        assertEquals(16383, events[0].pitchBendValue)
    }

    @Test
    fun `realtime clock byte interleaved mid-message does not corrupt it`() {
        // §6/§34 point 2: 0xF8 lands between the two data bytes of a Note
        // On — must emit CLOCK immediately, then still correctly complete
        // the interrupted Note On afterward.
        val events = feed(MidiStreamParser("p1"), 0x90, 60, 0xF8, 100)
        assertEquals(2, events.size)
        assertEquals(MidiEventType.CLOCK, events[0].type)
        assertEquals(MidiEventType.NOTE_ON, events[1].type)
        assertEquals(60, events[1].data1)
        assertEquals(100, events[1].data2)
    }

    @Test
    fun `start stop continue`() {
        assertEquals(MidiEventType.START, feed(MidiStreamParser("p1"), 0xFA)[0].type)
        assertEquals(MidiEventType.STOP, feed(MidiStreamParser("p1"), 0xFC)[0].type)
        assertEquals(MidiEventType.CONTINUE, feed(MidiStreamParser("p1"), 0xFB)[0].type)
    }

    @Test
    fun `active sensing and reset map to SYSTEM_OTHER, not dropped silently`() {
        assertEquals(MidiEventType.SYSTEM_OTHER, feed(MidiStreamParser("p1"), 0xFE)[0].type)
        assertEquals(MidiEventType.SYSTEM_OTHER, feed(MidiStreamParser("p1"), 0xFF)[0].type)
    }

    @Test
    fun `sysex - simple payload`() {
        val events = feed(MidiStreamParser("p1"), 0xF0, 0x7E, 0x00, 0xF7)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(MidiEventType.SYSTEM_EXCLUSIVE, e.type)
        assertEquals(listOf<Byte>(0x7E, 0x00), e.sysex!!.toList())
    }

    @Test
    fun `sysex - oversized payload is dropped entirely, not truncated`() {
        val bytes = mutableListOf(0xF0)
        repeat(MidiEvent.MAX_SYSEX_BYTES + 1) { bytes.add(0x01) }
        bytes.add(0xF7)
        val events = feed(MidiStreamParser("p1"), *bytes.toIntArray())
        // §19/§34: must not emit a truncated/partial SysEx event.
        assertTrue(events.isEmpty())
    }

    @Test
    fun `stray data byte with no active status is dropped defensively`() {
        val events = feed(MidiStreamParser("p1"), 60, 100) // no status byte at all
        assertTrue(events.isEmpty())
    }

    @Test
    fun `running status persists across separate feed() calls`() {
        // A single MIDI message can legitimately arrive split across two
        // separate onSend() callbacks — parser state must survive that.
        val parser = MidiStreamParser("p1")
        val out = mutableListOf<MidiEvent>()
        val onEvent: (MidiEvent) -> Unit = { out.add(it) }

        val buf1 = byteArrayOf(0x90.toByte(), 60)
        parser.feed(buf1, 0, buf1.size, 1000L, onEvent)
        assertTrue("no event should be complete yet", out.isEmpty())

        val buf2 = byteArrayOf(100)
        parser.feed(buf2, 0, buf2.size, 2000L, onEvent)
        assertEquals(1, out.size)
        assertEquals(MidiEventType.NOTE_ON, out[0].type)
        assertEquals(60, out[0].data1)
        assertEquals(100, out[0].data2)
    }

    @Test
    fun `two parser instances do not share state`() {
        // Class doc: one instance per connection — a stray running-status
        // byte on one connection must never leak into another's parsing.
        val a = MidiStreamParser("a")
        val b = MidiStreamParser("b")
        feed(a, 0x90, 60) // half a Note On on connection A, never completed.
        val eventsB = feed(b, 100) // a lone data byte arriving fresh on connection B.
        assertTrue("connection B has no running status of its own", eventsB.isEmpty())
    }
}
