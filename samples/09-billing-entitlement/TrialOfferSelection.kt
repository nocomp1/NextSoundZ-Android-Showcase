package com.nextsoundz.showcase.billing

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository. Real product identifiers,
 * base plan tags, offer ids, and pricing are NOT published here.
 *
 * ---
 *
 * Choosing which subscription offer to present, from what Google Play actually returned.
 *
 * Play Billing v5+ models a subscription as a product containing one or more **base plans**,
 * each of which may carry **offers** (a free trial, an introductory price). Play only
 * returns the offers this specific account is *eligible for* — which is exactly why
 * eligibility should be read from the response rather than decided locally.
 *
 * ## Two real bugs this encodes
 *
 * **The trial vanished for everyone.** The original code selected
 * `subscriptionOfferDetails.first()`. Play does not guarantee ordering, and for accounts
 * with both a trial offer and the plain base plan available it frequently returned the base
 * plan first — so eligible new users were shown the full price and never offered the trial
 * that the marketing pointed them at. Conversion dropped and nothing looked broken.
 *
 * **The price shown was not the price charged.** The displayed price was taken from the
 * first pricing phase of the selected offer. For a trial, phase one is the *free* phase, so
 * the paywall cheerfully advertised the subscription as costing nothing. The price a user is
 * committing to is the **final, recurring** phase — the one that repeats after any
 * introductory phases end.
 *
 * Both are the same underlying mistake: treating an ordered list from an external SDK as if
 * position carried meaning. Both are now pinned by unit tests.
 */
object TrialOfferSelection {

    /** Minimal stand-ins for the Play Billing types, so this file compiles standalone. */
    data class PricingPhase(
        val formattedPrice: String,
        val priceAmountMicros: Long,
        val billingPeriod: String,
        /** 0 means it repeats forever — i.e. this is the recurring phase. */
        val billingCycleCount: Int,
    )

    data class SubscriptionOffer(
        val basePlanId: String,
        val offerId: String?,
        val offerTags: List<String>,
        val pricingPhases: List<PricingPhase>,
    ) {
        /** A free phase at the front is what makes an offer a trial. */
        val isFreeTrial: Boolean
            get() = pricingPhases.firstOrNull()?.priceAmountMicros == 0L
    }

    data class Selection(
        val offer: SubscriptionOffer,
        /** The price the user will actually be charged on renewal. */
        val recurringPrice: String,
        val trialPeriod: String?,
    )

    /**
     * Picks the best offer available to this account.
     *
     * Prefer a free trial when Play has returned one — its presence *is* the eligibility
     * signal. Otherwise take the cheapest recurring offer, so a promotional price is never
     * accidentally passed over in favour of a more expensive plan.
     */
    fun select(offers: List<SubscriptionOffer>): Selection? {
        if (offers.isEmpty()) return null

        val chosen = offers.firstOrNull { it.isFreeTrial }
            ?: offers.minByOrNull { it.recurringPhase()?.priceAmountMicros ?: Long.MAX_VALUE }
            ?: return null

        val recurring = chosen.recurringPhase() ?: return null

        return Selection(
            offer = chosen,
            recurringPrice = recurring.formattedPrice,
            trialPeriod = chosen.pricingPhases
                .firstOrNull { it.priceAmountMicros == 0L }
                ?.billingPeriod,
        )
    }

    /**
     * The phase the user ends up paying indefinitely.
     *
     * Identified by `billingCycleCount == 0` ("repeats forever"), not by list position.
     * Introductory and trial phases always have a finite cycle count; exactly one phase
     * recurs. Falling back to the last phase covers a malformed response without crashing
     * the paywall — showing *a* price beats showing none.
     */
    private fun SubscriptionOffer.recurringPhase(): PricingPhase? =
        pricingPhases.firstOrNull { it.billingCycleCount == 0 } ?: pricingPhases.lastOrNull()
}
