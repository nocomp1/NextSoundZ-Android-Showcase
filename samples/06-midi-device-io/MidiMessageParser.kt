package com.nextsoundz.showcase.midi

import com.nextsoundz.showcase.events.MidiEvent
import javax.inject.Inject

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * Decoding the MIDI wire format. A compact example of binary protocol parsing against a
 * spec that is full of the sort of detail you only discover from real hardware.
 *
 * **The format.** A message is a status byte (high bit set) followed by 1–2 data bytes
 * (high bit clear). The status byte packs a 4-bit message type and a 4-bit channel.
 *
 * **Three details that bite:**
 *
 *  1. **Running status.** To save bandwidth a device may omit the status byte on
 *     consecutive messages of the same type. A parser that assumes every message begins
 *     with a status byte works on some controllers and silently mangles others. The
 *     previous status must be remembered across calls — and therefore across buffers,
 *     since a message can be split between two `onSend` deliveries.
 *
 *  2. **Note On with velocity 0 means Note Off.** Almost all hardware uses this form,
 *     precisely *because* it enables running status. Miss it and every note hangs.
 *
 *  3. **Real-time messages (0xF8–0xFF) can appear anywhere**, including in the middle of
 *     another message, and must not disturb running status.
 *
 * The parser is a pure function of bytes to events — no Android types — so its edge cases
 * are covered by fast JVM unit tests rather than by plugging in a keyboard and hoping.
 */
class MidiMessageParser @Inject constructor() {

    /** Running status persists between buffers. */
    private var runningStatus: Int = 0

    fun parse(data: ByteArray, offset: Int, count: Int, timestampNanos: Long): List<MidiEvent> {
        val events = mutableListOf<MidiEvent>()
        var i = offset
        val end = offset + count

        while (i < end) {
            val byte = data[i].toInt() and 0xFF

            // System real-time: single byte, may interleave, leaves running status alone.
            if (byte >= SYSTEM_REALTIME_MIN) { i++; continue }

            val status: Int
            if (byte and STATUS_BIT != 0) {
                status = byte
                // System common messages cancel running status; channel messages set it.
                runningStatus = if (byte < SYSTEM_COMMON_MIN) byte else 0
                i++
            } else {
                // No status byte: reuse the last one, if we have a valid one.
                if (runningStatus == 0) { i++; continue }   // data byte with no context
                status = runningStatus
            }

            val type = status and TYPE_MASK
            val channel = status and CHANNEL_MASK
            val needed = dataByteCount(type)
            if (i + needed > end) break                      // message split across buffers

            val d1 = if (needed > 0) data[i].toInt() and 0x7F else 0
            val d2 = if (needed > 1) data[i + 1].toInt() and 0x7F else 0
            i += needed

            when (type) {
                NOTE_ON -> events += MidiEvent.Note(
                    channel = channel,
                    number = d1,
                    velocity = d2,
                    // Velocity 0 is a Note Off. See note (2) above.
                    isOn = d2 > 0,
                    timestampNanos = timestampNanos,
                )

                NOTE_OFF -> events += MidiEvent.Note(
                    channel = channel,
                    number = d1,
                    velocity = 0,
                    isOn = false,
                    timestampNanos = timestampNanos,
                )

                CONTROL_CHANGE -> events += MidiEvent.ControlChange(channel, d1, d2)

                PITCH_BEND -> {
                    // 14-bit value, LSB first, centred at 8192.
                    val raw = (d2 shl 7) or d1
                    events += MidiEvent.PitchBend(
                        channel = channel,
                        normalized = (raw - PITCH_CENTER) / PITCH_CENTER.toFloat(),
                    )
                }
            }
        }
        return events
    }

    /** Reset when a device disconnects — stale running status would misparse the next one. */
    fun reset() { runningStatus = 0 }

    private fun dataByteCount(type: Int) = when (type) {
        PROGRAM_CHANGE, CHANNEL_PRESSURE -> 1
        else -> 2
    }

    private companion object {
        const val STATUS_BIT = 0x80
        const val TYPE_MASK = 0xF0
        const val CHANNEL_MASK = 0x0F

        const val NOTE_OFF = 0x80
        const val NOTE_ON = 0x90
        const val CONTROL_CHANGE = 0xB0
        const val PROGRAM_CHANGE = 0xC0
        const val CHANNEL_PRESSURE = 0xD0
        const val PITCH_BEND = 0xE0

        const val SYSTEM_COMMON_MIN = 0xF0
        const val SYSTEM_REALTIME_MIN = 0xF8

        const val PITCH_CENTER = 8192
    }
}
