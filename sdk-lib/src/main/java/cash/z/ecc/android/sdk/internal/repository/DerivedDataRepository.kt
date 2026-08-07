package cash.z.ecc.android.sdk.internal.repository

import cash.z.ecc.android.sdk.internal.model.DbBlock
import cash.z.ecc.android.sdk.internal.model.DbTransactionOverview
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.internal.model.OutputProperties
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionRecipient
import kotlinx.coroutines.flow.Flow

/**
 * Repository of wallet transactions, providing an agnostic interface to the underlying information.
 */
@Suppress("TooManyFunctions")
internal interface DerivedDataRepository {
    /**
     * The height of the first transaction that hasn't been enhanced yet.
     *
     * @return the height of the first un-enhanced transaction in the repository, or null in case of all transaction
     * enhanced or no entry found
     */
    suspend fun firstUnenhancedHeight(): BlockHeight?

    /**
     * Find the encoded transaction associated with the given id.
     *
     * @param txId the id of the transaction to find.
     *
     * @return the transaction or null when it cannot be found.
     */
    suspend fun findEncodedTransactionByTxId(txId: FirstClassByteArray): EncodedTransaction?

    /**
     * Find all unmined and unexpired transactions in the given height inclusively. This method
     * is intended for getting a transaction list for the transaction re-submitting. It returns a list to signal that
     * the intention is not to add them to a recyclerview or otherwise show in the UI.
     *
     * @param blockHeight the height of the block to which the query should be done
     *
     * @return a list of transactions that were unmined and are still in the expiry window till the give height
     * inclusively.
     */
    suspend fun findUnminedTransactionsWithinExpiry(blockHeight: BlockHeight): List<DbTransactionOverview>

    suspend fun getOldestTransaction(): DbTransactionOverview?

    /**
     * Find the mined height that matches the given raw tx_id in bytes. This is useful for matching
     * a pending transaction with one that we've decrypted from the blockchain.
     *
     * @param rawTransactionId the id of the transaction to find.
     *
     * @return the mined height of the given transaction, if it is known to this wallet.
     */
    suspend fun findMinedHeight(rawTransactionId: ByteArray): BlockHeight?

    suspend fun findMatchingTransactionId(rawTransactionId: ByteArray): Long?

    suspend fun getTransactionCount(): Long

    /**
     * Provides a way for other components to signal that the underlying data has been modified.
     */
    fun invalidate()

    //
    // Transactions
    //

    /*
     * Note there are two big limitations with this implementation:
     *  1. Clients don't receive notification if the underlying data changes.  A flow of flows could help there.
     *  2. Pagination isn't supported.  Although flow does a good job of allowing the data to be processed as a stream,
     *     that doesn't work so well in UI when users might scroll forwards/backwards.
     *
     * We'll come back to this and improve it in the future.  This implementation is already an improvement over
     * prior versions.
     */

    val allTransactions: Flow<List<DbTransactionOverview>>

    suspend fun getTransactions(accountUuid: AccountUuid): Flow<List<DbTransactionOverview>>

    fun getOutputProperties(transactionId: TransactionId): Flow<OutputProperties>

    /**
     * Batched alternative to [getOutputProperties] that returns the non-change output properties for ALL
     * transactions in a single query, grouped by [TransactionId].
     *
     * The map only contains entries for transactions that have at least one non-change output; a transaction
     * with only change outputs (or no outputs) is absent from the map rather than being present with an empty
     * list.
     */
    suspend fun getAllOutputProperties(): Map<TransactionId, List<OutputProperties>>

    fun getTransactionsByMemoSubstring(query: String): Flow<List<TransactionId>>

    /**
     * Recipients for [transactionId], oldest output first. May include wallet-internal entries (the recipient's
     * [TransactionRecipient.accountUuid] is non-null) whose [TransactionRecipient.addressValue] is the stored
     * receiving address where the wallet recorded one (else null), so a self-transfer (e.g. a pool migration)
     * surfaces a recipient instead of being filtered out as change.
     */
    fun getRecipients(transactionId: TransactionId): Flow<TransactionRecipient>

    /**
     * Batched alternative to [getRecipients] that returns the recipients for ALL transactions in a single query,
     * grouped by [TransactionId].
     *
     * The map only contains entries for transactions that have at least one recipient; a transaction with only
     * change outputs (or no outputs) is absent from the map rather than being present with an empty list.
     */
    suspend fun getAllRecipients(): Map<TransactionId, List<TransactionRecipient>>

    suspend fun debugQuery(query: String): String

    //
    // Blocks
    //

    suspend fun findBlockByHeight(blockHeight: BlockHeight): DbBlock?

    suspend fun close()

    suspend fun isClosed(): Boolean
}
