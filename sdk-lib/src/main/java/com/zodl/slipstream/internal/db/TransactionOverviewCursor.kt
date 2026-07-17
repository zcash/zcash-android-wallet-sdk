package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlin.math.absoluteValue

/**
 * Maps one `v_transactions` row (via [VisibleTransactionsQuery]) to the SDK's public
 * [TransactionOverview] - the adapter's twin of the upstream SDK's internal
 * `AllTransactionView.kt` cursor parser + `TransactionOverview.new` factory (both unreachable
 * from this module: the cursor parser is a private val, and `TransactionOverview.new` depends on
 * the internal `DbTransactionOverview` type), built instead over the public 17-field constructor.
 */
internal object TransactionOverviewCursor {
    /**
     * Pure row mapper - every parameter is an already-extracted column value, so this is fully
     * JVM-unit-testable without an `android.database.Cursor` (`SDK_ADAPTER_PLAN.md` law 5).
     *
     * @param latestHeight the engine snapshot's `chainTip` at query time (the adapter's twin of
     *   the upstream SDK folding `processor.networkHeight` into the flow,
     *   `SdkSynchronizer.kt:360-374`); 0 or unknown -> pass `null`.
     */
    @Suppress("LongParameterList")
    fun fromRow(
        txid: ByteArray,
        minedHeight: Long?,
        expiryHeight: Long?,
        txIndex: Long?,
        raw: ByteArray?,
        accountBalanceDelta: Long,
        totalSpent: Long,
        totalReceived: Long,
        feePaid: Long?,
        hasChange: Boolean,
        sentNoteCount: Int,
        receivedNoteCount: Int,
        memoCount: Int,
        blockTimeEpochSeconds: Long?,
        isShielding: Boolean,
        isExpiredUnmined: Boolean?,
        latestHeight: BlockHeight?
    ): TransactionOverview {
        val minedBlockHeight = minedHeight?.let(BlockHeight::new)
        // A raw expiry height of 0 means "no expiry" (disables expiry) - matches the upstream
        // cursor mapper (AllTransactionView.kt) folding 0 into null before state derivation.
        val expiryBlockHeight = expiryHeight?.takeIf { it != 0L }?.let(BlockHeight::new)
        val isSent = accountBalanceDelta < 0

        return TransactionOverview(
            txId = TransactionId.new(txid),
            minedHeight = minedBlockHeight,
            expiryHeight = expiryBlockHeight,
            index = txIndex,
            raw = raw?.let(::FirstClassByteArray),
            isSentTransaction = isSent,
            netValue = Zatoshi(accountBalanceDelta.absoluteValue),
            totalSpent = Zatoshi(totalSpent),
            totalReceived = Zatoshi(totalReceived),
            feePaid = feePaid?.let(::Zatoshi),
            isChange = hasChange,
            receivedNoteCount = receivedNoteCount,
            sentNoteCount = sentNoteCount,
            memoCount = memoCount,
            blockTimeEpochSeconds = blockTimeEpochSeconds,
            transactionState =
                computeTransactionState(
                    latestHeight = latestHeight,
                    minedHeight = minedBlockHeight,
                    expiryHeight = expiryBlockHeight,
                    isExpiredUnmined = isExpiredUnmined
                ),
            isShielding = isShielding
        )
    }
}
