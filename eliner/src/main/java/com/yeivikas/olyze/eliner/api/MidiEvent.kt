package com.yeivikas.olyze.eliner.api

/**
 * Every MIDI message type this foundation represents. Deliberately one
 * enum + one data class ([MidiEvent]) below, not one class per message
 * type — a `NoteOnEvent`/`NoteOffEvent`/`ControlChangeEvent`/... class
 * hierarchy would be more "typed" but is exactly the sobreingeniería the
 * spec (Fase MIDI Foundation, §6) warns against for a message set this
 * uniform in shape (channel + up to 2 data bytes, or a raw payload for
 * SysEx). [MidiEvent] carries all the fields any of these needs; unused
 * fields for a given [type] are simply left at their default.
 */
enum class MidiEventType {
    NOTE_ON,
    NOTE_OFF,
    POLYPHONIC_AFTERTOUCH,
    CHANNEL_AFTERTOUCH,
    CONTROL_CHANGE,
    PROGRAM_CHANGE,
    PITCH_BEND,
    SYSTEM_EXCLUSIVE,
    /** MIDI Clock (0xF8) — 24 ticks per quarter note, per the MIDI spec. */
    CLOCK,
    START,
    STOP,
    CONTINUE,
    /** Any other System Common / System Real-Time byte not modeled above
     *  (MTC Quarter Frame, Song Position Pointer, Song Select, Tune
     *  Request, Active Sensing, System Reset). Carried as [raw] so no
     *  data is lost even though this foundation doesn't interpret it —
     *  see §6/§39: representation must not lose information the spec asks
     *  to preserve, even for messages this phase doesn't act on. */
    SYSTEM_OTHER,
}

/**
 * A single MIDI event, already translated from whatever transport
 * delivered it (Android MIDI today; see [MidiPlatformBackend]) into
 * EliNer's own vocabulary. Nothing above [MidiRouter] should ever see an
 * `android.media.midi.*` type — this is the boundary (§25/§26).
 *
 * Field meaning depends on [type]:
 * - NOTE_ON / NOTE_OFF: [data1] = note number (0-127), [data2] = velocity (0-127).
 * - POLYPHONIC_AFTERTOUCH: [data1] = note number, [data2] = pressure.
 * - CHANNEL_AFTERTOUCH: [data1] = pressure, [data2] unused.
 * - CONTROL_CHANGE: [data1] = controller number, [data2] = value.
 * - PROGRAM_CHANGE: [data1] = program number, [data2] unused.
 * - PITCH_BEND: [data1]/[data2] unused; use [pitchBendValue] (14-bit, 0-16383, 8192 = center).
 * - SYSTEM_EXCLUSIVE: [sysex] holds the payload (see its own doc).
 * - CLOCK/START/STOP/CONTINUE/SYSTEM_OTHER: only [timestampNanos]/[sourcePortId] are meaningful.
 *
 * [channel] is 0-15 for channel-voice messages, or `null` for
 * system messages (they have no channel). Validated at construction —
 * see `init`.
 *
 * [timestampNanos]: the moment this event was captured, read from
 * [com.yeivikas.olyze.eliner.services.TimeProvider.nowNanos] at the point
 * the platform backend received it — as close to the hardware event as
 * this phase can get without a dedicated MIDI-capable audio clock. This
 * is deliberately `TimeProvider`, not
 * [com.yeivikas.olyze.eliner.audiofoundation.AudioClock]: `AudioClock` is
 * frame-based and is only ever advanced by a real audio callback driving
 * it (`advanceFrames`), and no such callback drives it today — the real
 * audio path is the native engine (see ADR 0010/0011), which doesn't call
 * back into this Kotlin clock. Wiring MIDI timestamps to a truly
 * sample-accurate clock is real future work (§7/§28), not something this
 * phase can honestly claim without that callback existing — see
 * `docs/adr/0012-midi-foundation.md` for the full reasoning.
 *
 * [sourcePortId] identifies which [MidiPortInfo] this event arrived on —
 * required for any routing decision that cares about "which device/port"
 * (§13), and for telling two identically-configured controllers apart in
 * a multi-device setup (§37).
 */
data class MidiEvent(
    val type: MidiEventType,
    val channel: Int?,
    val data1: Int = 0,
    val data2: Int = 0,
    val pitchBendValue: Int = 8192,
    val sysex: ByteArray? = null,
    val timestampNanos: Long,
    val sourcePortId: String,
) {
    init {
        require(channel == null || channel in 0..15) {
            "channel must be null or in 0..15, got $channel."
        }
        require(data1 in 0..127) { "data1 must be in 0..127, got $data1." }
        require(data2 in 0..127) { "data2 must be in 0..127, got $data2." }
        require(pitchBendValue in 0..16383) {
            "pitchBendValue must be in 0..16383 (14-bit), got $pitchBendValue."
        }
        if (type == MidiEventType.SYSTEM_EXCLUSIVE) {
            requireNotNull(sysex) { "SYSTEM_EXCLUSIVE event must carry a non-null sysex payload." }
            require(sysex.size <= MAX_SYSEX_BYTES) {
                "sysex payload (${sysex.size} bytes) exceeds MAX_SYSEX_BYTES ($MAX_SYSEX_BYTES) — " +
                    "see MidiEvent.MAX_SYSEX_BYTES doc for why this is bounded."
            }
        }
    }

    // data class equals/hashCode would use ByteArray's identity-based
    // default otherwise (a real correctness bug for anything that
    // deduplicates or tests events) — override with content-based ones,
    // matching Kotlin's own documented pattern for data classes wrapping
    // arrays.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MidiEvent) return false
        return type == other.type &&
            channel == other.channel &&
            data1 == other.data1 &&
            data2 == other.data2 &&
            pitchBendValue == other.pitchBendValue &&
            (sysex?.contentEquals(other.sysex) ?: (other.sysex == null)) &&
            timestampNanos == other.timestampNanos &&
            sourcePortId == other.sourcePortId
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (channel ?: -1)
        result = 31 * result + data1
        result = 31 * result + data2
        result = 31 * result + pitchBendValue
        result = 31 * result + (sysex?.contentHashCode() ?: 0)
        result = 31 * result + timestampNanos.hashCode()
        result = 31 * result + sourcePortId.hashCode()
        return result
    }

    companion object {
        /**
         * §19/§34: SysEx must have a real bound, not be "unlimited". 4 KiB
         * comfortably covers real-world identity/parameter-dump SysEx from
         * common controllers without risking an unbounded allocation from
         * a malformed or hostile device. No multi-packet SysEx
         * reassembly exists yet (see class doc) — this bound applies to a
         * single already-assembled payload as Android's MIDI framework
         * delivers it.
         */
        const val MAX_SYSEX_BYTES: Int = 4096
    }
}
