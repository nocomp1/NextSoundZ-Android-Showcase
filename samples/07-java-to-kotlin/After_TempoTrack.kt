package com.nextsoundz.showcase.legacy

/**
 * DEMONSTRATION SAMPLE — the "after" for [Before_TempoTrack], written for this public
 * repository.
 *
 * ---
 *
 * What a real conversion looks like versus what the IDE's automatic converter produces.
 *
 * **The automatic conversion would have given me** `Double?` returns, `!!` at the call
 * sites, the same mutable bean with `var` fields, the same exposed internal list, and the
 * same O(n) scan. It would compile, pass the existing tests, and preserve every defect —
 * now expressed in Kotlin. That is the trap: a green build after a bulk convert feels like
 * modernization and is actually just translation.
 *
 * **What changed, and why each one matters:**
 *
 * | Java | Kotlin | Why |
 * |---|---|---|
 * | `Double` (nullable, undeclared) | `Double` non-null + explicit default | Removed a whole class of NPE; the "no tempo set" case is now answered by the type, not by the caller. |
 * | Mutable bean, 4 accessors | `data class` | `equals`/`hashCode`/`toString`/`copy` for free; usable in tests and as a map key. |
 * | Exposed `List` | `List` view over a private `MutableList` | The sorted invariant can no longer be violated from outside. |
 * | Sort on every insert | Binary-search insertion | O(log n) insert, and order is maintained by construction rather than by convention. |
 * | O(n) linear scan | `binarySearch` | The lookup is on the playback path; on a long arrangement the scan was measurable. |
 * | Invariant by convention | Invariant by encapsulation | Unrepresentable states are now actually unrepresentable. |
 *
 * The deeper point: converting the *language* was the easy part. The value came from asking
 * what each Java idiom was working around, and whether Kotlin lets the design say it
 * directly. A nullable return was standing in for a missing default; a bean was standing in
 * for a value type; a public list was standing in for an absent boundary.
 */
class TempoTrack(initialBpm: Double = DEFAULT_BPM) {

    /** Sorted by tick, always — enforced by [addEvent], not by convention. */
    private val _events = mutableListOf(TempoEvent(tick = 0L, bpm = initialBpm))

    /** A read-only view. Callers cannot break the ordering the lookup relies on. */
    val events: List<TempoEvent> get() = _events

    /**
     * Inserts a tempo change, keeping the list sorted. An event at an existing tick
     * replaces it — two tempos at the same instant is not a meaningful state.
     */
    fun addEvent(event: TempoEvent) {
        val index = _events.binarySearch { it.tick.compareTo(event.tick) }
        if (index >= 0) _events[index] = event else _events.add(-(index + 1), event)
    }

    /**
     * The tempo in effect at [tick]. Never null: there is always an event at tick 0, so
     * "no tempo here" is not a state this type can be in.
     *
     * Negative ticks clamp to the initial tempo rather than throwing — a seek can legitimately
     * produce one transiently, and a crash is the wrong answer to a harmless input.
     */
    fun tempoAt(tick: Long): Double {
        if (tick <= 0L) return _events.first().bpm

        val index = _events.binarySearch { it.tick.compareTo(tick) }
        // Exact hit, or the insertion point minus one = the last event at or before tick.
        return if (index >= 0) _events[index].bpm else _events[-(index + 1) - 1].bpm
    }

    data class TempoEvent(val tick: Long, val bpm: Double)

    companion object {
        const val DEFAULT_BPM = 120.0
    }
}
