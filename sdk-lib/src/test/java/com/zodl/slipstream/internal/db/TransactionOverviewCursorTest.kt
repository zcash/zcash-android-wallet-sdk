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
                        isExpiredUnmined = 0L,
                        zip318Kind = 0
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
                        isExpiredUnmined = 0L,
                        zip318Kind = 0
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
                        isExpiredUnmined = 0L,
                        zip318Kind = 0
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
                        isExpiredUnmined = null,
                        zip318Kind = 0
                    ),
                latestHeight = null
            )

        assertEquals(9L, overview.index)
        assertTrue(overview.raw!!.byteArray.contentEquals(byteArrayOf(9, 9)))
        assertTrue(overview.isShielding)
        assertEquals(TransactionState.Pending, overview.transactionState)
    }

    /**
     * Shape of a row from `v_transactions_with_pending_migrations`'s migration branch: no
     * `raw` (not broadcast yet), no `mined_height`, a real `expiry_height`, `fee_paid` present
     * (unlike an ordinary unbroadcast send, which has none yet either), and a real
     * `zip318Kind`. Locks in that the existing null-safe mapping needs no changes for
     * z/wt/migration_fixes/spec/2026-08-06-activity-pending-migrations-plan.md.
     */
    @Test
    fun migration_transfer_pending_row_maps_to_pending_state_with_transfer_kind() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 5 },
                        minedHeight = null,
                        expiryHeight = 2_500_000,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = -1_000,
                        totalSpent = 500_000,
                        totalReceived = 499_000,
                        feePaid = 1_000,
                        hasChange = false,
                        sentNoteCount = 1,
                        receivedNoteCount = 1,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 3 // Zip318Kind.TRANSFER
                    ),
                latestHeight = BlockHeight.new(2_000_000)
            )

        assertTrue(overview.isSentTransaction)
        assertNull(overview.raw)
        assertNull(overview.minedHeight)
        assertEquals(TransactionState.Pending, overview.transactionState)
        assertEquals(cash.z.ecc.android.sdk.model.Zip318Kind.TRANSFER, overview.zip318Kind)
    }

    /** Same shape, `PREPARATION` (note-split) kind — the other migration branch case. */
    @Test
    fun migration_preparation_pending_row_maps_to_pending_state_with_preparation_kind() {
        val overview =
            TransactionOverviewCursor.fromRow(
                row =
                    SlipstreamTransactionRow(
                        txId = ByteArray(32) { 6 },
                        minedHeight = null,
                        expiryHeight = 2_500_100,
                        txIndex = null,
                        raw = null,
                        accountBalanceDelta = -300,
                        totalSpent = 300,
                        totalReceived = 0,
                        feePaid = 300,
                        hasChange = false,
                        sentNoteCount = 0,
                        receivedNoteCount = 3,
                        memoCount = 0,
                        blockTime = null,
                        isShielding = false,
                        isExpiredUnmined = 0L,
                        zip318Kind = 2 // Zip318Kind.PREPARATION
                    ),
                latestHeight = BlockHeight.new(2_000_000)
            )

        assertTrue(overview.isSentTransaction)
        assertEquals(TransactionState.Pending, overview.transactionState)
        assertEquals(cash.z.ecc.android.sdk.model.Zip318Kind.PREPARATION, overview.zip318Kind)
    }
}
