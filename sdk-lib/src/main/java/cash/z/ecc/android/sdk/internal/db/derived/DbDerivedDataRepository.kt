package cash.z.ecc.android.sdk.internal.db.derived

import cash.z.ecc.android.sdk.internal.model.DbBlock
import cash.z.ecc.android.sdk.internal.model.DbTransactionOverview
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.internal.model.OutputProperties
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionRecipient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import java.util.UUID

@Suppress("TooManyFunctions")
internal class DbDerivedDataRepository(
    private val derivedDataDb: DerivedDataDb
) : DerivedDataRepository {
    private val invalidatingFlow = MutableStateFlow(UUID.randomUUID())

    override suspend fun firstUnenhancedHeight(): BlockHeight? =
        derivedDataDb.allTransactionView.firstUnenhancedHeight()

    override suspend fun findEncodedTransactionByTxId(txId: FirstClassByteArray): EncodedTransaction? =
        derivedDataDb.transactionTable.findEncodedTransactionByTxId(txId)

    override suspend fun findUnminedTransactionsWithinExpiry(blockHeight: BlockHeight): List<DbTransactionOverview> =
        derivedDataDb.allTransactionView.getUnminedUnexpiredTransactions(blockHeight).toList()

    override suspend fun getOldestTransaction() = derivedDataDb.allTransactionView.getOldestTransaction()

    override suspend fun findMinedHeight(rawTransactionId: ByteArray) =
        derivedDataDb.transactionTable
            .findMinedHeight(rawTransactionId)

    override suspend fun findMatchingTransactionId(rawTransactionId: ByteArray) =
        derivedDataDb.transactionTable
            .findDatabaseId(rawTransactionId)

    override suspend fun getTransactionCount() = derivedDataDb.transactionTable.count()

    override suspend fun getTransactions(accountUuid: AccountUuid) =
        invalidatingFlow.map { derivedDataDb.allTransactionView.getTransactions(accountUuid).toList() }

    override fun invalidate() {
        invalidatingFlow.value = UUID.randomUUID()
    }

    override val allTransactions: Flow<List<DbTransactionOverview>>
        get() = invalidatingFlow.map { derivedDataDb.allTransactionView.getAllTransactions().toList() }

    override fun getOutputProperties(transactionId: TransactionId) =
        derivedDataDb.txOutputsView
            .getOutputProperties(transactionId.value)

    override suspend fun getAllOutputProperties(): Map<TransactionId, List<OutputProperties>> =
        derivedDataDb.txOutputsView
            .getAllOutputProperties()
            .toList()
            .groupBy(
                keySelector = { (transactionId, _) -> TransactionId(transactionId) },
                valueTransform = { (_, outputProperties) -> outputProperties }
            )

    override fun getTransactionsByMemoSubstring(query: String): Flow<List<TransactionId>> =
        flow {
            emit(
                derivedDataDb
                    .txOutputsView
                    .getTransactionsByMemoSubstring(query)
                    .map { byteArray -> TransactionId(byteArray) }
                    .toList()
            )
        }

    override fun getRecipients(transactionId: TransactionId): Flow<TransactionRecipient> =
        derivedDataDb.txOutputsView.getRecipients(transactionId.value)

    override suspend fun getAllRecipients(): Map<TransactionId, List<TransactionRecipient>> =
        derivedDataDb.txOutputsView
            .getAllRecipients()
            .toList()
            .groupBy(
                keySelector = { (transactionId, _) -> TransactionId(transactionId) },
                valueTransform = { (_, recipient) -> recipient }
            )

    override suspend fun debugQuery(query: String): String = derivedDataDb.debugQuery(query)

    override suspend fun findBlockByHeight(blockHeight: BlockHeight): DbBlock? =
        derivedDataDb.blockTable.findBlockByExpiryHeight(blockHeight)

    override suspend fun close() {
        derivedDataDb.close()
    }

    override suspend fun isClosed(): Boolean = derivedDataDb.isClosed()
}
