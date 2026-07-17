package com.zodl.slipstream.internal.db

/**
 * Projection = the upstream `v_transactions` view (mirrors, does not link, the internal
 * `AllTransactionView.kt` column constants - `AllTransactionView.kt:236-272`). `expired_unmined`
 * drives the `TransactionStates` state derivation.
 */
internal object VisibleTransactionsQuery {
    private const val SELECT =
        "SELECT tx.txid, tx.mined_height, tx.expiry_height, tx.tx_index, tx.raw, " +
            "tx.account_balance_delta, tx.total_spent, tx.total_received, tx.fee_paid, tx.has_change, " +
            "tx.sent_note_count, tx.received_note_count, tx.memo_count, tx.block_time, tx.is_shielding, " +
            "tx.expired_unmined, tx.account_uuid FROM v_transactions AS tx"

    /** `UInt.MAX_VALUE` (4294967295) = the upstream `sort_height` NULL sentinel; unmined (NULL `mined_height`) sorts first. */
    private const val ORDER = " ORDER BY IFNULL(tx.mined_height, 4294967295) DESC, tx.tx_index DESC"

    /**
     * `FFI_JNI_CONTRACT.md` section 7.1 verbatim: visible = reconciled OR NOT is_recovering.
     * Absent reconciled row => reconciled. Outside recovery the join is skipped entirely (hot-path
     * rule - and hiding a flagged row on a synced wallet is the "vanishing tx" bug).
     */
    fun forScope(
        isRecovering: Boolean,
        filterByAccount: Boolean
    ): String {
        val join = if (isRecovering) " LEFT JOIN slipstream_v_tx_reconciled AS r ON r.txid = tx.txid" else ""
        val where =
            buildList {
                if (isRecovering) add("COALESCE(r.reconciled, 1) = 1")
                if (filterByAccount) add("tx.account_uuid = ?")
            }.let { conditions -> if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ") }
        return SELECT + join + where + ORDER
    }
}
