@file:Suppress("MaxLineLength")

package com.zodl.slipstream.internal.spend

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure twin of `host_read.rs`'s `listResubmissionCandidates` `WHERE` clause - `SDK_ADAPTER_PLAN.md` T8's four cases. */
class ResubmissionPredicateTest {
    @Test
    fun mined_is_not_eligible() =
        assertFalse(isEligibleForResubmission(minedHeight = 100L, expiryHeight = 200L, chainTip = 150L, accountBalanceDelta = -10L))

    @Test
    fun expired_is_not_eligible() =
        assertFalse(isEligibleForResubmission(minedHeight = null, expiryHeight = 100L, chainTip = 150L, accountBalanceDelta = -10L))

    @Test
    fun unmined_within_expiry_send_is_eligible() =
        assertTrue(isEligibleForResubmission(minedHeight = null, expiryHeight = 200L, chainTip = 150L, accountBalanceDelta = -10L))

    @Test
    fun received_is_not_eligible() =
        assertFalse(isEligibleForResubmission(minedHeight = null, expiryHeight = 200L, chainTip = 150L, accountBalanceDelta = 10L))
}
