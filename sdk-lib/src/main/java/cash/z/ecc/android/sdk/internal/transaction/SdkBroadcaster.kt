package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

internal class SdkBroadcaster(
    private val txManager: OutboundTransactionManager,
    private val transactionSubmitter: TransactionSubmitter
) : Broadcaster {
    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<CreatedTransaction> =
        txManager
            .createProposedTransactions(proposal, usk)
            .map { it.toCreatedTransaction() }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): List<CreatedTransaction> =
        listOf(
            txManager
                .extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures)
                .toCreatedTransaction()
        )

    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult = transactionSubmitter.submit(transaction, endpoint)
}

internal interface TransactionSubmitter {
    suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult
}

internal suspend fun Broadcaster.createAndSubmitProposedTransactions(
    proposal: Proposal,
    usk: UnifiedSpendingKey,
    endpoint: LightWalletEndpoint
): Flow<TransactionSubmitResult> {
    var anySubmissionFailed = false
    return createProposedTransactions(proposal, usk)
        .asFlow()
        .map { transaction ->
            if (anySubmissionFailed) {
                TransactionSubmitResult.NotAttempted(transaction.txId)
            } else {
                val submission = submit(transaction, endpoint)
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

internal suspend fun Broadcaster.createAndSubmitTransactionFromPczt(
    pcztWithProofs: Pczt,
    pcztWithSignatures: Pczt,
    endpoint: LightWalletEndpoint
): Flow<TransactionSubmitResult> =
    createTransactionFromPczt(pcztWithProofs, pcztWithSignatures)
        .asFlow()
        .map { transaction -> submit(transaction, endpoint) }

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
            val response =
                walletClient.submitTransaction(
                    tx = transaction.raw.byteArray,
                    serviceMode =
                        sdkFlags ifTor
                            ServiceMode.Group("submit-${transaction.txId.byteArray.toHexReversed()}")
                )
            return response.toSubmitResult(transaction)
        } finally {
            walletClient.dispose()
        }
    }
}

private fun EncodedTransaction.toCreatedTransaction() =
    CreatedTransaction(
        txId = txId,
        raw = raw,
        expiryHeight = expiryHeight
    )

private fun Response<SendResponseUnsafe>.toSubmitResult(transaction: CreatedTransaction): TransactionSubmitResult =
    when (this) {
        is Response.Success -> {
            if (result.code == 0) {
                Twig.info {
                    "SUCCESS: submit transaction completed for:" +
                        " ${transaction.txId.byteArray.toHexReversed()}"
                }
                TransactionSubmitResult.Success(transaction.txId)
            } else {
                Twig.error {
                    "FAILURE! submit transaction ${transaction.txId.byteArray.toHexReversed()} " +
                        "completed with response: ${result.code}: ${result.message}"
                }
                TransactionSubmitResult.Failure(
                    txId = transaction.txId,
                    grpcError = false,
                    code = result.code,
                    description = result.message
                )
            }
        }

        is Response.Failure -> {
            Twig.error {
                "FAILURE! submit transaction failed with gRPC response: $code: $description"
            }
            TransactionSubmitResult.Failure(
                txId = transaction.txId,
                grpcError = true,
                code = code,
                description = description
            )
        }
    }
