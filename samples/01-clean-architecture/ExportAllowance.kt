package com.nextsoundz.showcase.domain

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 * The production class enforces a different, unpublished allowance and additional rules.
 *
 * ---
 *
 * A domain policy object: the rule for how many times a non-subscriber may export audio.
 *
 * Three properties are deliberate, and they are the whole point of this sample:
 *
 *  1. **No Android imports.** This file compiles and tests on the JVM in milliseconds.
 *     Persistence and entitlement arrive as interfaces, so the rule can be exercised
 *     without a device, a Robolectric shadow, or a running app.
 *
 *  2. **The rule lives in one place.** In the real app this allowance is shared by two
 *     entirely separate UI surfaces (the beat-maker and the DAW). It previously lived on
 *     one screen, which meant the other screen exported without limit — a revenue bug
 *     caused purely by putting a business rule in a UI layer.
 *
 *  3. **It is honest about its collaborators.** [Counter] and [Entitlement] are narrow
 *     interfaces owned by the domain, not leaked SharedPreferences or billing SDK types.
 *     The data layer implements them; the domain never learns what backs them.
 */
class ExportAllowance(
    private val counter: Counter,
    private val entitlement: Entitlement,
) {

    /** Persisted count of exports already used. Backed by preferences in production. */
    interface Counter {
        fun used(): Int
        fun setUsed(value: Int)
    }

    /** Whether the current user has paid access to unrestricted export. */
    interface Entitlement {
        fun hasUnlimitedExport(): Boolean
    }

    /** True when the user may start another export right now. */
    fun canExport(): Boolean =
        entitlement.hasUnlimitedExport() || counter.used() < FREE_EXPORT_LIMIT

    /**
     * Records a completed export and reports how many free exports remain.
     *
     * Subscribers are never counted — otherwise a user who lapses after a long paid period
     * would find their free allowance already spent, which reads as a bug to them and as a
     * support ticket to us.
     */
    fun recordExport(): Int {
        if (entitlement.hasUnlimitedExport()) return UNLIMITED

        val next = (counter.used() + 1).coerceAtMost(FREE_EXPORT_LIMIT)
        counter.setUsed(next)
        return FREE_EXPORT_LIMIT - next
    }

    /** Remaining free exports, or [UNLIMITED] for subscribers. */
    fun remaining(): Int =
        if (entitlement.hasUnlimitedExport()) UNLIMITED
        else (FREE_EXPORT_LIMIT - counter.used()).coerceAtLeast(0)

    companion object {
        /** Illustrative value. The production allowance is not published here. */
        const val FREE_EXPORT_LIMIT = 5

        const val UNLIMITED = -1
    }
}
