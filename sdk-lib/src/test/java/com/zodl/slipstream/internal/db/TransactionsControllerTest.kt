package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.BlockHeight
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MOB-1664: a fresh [com.zodl.slipstream.internal.SlipstreamEngine] instance (e.g. right after an
 * automatic server switch rebuilds the whole synchronizer) reports `chainTip == null`, and a
 * mid-tick degraded snapshot can report `chainTip <= 0`. Neither is trustworthy; both must be
 * treated as "unknown" so [TransactionsController.latestHeight] falls back to the DB-backed
 * scanned height instead of letting every transaction's confirmation math momentarily flash back
 * to Pending.
 */
class TransactionsControllerTest {
    @Test
    fun `null chainTip is treated as unknown`() = assertNull(resolveLiveChainTip(null))

    @Test
    fun `zero chainTip is treated as unknown`() = assertNull(resolveLiveChainTip(0L))

    @Test
    fun `negative chainTip is treated as unknown`() = assertNull(resolveLiveChainTip(-1L))

    @Test
    fun `positive chainTip resolves to a real BlockHeight`() {
        assertEquals(BlockHeight.new(3_440_531L), resolveLiveChainTip(3_440_531L))
    }

    @Test
    fun `chainTip of exactly one resolves`() {
        assertEquals(BlockHeight.new(1L), resolveLiveChainTip(1L))
    }
}
