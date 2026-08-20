package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionState
import org.junit.Test
import kotlin.test.assertEquals

class TransactionStatesTest {
    @Test
    fun expired_unmined_flag_always_wins() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(100),
                minedHeight = null,
                expiryHeight = BlockHeight.new(50),
                isExpiredUnmined = true
            )
        assertEquals(TransactionState.Expired, state)
    }

    @Test
    fun mined_with_at_least_min_confirmations_is_confirmed() {
        // chainTip + 1 - minedHeight >= 10
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_009),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = null,
                isExpiredUnmined = false
            )
        assertEquals(TransactionState.Confirmed, state)
    }

    @Test
    fun mined_with_fewer_than_min_confirmations_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_005),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = null,
                isExpiredUnmined = false
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unmined_within_expiry_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_000),
                minedHeight = null,
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unmined_past_expiry_is_expired() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_020),
                minedHeight = null,
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false
            )
        assertEquals(TransactionState.Expired, state)
    }

    @Test
    fun unmined_with_zero_expiry_never_expires() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_000_000),
                minedHeight = null,
                expiryHeight = BlockHeight.new(0),
                isExpiredUnmined = false
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unknown_chain_tip_is_always_pending() {
        val state =
            computeTransactionState(
                latestHeight = null,
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = BlockHeight.new(1_010),
                isExpiredUnmined = false
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun unmined_unknown_expiry_is_pending() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(500),
                minedHeight = null,
                expiryHeight = null,
                isExpiredUnmined = false
            )
        assertEquals(TransactionState.Pending, state)
    }

    @Test
    fun null_expired_unmined_flag_does_not_short_circuit() {
        val state =
            computeTransactionState(
                latestHeight = BlockHeight.new(1_009),
                minedHeight = BlockHeight.new(1_000),
                expiryHeight = null,
                isExpiredUnmined = null
            )
        assertEquals(TransactionState.Confirmed, state)
    }
}
