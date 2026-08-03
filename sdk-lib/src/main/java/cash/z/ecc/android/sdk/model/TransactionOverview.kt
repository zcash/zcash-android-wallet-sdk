package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.model.DbTransactionOverview
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository

/**
 * High level transaction information, suitable for mapping to a display of transaction history.
 *
 * Note that both sent and received transactions will have a positive net value.  Consumers of this class must check
 * [isSentTransaction] if displaying negative values is desired.
 *
 * Pending transactions are identified by a null [minedHeight].  Pending transactions are considered expired if the
 * last synced block exceeds the [expiryHeight].
 */
data class TransactionOverview(
    val txId: TransactionId,
    val minedHeight: BlockHeight?,
    val expiryHeight: BlockHeight?,
    val index: Long?,
    val raw: FirstClassByteArray?,
    val isSentTransaction: Boolean,
    val netValue: Zatoshi,
    val totalSpent: Zatoshi,
    val totalReceived: Zatoshi,
    val feePaid: Zatoshi?,
    val isChange: Boolean,
    val receivedNoteCount: Int,
    val sentNoteCount: Int,
    val memoCount: Int,
    val blockTimeEpochSeconds: Long?,
    val transactionState: TransactionState,
    val isShielding: Boolean,
    /**
     * How this transaction classifies against ZIP 318, the Orchard to Ironwood pool migration.
     *
     * [Zip318Kind.NOT_CLASSIFIED] means the wallet has not looked, not that the transaction is
     * not a migration, so it warrants no label at all.
     */
    val zip318Kind: Zip318Kind
) {
    override fun toString() = "TransactionOverview"

    companion object {
        internal fun new(
            dbTransactionOverview: DbTransactionOverview,
            latestBlockHeight: BlockHeight?
        ): TransactionOverview =
            TransactionOverview(
                txId = TransactionId(dbTransactionOverview.rawId),
                minedHeight = dbTransactionOverview.minedHeight,
                expiryHeight = dbTransactionOverview.expiryHeight,
                index = dbTransactionOverview.index,
                raw = dbTransactionOverview.raw,
                isSentTransaction = dbTransactionOverview.isSentTransaction,
                netValue = dbTransactionOverview.netValue,
                feePaid = dbTransactionOverview.feePaid,
                isChange = dbTransactionOverview.isChange,
                receivedNoteCount = dbTransactionOverview.receivedNoteCount,
                sentNoteCount = dbTransactionOverview.sentNoteCount,
                memoCount = dbTransactionOverview.memoCount,
                blockTimeEpochSeconds = dbTransactionOverview.blockTimeEpochSeconds,
                transactionState =
                    TransactionState.new(
                        latestBlockHeight = latestBlockHeight,
                        minedHeight = dbTransactionOverview.minedHeight,
                        expiryHeight = dbTransactionOverview.expiryHeight,
                        isExpiredUnmined = dbTransactionOverview.isExpiredUnmined
                    ),
                isShielding = dbTransactionOverview.isShielding,
                totalSpent = dbTransactionOverview.totalSpent,
                totalReceived = dbTransactionOverview.totalReceived,
                zip318Kind = dbTransactionOverview.zip318Kind
            )
    }

    private fun isExpired() = expiryHeight != null && TransactionState.Expired == transactionState

    internal suspend fun checkAndFillInTime(storage: DerivedDataRepository): TransactionOverview =
        if (isExpired() && blockTimeEpochSeconds == null) {
            Twig.debug { "Expired transaction ${txId.txIdString()} - going to find its time" }
            storage.findBlockByHeight(expiryHeight!!)?.run {
                Twig.debug { "Expired transaction ${txId.txIdString()} - time found: $blockTimeEpochSeconds" }
                this@TransactionOverview.copy(blockTimeEpochSeconds = blockTimeEpochSeconds)
            } ?: this
        } else {
            this
        }
}

enum class TransactionState {
    Confirmed,
    Pending,
    Expired;

    companion object {
        private const val MIN_CONFIRMATIONS = 10

        internal fun new(
            latestBlockHeight: BlockHeight?,
            minedHeight: BlockHeight?,
            expiryHeight: BlockHeight?,
            isExpiredUnmined: Boolean?
        ): TransactionState {
            if (isExpiredUnmined != null && isExpiredUnmined) return Expired

            return latestBlockHeight?.let { chainTip ->
                minedHeight?.let { minedHeight ->
                    // A transaction mined in the latest block has 1 confirmation.
                    if ((chainTip + 1 - minedHeight) >= MIN_CONFIRMATIONS) {
                        Confirmed
                    } else {
                        Pending
                    }
                } ?: expiryHeight?.let { expiryHeight ->
                    // Expiry height is the last height at which a transaction can be mined.
                    // If the chain tip is greater than or equal to the expiry height, the
                    // transaction can never be mined. A value of 0 disables expiry.
                    if (expiryHeight.value == 0L || expiryHeight > chainTip) {
                        Pending
                    } else {
                        Expired
                    }
                }
                // Base case: either we don't know the latest block height (unlikely if we
                // know about transactions), or the transaction is both unmined and has an
                // unknown expiry height (because we haven't seen the full transaction).
                // Treat these as Pending because the status will change as we sync.
            } ?: Pending
        }
    }
}
