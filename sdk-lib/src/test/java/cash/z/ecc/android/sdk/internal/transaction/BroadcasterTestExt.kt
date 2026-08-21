package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

internal suspend fun Broadcaster.createAndSubmitProposedTransactions(
    proposal: Proposal,
    usk: UnifiedSpendingKey,
    endpoint: LightWalletEndpoint
): Flow<TransactionSubmitResult> =
    createProposedTransactions(proposal, usk)
        .createSubmitResultFlow { transaction ->
            submit(transaction, endpoint)
        }

internal suspend fun Broadcaster.createAndSubmitTransactionFromPczt(
    pcztWithProofs: Pczt,
    pcztWithSignatures: Pczt,
    endpoint: LightWalletEndpoint
): Flow<TransactionSubmitResult> =
    createTransactionFromPczt(pcztWithProofs, pcztWithSignatures)
        .asFlow()
        .map { transaction -> submit(transaction, endpoint) }

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
