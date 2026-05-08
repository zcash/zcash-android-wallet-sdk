package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/**
 * Creates transactions without immediately submitting them, and submits stored
 * transaction bytes to a specific lightwalletd endpoint.
 */
interface Broadcaster {
    /**
     * Creates and stores the transactions in [proposal] without submitting them.
     */
    suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<CreatedTransaction>

    /**
     * Finalizes and stores a separately proven and signed PCZT without submitting it.
     */
    suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): List<CreatedTransaction>

    /**
     * Submits [transaction] to the provided [endpoint].
     */
    suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult
}
