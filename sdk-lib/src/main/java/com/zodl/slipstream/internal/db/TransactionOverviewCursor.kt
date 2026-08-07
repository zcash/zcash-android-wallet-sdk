package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import com.zodl.slipstream.model.SlipstreamTransactionRow
import kotlin.math.absoluteValue

/**
 * Maps one [SlipstreamTransactionRow] (the `listTransactions` native's output) to the SDK's
 * public [TransactionOverview] - the adapter's twin of the upstream SDK's internal
 * `AllTransactionView.kt` cursor parser + `TransactionOverview.new` factory (both unreachable
 * from this module: the cursor parser is a private val, and `TransactionOverview.new` depends on
 * the internal `DbTransactionOverview` type), built instead over the public 17-field constructor.
 */
internal object TransactionOverviewCursor {
    /**
     * Pure row mapper - [row] is already-constructed by the native, so this is fully
     * JVM-unit-testable without an `android.database.Cursor` (`SDK_ADAPTER_PLAN.md` law 5).
     *
     * @param latestHeight the engine snapshot's `chainTip` at query time (the adapter's twin of
     *   the upstream SDK folding `processor.networkHeight` into the flow,
     *   `SdkSynchronizer.kt:360-374`); 0 or unknown -> pass `null`.
     */
    fun fromRow(
        row: SlipstreamTransactionRow,
        latestHeight: BlockHeight?
    ): TransactionOverview {
        val minedBlockHeight = row.minedHeight?.let(BlockHeight::new)
        val expiryBlockHeight = row.expiryHeight?.takeIf { it != 0L }?.let(BlockHeight::new)
        val isSent = row.accountBalanceDelta < 0

        return TransactionOverview(
            txId = TransactionId.new(row.txId),
            minedHeight = minedBlockHeight,
            expiryHeight = expiryBlockHeight,
            index = row.txIndex,
            raw = row.raw?.let(::FirstClassByteArray),
            isSentTransaction = isSent,
            netValue = Zatoshi(row.accountBalanceDelta.absoluteValue),
            totalSpent = Zatoshi(row.totalSpent),
            totalReceived = Zatoshi(row.totalReceived),
            feePaid = row.feePaid?.let(::Zatoshi),
            isChange = row.hasChange,
            receivedNoteCount = row.receivedNoteCount,
            sentNoteCount = row.sentNoteCount,
            memoCount = row.memoCount,
            blockTimeEpochSeconds = row.blockTime,
            transactionState =
                computeTransactionState(
                    latestHeight = latestHeight,
                    minedHeight = minedBlockHeight,
                    expiryHeight = expiryBlockHeight,
                    isExpiredUnmined = row.isExpiredUnmined?.let { it != 0L }
                ),
            isShielding = row.isShielding,
            // NOT PROJECTED by this read path. `host_read.rs`'s `listTransactions` SQL selects a
            // fixed column list that does not include `spent_note_count`, `pool_crossing_value` or
            // `trust_status`, and `SlipstreamTransactionRow` has no slot for them, so there is
            // nothing to carry here. These values reproduce the shape this path had before the
            // columns existed, so nothing regresses; a transaction read through slipstream simply
            // does not gain them. `isTrusted = false` is the conservative reading (the longer,
            // untrusted confirmation count). Widening the projection means extending the SQL, the
            // row type, and the `SlipstreamTransactionRow` JNI constructor signature in lockstep —
            // a follow-up, not merge work. The SDK's own `AllTransactionView` does project them.
            spentNoteCount = 0,
            poolCrossingValue = null,
            isTrusted = false,
            // `zip318_kind` is selected from `v_transactions` by our own `host_read.rs`
            // (backend-lib, not the external slipstream-core crate) — see that file's 2026-08-03
            // doc update.
            zip318Kind = Zip318Kind.new(row.zip318Kind)
        )
    }
}
