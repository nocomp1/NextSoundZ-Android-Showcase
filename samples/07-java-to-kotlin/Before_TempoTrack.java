package com.nextsoundz.showcase.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DEMONSTRATION SAMPLE — representative "before" code, written for this public repository
 * in the style of the original Java layer. Not production source.
 *
 * ---
 *
 * A tempo map: given a musical position, what tempo is in effect?
 *
 * This is deliberately written the way the original Java actually looked, including its
 * defects. The Kotlin version alongside fixes all of them. Six problems:
 *
 *  1. getTempoAt() returns Double — a boxed type that can be null, with no indication in
 *     the signature whether it ever is. Every caller either null-checks defensively or
 *     eventually NPEs on unboxing.
 *  2. getEvents() returns the internal mutable list. Any caller can corrupt the sorted
 *     invariant the lookup depends on, from anywhere, with no compiler complaint.
 *  3. The sorted invariant is maintained by convention. addEvent() sorts every insertion —
 *     O(n log n) per add — and nothing prevents a caller from breaking the order anyway.
 *  4. TempoEvent is a mutable bean: four lines of boilerplate per field, no equals(),
 *     no hashCode(), no toString(). It cannot be used as a map key or compared in a test
 *     without writing those by hand.
 *  5. The linear scan is O(n) on a list that is already sorted — this is called from the
 *     playback path.
 *  6. No defence against an empty map or a negative tick.
 */
public class Before_TempoTrack {

    private List<TempoEvent> events = new ArrayList<TempoEvent>();

    public void addEvent(TempoEvent event) {
        events.add(event);
        Collections.sort(events, (a, b) -> Long.compare(a.getTick(), b.getTick()));
    }

    /** Returns null if there is no tempo event at or before the tick. Caller beware. */
    public Double getTempoAt(long tick) {
        Double result = null;
        for (int i = 0; i < events.size(); i++) {
            TempoEvent e = events.get(i);
            if (e.getTick() <= tick) {
                result = e.getBpm();
            }
        }
        return result;
    }

    /** Hands out the live internal list. */
    public List<TempoEvent> getEvents() {
        return events;
    }

    public static class TempoEvent {
        private long tick;
        private double bpm;

        public TempoEvent(long tick, double bpm) {
            this.tick = tick;
            this.bpm = bpm;
        }

        public long getTick() { return tick; }
        public void setTick(long tick) { this.tick = tick; }
        public double getBpm() { return bpm; }
        public void setBpm(double bpm) { this.bpm = bpm; }
    }
}
