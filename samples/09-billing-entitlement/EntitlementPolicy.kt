package com.nextsoundz.showcase.billing

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * Real thresholds, grace periods, product identifiers, pricing, and the server verification
 * protocol are NOT published here. The values below are illustrative placeholders chosen to
 * make the logic readable; they are not the production values.
 *
 * ---
 *
 * Deciding whether a user currently has paid access.
 *
 * This looks like a trivial boolean and is not. The hard part is the **offline and
 * degraded** cases, which is where naive implementations fail in one of two expensive ways:
 * locking out a paying customer, or handing out free access indefinitely.
 *
 * ## The three rules that matter
 *
 * **1. Google Play is the source of truth for trial eligibility — never a local flag.**
 * A stored "has used free trial" boolean is wrong after a reinstall, wrong on a second
 * device, wrong after a factory reset, and trivially defeated by clearing app data. Play
 * knows whether this account has consumed the introductory offer, and it tells you by
 * which offers it returns for the product. So: read the offers Play gives you and pick from
 * them, rather than deciding eligibility yourself and asking Play to honour it.
 *
 * **2. A local entitlement is valid, but only for a bounded time.**
 * Requiring a successful server check to use paid features means the app is unusable on a
 * plane. Never checking means a cancelled subscription keeps working forever. The
 * resolution is two clocks: re-verify periodically when possible, and cap how long an
 * *unverified* entitlement stays trusted. Inside the cap, offline users keep working.
 * Past it, access lapses.
 *
 * **3. A verification outage must not revoke paying users.**
 * If the server is unreachable, that is our failure, not the user's. An unreachable server
 * leaves the last known state alone. Only an explicit, authoritative "this entitlement is
 * not valid" revokes. Distinguishing "cannot reach" from "reached, and it said no" is the
 * single most important distinction in this file — conflating them turns a backend incident
 * into a mass lockout of exactly the customers you least want to lock out.
 */
class EntitlementPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What we last learned, and when. Persisted locally. */
    data class EntitlementRecord(
        val status: Status,
        /** When this record was last confirmed by the server. */
        val lastVerifiedAt: Long,
        /** Server-reported expiry, when known. */
        val expiresAt: Long?,
    )

    enum class Status {
        /** Paid and confirmed. */
        ACTIVE,

        /** Play reports a payment problem; the user keeps access during the grace window. */
        IN_GRACE_PERIOD,

        /** Authoritatively not entitled. */
        NONE,
    }

    /** The result of the most recent verification attempt. */
    sealed interface VerificationOutcome {
        data class Confirmed(val status: Status, val expiresAt: Long?) : VerificationOutcome

        /** Reached the server; it says this entitlement is not valid. Authoritative. */
        data object Revoked : VerificationOutcome

        /** Could not reach the server. Says nothing about the entitlement. Not a denial. */
        data object Unreachable : VerificationOutcome
    }

    /**
     * Whether paid features are available right now.
     *
     * Note what this does *not* do: it does not call the network. Entitlement checks happen
     * on hot paths (every export, every premium kit tap) and must be synchronous and free.
     * Verification is a separate, scheduled concern — see [shouldReverify].
     */
    fun hasAccess(record: EntitlementRecord?): Boolean {
        val current = record ?: return false

        if (current.status == Status.NONE) return false

        val now = clock()

        // A server-reported expiry that has passed is authoritative.
        current.expiresAt?.let { if (now >= it) return false }

        // Rule 2: trust a local record only so long without re-confirmation.
        val unverifiedFor = now - current.lastVerifiedAt
        if (unverifiedFor > MAX_UNVERIFIED_MS) return false

        return true
    }

    /** True when it is time to attempt a background re-check. */
    fun shouldReverify(record: EntitlementRecord?): Boolean {
        val current = record ?: return false
        return clock() - current.lastVerifiedAt >= REVERIFY_INTERVAL_MS
    }

    /**
     * Folds a verification result into the stored record.
     *
     * Rule 3 lives here: [VerificationOutcome.Unreachable] returns the previous record
     * **unchanged** — crucially without refreshing `lastVerifiedAt`, so an extended outage
     * still eventually ages out via [MAX_UNVERIFIED_MS] rather than granting indefinite
     * access. A failed check neither confirms nor denies; it simply did not happen.
     */
    fun applyVerification(
        previous: EntitlementRecord?,
        outcome: VerificationOutcome,
    ): EntitlementRecord? = when (outcome) {

        is VerificationOutcome.Confirmed -> EntitlementRecord(
            status = outcome.status,
            lastVerifiedAt = clock(),
            expiresAt = outcome.expiresAt,
        )

        VerificationOutcome.Revoked -> EntitlementRecord(
            status = Status.NONE,
            lastVerifiedAt = clock(),
            expiresAt = null,
        )

        VerificationOutcome.Unreachable -> previous
    }

    companion object {
        /** Illustrative. Re-check roughly this often when the network allows. */
        const val REVERIFY_INTERVAL_MS = 2 * 60 * 60 * 1000L        // 2 hours

        /**
         * Illustrative. How long an entitlement may go unverified and still be honoured.
         *
         * The trade-off in one number: too short and a user on a long trip loses access they
         * paid for; too long and a cancellation takes weeks to take effect. It should
         * comfortably exceed a normal offline stretch and fall well short of a billing period.
         */
        const val MAX_UNVERIFIED_MS = 3 * 24 * 60 * 60 * 1000L      // 3 days
    }
}
