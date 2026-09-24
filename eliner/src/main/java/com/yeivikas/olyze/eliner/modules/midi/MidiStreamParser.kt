package com.yeivikas.olyze.eliner.modules.midi

import com.yeivikas.olyze.eliner.api.MidiEvent
import com.yeivikas.olyze.eliner.api.MidiEventType

/**
 * §6/§34: turns a raw MIDI byte stream (exactly what
 * `android.media.midi.MidiReceiver.onSend` hands over) into [MidiEvent]s,
 * correctly handling two details that are easy to get wrong and would
 * silently misread real hardware if skipped:
 *
 * 1. **Running status** — a compliant sender may omit a repeated status
 *    byte for consecutive messages of the same type (e.g. a fast note
 *    stream sends `[0x90 60 100] [62 100] [64 100] ...`, not a fresh
 *    `0x90` each time). Very common on real keyboards. This parser tracks
 *    [runningStatus] across calls.
 * 2. **Interleaved System Real-Time bytes** — Clock (0xF8), Start (0xFA),
 *    Continue (0xFB), Stop (0xFC), Active Sensing (0xFE), and Reset
 *    (0xFF) are single bytes that the spec explicitly allows to appear
 *    in the middle of any other message's bytes (including mid-SysEx)
 *    without disturbing it. Handled here by checking for them first, on
 *    every byte, before any other state machine logic runs.
 *
 * One instance per input connection (i.e. per [MidiPortInfo]) — running
 * status and SysEx accumulation state must not be shared across
 * different physical sources, or bytes from two devices could corrupt
 * each other's in-progress message. Not thread-safe by design: each
 * instance is only ever fed by the single platform callback thread that
 * owns its connection.
 */
class MidiStreamParser(private val portId: String) {
    private var runningStatus: Int = 0
    private var pendingData = IntArray(2)
    private var pendingDataCount = 0
    private var expectedDataBytes = 0
    private var inSysex = false
    private val sysexBuffer = mutableListOf<Byte>()

    /**
     * Feeds [count] bytes from [buffer] starting at [offset] (exactly
     * `MidiReceiver.onSend`'s own signature shape). [timestampNanos]
     * applies to every event this call produces — see [MidiEvent]'s doc
     * for why it's [com.yeivikas.olyze.eliner.services.TimeProvider]-based,
     * not per-byte from the platform (Android's own MIDI timestamp is in
     * a different, unspecified-precision epoch not worth threading
     * through here). Emits each parsed event to [onEvent] as soon as it's
     * complete.
     */
    fun feed(buffer: ByteArray, offset: Int, count: Int, timestampNanos: Long, onEvent: (MidiEvent) -> Unit) {
        for (i in offset until offset + count) {
            val byte = buffer[i].toInt() and 0xFF
            processByte(byte, timestampNanos, onEvent)
        }
    }

    private fun processByte(byte: Int, timestampNanos: Long, onEvent: (MidiEvent) -> Unit) {
        // Real-time bytes: handled first, never disturb any other state (see class doc, point 2).
        realtimeTypeFor(byte)?.let { type ->
            onEvent(MidiEvent(type = type, channel = null, timestampNanos = timestampNanos, sourcePortId = portId))
            return
        }

        if (byte == 0xF0) { // SysEx start
            inSysex = true
            sysexBuffer.clear()
            return
        }
        if (inSysex) {
            if (byte == 0xF7) { // SysEx end
                inSysex = false
                if (sysexBuffer.size <= MidiEvent.MAX_SYSEX_BYTES) {
                    onEvent(
                        MidiEvent(
                            type = MidiEventType.SYSTEM_EXCLUSIVE,
                            channel = null,
                            sysex = sysexBuffer.toByteArray(),
                            timestampNanos = timestampNanos,
                            sourcePortId = portId,
                        ),
                    )
                }
                // Oversized SysEx is silently dropped, not delivered partially
                // or thrown — §19/§34: never let a malformed/hostile payload
                // reach a consumer half-assembled or crash the parser.
                sysexBuffer.clear()
                return
            }
            if (sysexBuffer.size < MidiEvent.MAX_SYSEX_BYTES) sysexBuffer.add(byte.toByte())
            // Bytes beyond the cap are consumed (kept out of running-status
            // logic below) but not stored — bounded memory, no silent
            // truncation-that-looks-valid (the size check above rejects the
            // whole message instead of emitting a truncated one).
            return
        }

        if (byte >= 0x80) {
            // A new status byte: (re)starts running status and resets the
            // in-progress data-byte count, whatever it was.
            runningStatus = byte
            pendingDataCount = 0
            expectedDataBytes = dataByteCountFor(byte)
            if (expectedDataBytes == 0) {
                // Channel Aftertouch/Program Change... no, those need 1 data
                // byte — 0 only reached for status bytes this parser doesn't
                // model as channel-voice (shouldn't happen given the checks
                // above already handled 0xF0/0xF7 and realtime); defensively
                // just ignore an unrecognized status byte rather than emit
                // garbage.
                runningStatus = 0
            }
            return
        }

        // Data byte (0x00-0x7F).
        if (runningStatus == 0) return // no active status — drop stray data byte defensively (§34).
        pendingData[pendingDataCount] = byte
        pendingDataCount++
        if (pendingDataCount == expectedDataBytes) {
            emitChannelVoiceEvent(timestampNanos, onEvent)
            pendingDataCount = 0 // running status: stay armed for the next data-byte group.
        }
    }

    private fun emitChannelVoiceEvent(timestampNanos: Long, onEvent: (MidiEvent) -> Unit) {
        val channel = runningStatus and 0x0F
        val type = when (runningStatus and 0xF0) {
            0x80 -> MidiEventType.NOTE_OFF
            0x90 -> if (pendingData[1] == 0) MidiEventType.NOTE_OFF else MidiEventType.NOTE_ON // §6: velocity-0 Note On == Note Off, standard MIDI convention.
            0xA0 -> MidiEventType.POLYPHONIC_AFTERTOUCH
            0xB0 -> MidiEventType.CONTROL_CHANGE
            0xC0 -> MidiEventType.PROGRAM_CHANGE
            0xD0 -> MidiEventType.CHANNEL_AFTERTOUCH
            0xE0 -> MidiEventType.PITCH_BEND
            else -> return
        }
        val event = if (type == MidiEventType.PITCH_BEND) {
            MidiEvent(
                type = type,
                channel = channel,
                pitchBendValue = pendingData[0] or (pendingData[1] shl 7),
                timestampNanos = timestampNanos,
                sourcePortId = portId,
            )
        } else {
            MidiEvent(
                type = type,
                channel = channel,
                data1 = pendingData[0],
                data2 = if (expectedDataBytes == 2) pendingData[1] else 0,
                timestampNanos = timestampNanos,
                sourcePortId = portId,
            )
        }
        onEvent(event)
    }

    private fun dataByteCountFor(status: Int): Int = when (status and 0xF0) {
        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2
        0xC0, 0xD0 -> 1
        else -> 0 // System Common (0xF1-0xF6) — not modeled as channel-voice; see processByte's defensive reset.
    }

    private fun realtimeTypeFor(byte: Int): MidiEventType? = when (byte) {
        0xF8 -> MidiEventType.CLOCK
        0xFA -> MidiEventType.START
        0xFB -> MidiEventType.CONTINUE
        0xFC -> MidiEventType.STOP
        0xFE, 0xFF -> MidiEventType.SYSTEM_OTHER // Active Sensing / Reset — preserved as a fact, not acted on (§6).
        else -> null
    }
}
