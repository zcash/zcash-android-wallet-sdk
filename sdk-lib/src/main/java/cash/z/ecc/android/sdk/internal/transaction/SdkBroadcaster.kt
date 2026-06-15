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
    ): List<CreatedTransaction> =
        pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan {
            txManager
                .createProposedTransactions(proposal, usk)
                .map { it.toCreatedTransaction() }
        }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): List<CreatedTransaction> =
        pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan {
            listOf(
                txManager
                    .extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures)
                    .toCreatedTransaction()
            )
        }

    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult {
        // Record the endpoint AFTER the submit returns so the in-flight window stays at
        // AwaitingPlan (or the prior Ready state) — the sync loop's resubmit step skips
        // AwaitingPlan and otherwise dedupes against the endpoints recorded here, so
        // recording before the submit returns would let a concurrent resubmit race the
        // in-flight broadcast against an endpoint we've claimed but not actually hit yet.
        val result = transactionSubmitter.submit(transaction, endpoint)
        pendingSubmitPlanStore.addSubmitEndpoint(transaction, endpoint)
        return result
    }

    // Legacy Synchronizer APIs route through the same plan-store machinery as the public
    // Broadcaster so the sync-loop's resubmit step skips in-flight submits.
    internal suspend fun createAndSubmitProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        endpoint: LightWalletEndpoint
    ): Flow<TransactionSubmitResult> =
        pendingSubmitPlanStore
            .createAndMarkAwaitingSubmitPlan {
                txManager
                    .createProposedTransactions(proposal, usk)
                    .map { it.toCreatedTransaction() }
            }.createSubmitResultFlow { transaction ->
                val result = transactionSubmitter.submit(transaction, endpoint)
                pendingSubmitPlanStore.addSubmitEndpoint(transaction, endpoint)
                result
            }

    internal suspend fun createAndSubmitTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt,
        endpoint: LightWalletEndpoint
    ): Flow<TransactionSubmitResult> =
        pendingSubmitPlanStore
            .createAndMarkAwaitingSubmitPlan {
                listOf(
                    txManager
                        .extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures)
                        .toCreatedTransaction()
                )
            }.asFlow()
            .map { transaction ->
                val result = transactionSubmitter.submit(transaction, endpoint)
                pendingSubmitPlanStore.addSubmitEndpoint(transaction, endpoint)
                result
            }
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
