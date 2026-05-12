package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class SdkBroadcaster(
    private val txManager: OutboundTransactionManager,
    private val transactionSubmitter: TransactionSubmitter,
    private val pendingSubmitPlanStore: PendingSubmitPlanStore
) : Broadcaster {
    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<CreatedTransaction> {
        val transactions =
            txManager
                .createProposedTransactions(proposal, usk)
                .map { it.toCreatedTransaction() }
        pendingSubmitPlanStore.markAwaitingSubmitPlan(transactions)
        return transactions
    }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): List<CreatedTransaction> {
        val transactions =
            listOf(
                txManager
                    .extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures)
                    .toCreatedTransaction()
            )
        pendingSubmitPlanStore.markAwaitingSubmitPlan(transactions)
        return transactions
    }

    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult {
        pendingSubmitPlanStore.addSubmitEndpoint(transaction, endpoint)
        return transactionSubmitter.submit(transaction, endpoint)
    }

    // Legacy Synchronizer APIs stay eligible for automatic resubmission, so these helpers
    // bypass public Broadcaster methods that record submitted endpoints.
    internal suspend fun createAndSubmitProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        endpoint: LightWalletEndpoint
    ): Flow<TransactionSubmitResult> =
        txManager
            .createProposedTransactions(proposal, usk)
            .map { it.toCreatedTransaction() }
            .createSubmitResultFlow { transaction ->
                transactionSubmitter.submit(transaction, endpoint)
            }

    internal suspend fun createAndSubmitTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt,
        endpoint: LightWalletEndpoint
    ): Flow<TransactionSubmitResult> =
        listOf(
            txManager
                .extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures)
                .toCreatedTransaction()
        ).asFlow()
            .map { transaction -> transactionSubmitter.submit(transaction, endpoint) }
}

internal interface TransactionSubmitter {
    suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult
}

internal class EndpointTransactionSubmitter(
    private val walletClientFactory: WalletClientFactory,
    private val sdkFlags: SdkFlags
) : TransactionSubmitter {
    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult {
        val walletClient = walletClientFactory.create(endpoint)
        try {
            return walletClient.submitTransaction(transaction.raw, transaction.txId, sdkFlags)
        } finally {
            withContext(NonCancellable) {
                walletClient.dispose()
            }
        }
    }
}

private fun List<CreatedTransaction>.createSubmitResultFlow(
    submit: suspend (CreatedTransaction) -> TransactionSubmitResult
): Flow<TransactionSubmitResult> {
    var anySubmissionFailed = false
    return asFlow()
        .map { transaction ->
            if (anySubmissionFailed) {
                TransactionSubmitResult.NotAttempted(transaction.txId)
            } else {
                val submission = submit(transaction)
                when (submission) {
                    is TransactionSubmitResult.Success -> {
                        // Expected state
                    }

                    is TransactionSubmitResult.Failure,
                    is TransactionSubmitResult.NotAttempted -> {
                        anySubmissionFailed = true
                    }
                }
                submission
            }
        }
}
