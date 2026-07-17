package com.zodl.slipstream.internal.db

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisibleTransactionsQueryTest {
    @Test
    fun recovering_scope_joins_the_reconciled_view_and_keeps_reconciled_rows() {
        val sql = VisibleTransactionsQuery.forScope(isRecovering = true, filterByAccount = false)
        assertTrue(sql.contains("LEFT JOIN slipstream_v_tx_reconciled"))
        assertTrue(sql.contains("COALESCE(r.reconciled, 1) = 1")) // absent view row => reconciled
    }

    @Test
    fun non_recovering_scope_shows_everything() { // section 7.1: hiding here is the "vanishing tx" bug
        val sql = VisibleTransactionsQuery.forScope(isRecovering = false, filterByAccount = false)
        assertFalse(sql.contains("slipstream_v_tx_reconciled"))
    }

    @Test
    fun account_filter_binds_a_placeholder_never_inlines() {
        val sql = VisibleTransactionsQuery.forScope(isRecovering = false, filterByAccount = true)
        assertTrue(sql.contains("tx.account_uuid = ?"))
    }

    @Test
    fun unmined_transactions_sort_first() { // upstream sort_height = IFNULL(mined, UInt.MAX)
        val sql = VisibleTransactionsQuery.forScope(isRecovering = true, filterByAccount = false)
        assertTrue(sql.contains("IFNULL(tx.mined_height, 4294967295) DESC"))
    }

    @Test
    fun recovering_and_account_filter_combine_with_and() {
        val sql = VisibleTransactionsQuery.forScope(isRecovering = true, filterByAccount = true)
        assertTrue(sql.contains("COALESCE(r.reconciled, 1) = 1 AND tx.account_uuid = ?"))
    }

    @Test
    fun non_recovering_without_account_filter_has_no_where_clause() {
        val sql = VisibleTransactionsQuery.forScope(isRecovering = false, filterByAccount = false)
        assertFalse(sql.contains("WHERE"))
    }
}
