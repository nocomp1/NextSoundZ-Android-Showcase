package com.nextsoundz.showcase.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * Shows the testing style I use for domain rules: hand-written fakes instead of a mocking
 * framework, and a test name that states the *rule*, not the method under test.
 *
 * Each test's comment records why the behaviour matters. When someone changes this rule in
 * two years, the failing test should tell them what they are about to break and what it cost
 * us last time — that context is the most valuable thing in a regression suite.
 */
class ExportAllowanceTest {

    private class FakeCounter(private var value: Int = 0) : ExportAllowance.Counter {
        override fun used() = value
        override fun setUsed(value: Int) { this.value = value }
    }

    private class FakeEntitlement(private val paid: Boolean) : ExportAllowance.Entitlement {
        override fun hasUnlimitedExport() = paid
    }

    private fun quota(paid: Boolean, alreadyUsed: Int = 0) =
        ExportAllowance(FakeCounter(alreadyUsed), FakeEntitlement(paid))

    @Test
    fun `a free user may export until the allowance runs out`() {
        val quota = quota(paid = false)

        repeat(ExportAllowance.FREE_EXPORT_LIMIT) { i ->
            assertTrue("export ${i + 1} should be allowed", quota.canExport())
            quota.recordExport()
        }

        assertFalse("the allowance should be spent", quota.canExport())
    }

    @Test
    fun `recording an export reports what is left`() {
        val quota = quota(paid = false)
        assertEquals(ExportAllowance.FREE_EXPORT_LIMIT - 1, quota.recordExport())
    }

    @Test
    fun `a subscriber is never metered`() {
        val quota = quota(paid = true)

        repeat(ExportAllowance.FREE_EXPORT_LIMIT * 10) { quota.recordExport() }

        assertTrue(quota.canExport())
        assertEquals(ExportAllowance.UNLIMITED, quota.remaining())
    }

    /**
     * Regression: a subscriber's exports must not consume the free allowance. Before this was
     * pinned, a user who subscribed, exported heavily, then let the subscription lapse found
     * their free exports already gone — they had never used one as a free user.
     */
    @Test
    fun `a lapsed subscriber still has their free allowance intact`() {
        val counter = FakeCounter()

        ExportAllowance(counter, FakeEntitlement(paid = true)).also { paidSession ->
            repeat(5) { paidSession.recordExport() }
        }

        val afterLapse = ExportAllowance(counter, FakeEntitlement(paid = false))
        assertEquals(ExportAllowance.FREE_EXPORT_LIMIT, afterLapse.remaining())
    }

    /** Remaining never goes negative, even if a stored count is corrupt or ahead of the limit. */
    @Test
    fun `a corrupt stored count degrades safely`() {
        val quota = quota(paid = false, alreadyUsed = 999)
        assertEquals(0, quota.remaining())
        assertFalse(quota.canExport())
    }
}
