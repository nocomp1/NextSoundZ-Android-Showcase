package com.nextsoundz.showcase.billing

import com.nextsoundz.showcase.billing.TrialOfferSelection.PricingPhase
import com.nextsoundz.showcase.billing.TrialOfferSelection.SubscriptionOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * The regression tests for the two bugs documented in [TrialOfferSelection]. Both were
 * revenue-affecting and neither threw an exception — they just quietly did the wrong thing,
 * which is the category of bug that most needs a test pinning it.
 */
class TrialOfferSelectionTest {

    private fun free(period: String) = PricingPhase("Free", 0L, period, billingCycleCount = 1)
    private fun paid(price: String, micros: Long) =
        PricingPhase(price, micros, "P1M", billingCycleCount = 0)

    private val trialOffer = SubscriptionOffer(
        basePlanId = "monthly",
        offerId = "trial",
        offerTags = listOf("intro"),
        pricingPhases = listOf(free("P7D"), paid("$9.99", 9_990_000L)),
    )

    private val plainOffer = SubscriptionOffer(
        basePlanId = "monthly",
        offerId = null,
        offerTags = emptyList(),
        pricingPhases = listOf(paid("$9.99", 9_990_000L)),
    )

    /**
     * Regression: Play does not guarantee offer ordering. Selecting `first()` showed the
     * full-price plan to trial-eligible users and the trial silently disappeared from the
     * paywall.
     */
    @Test
    fun `the trial is chosen even when Play lists it last`() {
        val selection = TrialOfferSelection.select(listOf(plainOffer, trialOffer))

        assertEquals("trial", selection?.offer?.offerId)
        assertEquals("P7D", selection?.trialPeriod)
    }

    /**
     * Regression: the displayed price came from pricing phase one, which for a trial is the
     * free phase — so the paywall advertised the subscription as free.
     */
    @Test
    fun `the advertised price is the recurring phase, not the free phase`() {
        val selection = TrialOfferSelection.select(listOf(trialOffer))

        assertEquals("$9.99", selection?.recurringPrice)
    }

    /**
     * Play omits the trial offer for accounts that already consumed it. Absence of the offer
     * is the eligibility signal — we must not synthesise one from a local flag.
     */
    @Test
    fun `an account with no trial offer falls back to the paid plan`() {
        val selection = TrialOfferSelection.select(listOf(plainOffer))

        assertNull(selection?.trialPeriod)
        assertEquals("$9.99", selection?.recurringPrice)
    }

    @Test
    fun `the cheapest plan wins when no trial is available`() {
        val discounted = plainOffer.copy(
            basePlanId = "monthly-promo",
            pricingPhases = listOf(paid("$4.99", 4_990_000L)),
        )

        val selection = TrialOfferSelection.select(listOf(plainOffer, discounted))

        assertEquals("$4.99", selection?.recurringPrice)
    }

    @Test
    fun `an empty offer list does not crash the paywall`() {
        assertNull(TrialOfferSelection.select(emptyList()))
    }

    @Test
    fun `a malformed offer still yields a displayable price`() {
        // No phase is marked recurring; fall back to the last rather than showing nothing.
        val malformed = SubscriptionOffer(
            basePlanId = "monthly",
            offerId = null,
            offerTags = emptyList(),
            pricingPhases = listOf(PricingPhase("$9.99", 9_990_000L, "P1M", billingCycleCount = 3)),
        )

        assertTrue(TrialOfferSelection.select(listOf(malformed))?.recurringPrice == "$9.99")
    }
}
