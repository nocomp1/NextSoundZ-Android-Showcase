package com.nextsoundz.showcase.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * Replacing a listener interface with a `Flow`, and why the buffer configuration matters.
 *
 * **What this replaced.** The original design was a `MidiListener` interface that the
 * currently-visible instrument registered on a singleton manager. Two consequences:
 *
 *  - Only one consumer could listen at a time, so the sequencer could not record a
 *    performance *while* the sampler played it.
 *  - The manager held a strong reference to a Fragment. Forget one `unregister()` in one
 *    lifecycle path and you have leaked a whole view hierarchy — which is exactly what
 *    happened, and it showed up as memory growth, not as an obvious crash.
 *
 * A `SharedFlow` fixes both: any number of collectors, and collection is scoped to the
 * collector's lifecycle, so unsubscription is automatic rather than remembered.
 *
 * **The buffer settings are the interesting part.** MIDI events arrive from a hardware
 * callback thread that must not block. `tryEmit` on a buffered flow with
 * [BufferOverflow.DROP_OLDEST] is non-suspending and never blocks the producer.
 *
 * Under a dense passage it is correct to drop the *oldest* pending note rather than stall
 * the MIDI thread: a late note is worse than a missing one in a live performance, and
 * stalling the input thread would back pressure into the driver.
 *
 * Note On/Off is the one exception — see [emit].
 */
@Singleton
class MidiEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<MidiEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every MIDI event, for any consumer that wants it. */
    val events: SharedFlow<MidiEvent> = _events.asSharedFlow()

    /** Convenience streams, so a consumer does not filter by hand at every call site. */
    val notes: kotlinx.coroutines.flow.Flow<MidiEvent.Note> =
        events.filterIsInstance<MidiEvent.Note>()

    val controlChanges: kotlinx.coroutines.flow.Flow<MidiEvent.ControlChange> =
        events.filterIsInstance<MidiEvent.ControlChange>()

    /**
     * Publishes an event. Safe to call from the MIDI input thread: never suspends, never
     * allocates a coroutine.
     *
     * Returns false if the event was dropped under back pressure, which the caller may
     * count for diagnostics.
     *
     * **Note Off is never dropped.** Losing a Note Off strands a voice sounding forever —
     * a stuck note, the single most obvious audible failure a MIDI app can have. When the
     * buffer is saturated, a dropped Note Off is escalated directly to the voice allocator
     * so the note always stops even if its event never reaches a collector.
     */
    fun emit(event: MidiEvent): Boolean {
        val delivered = _events.tryEmit(event)

        if (!delivered && event is MidiEvent.Note && !event.isOn) {
            stuckNoteGuard?.forceNoteOff(event.channel, event.number)
        }
        return delivered
    }

    /** Set by the audio layer at startup. Kept as an interface so this class stays pure. */
    var stuckNoteGuard: StuckNoteGuard? = null

    fun interface StuckNoteGuard {
        fun forceNoteOff(channel: Int, note: Int)
    }

    private companion object {
        /**
         * Sized for a dense two-handed passage plus controller sweeps within one UI frame.
         * Large enough that normal playing never drops; small enough that a runaway device
         * cannot grow memory without bound.
         */
        const val EVENT_BUFFER = 64
    }
}

/** Typed MIDI events. Parsing from the wire format lives in samples/06-midi-device-io. */
sealed interface MidiEvent {
    val channel: Int

    data class Note(
        override val channel: Int,
        val number: Int,
        val velocity: Int,
        val isOn: Boolean,
        /**
         * Stamped when the event entered the process, not when it was handled.
         *
         * This distinction was a real bug: notes recorded from pads landed audibly late and
         * quantized to the wrong step. The cause was ~30ms of Android input-dispatch latency
         * between the touch and our handler — the recording was accurate to when we *saw*
         * the event, which was not when it *happened*.
         */
        val timestampNanos: Long,
    ) : MidiEvent

    data class ControlChange(
        override val channel: Int,
        val controller: Int,
        val value: Int,
    ) : MidiEvent

    data class PitchBend(
        override val channel: Int,
        /** Normalised to -1.0..1.0 from the 14-bit wire value. */
        val normalized: Float,
    ) : MidiEvent
}
