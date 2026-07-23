package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState
import com.zodl.slipstream.model.SlipstreamTransactionRow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionOverviewCursorTest {
    /** `chainTip + 1 - minedHeight == 10 == MIN_CONFIRMATIONS -> Confirmed`. */
    @Test
    fun received_transaction_has_positive_net_value_and_is_not_sent() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 1 },
                        minedHeight = 1_000,
                        expiryHeight = null,
                        txIndex = 3,
                        raw = null,
                        accountBalanceDelta = 5_000,
                        totalSpent = 0,
                        totalReceived = 5_000,
                        feePaid = null,
                        hasChange = false,
                        sentNoteCount = 0,
                        receivedNoteCount = 1,
                        memoCount = 0,
                        blockTime = 1_700_000_000,
                        isShielding = false,
                        isExpiredUnmined = 0L
                    ),
                latestHeight = BlockHeight.new(1_009)
            )

        assertFalse(overview.isSentTransaction)
        assertEquals(5_000L, overview.netValue.value)
        assertEquals(TransactionState.Confirmed, overview.transactionState)
    }

    @Test
    fun sent_transaction_has_positive_net_value_via_absolute_value() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 2 },
                        minedHeight = null,
                        expiryHeight = 1_010,
                        txIndex = null,
                        raw = byteArrayOf(1, 2, 3),
                        accountBalanceDelta = -7_500,
                        totalSpent = 8_000,
                        totalReceived = 0,
                        feePaid = 500,
                        hasChange = true,
                        sentNoteCount = 1,
                        receivedNoteCount = 0,
                        memoCount = 1,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L
                    ),
                latestHeight = BlockHeight.new(1_000)
            )

        assertTrue(overview.isSentTransaction)
        assertEquals(7_500L, overview.netValue.value)
        assertEquals(500L, overview.feePaid?.value)
        assertTrue(overview.isChange)
        assertEquals(TransactionState.Pending, overview.transactionState)
    }

    @Test
    fun zero_expiry_height_maps_to_no_expiry() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 3 },
                        minedHeight = null,
                        expiryHeight = 0,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = 100,
                        totalSpent = 0,
                        totalReceived = 100,
                        feePaid = null,
                        hasChange = false,
                        sentNoteCount = 0,
                        receivedNoteCount = 1,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L
                    ),
                latestHeight = BlockHeight.new(1_000_000)
            )

        assertNull(overview.expiryHeight)
        assertEquals(TransactionState.Pending, overview.transactionState)
    }

    /** Unknown chain tip -> Pending regardless of a mined height (section 3.6 base case). */
    @Test
    fun raw_and_index_pass_through_when_present() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 4 },
                        minedHeight = 500,
                        expiryHeight = null,
                        txIndex = 9,
                        raw = byteArrayOf(9, 9),
                        accountBalanceDelta = 1,
                        totalSpent = 0,
                        totalReceived = 1,
                        feePaid = null,
                        hasChange = false,
                        sentNoteCount = 0,
                        receivedNoteCount = 1,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = true,
                        isExpiredUnmined = null
                    ),
                latestHeight = null
            )

        assertEquals(9L, overview.index)
        assertTrue(overview.raw!!.byteArray.contentEquals(byteArrayOf(9, 9)))
        assertTrue(overview.isShielding)
        assertEquals(TransactionState.Pending, overview.transactionState)
    }
}
