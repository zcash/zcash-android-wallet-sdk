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
 *
 * Transactions created through this API wait for the caller to submit them.
 * Once submitted, automatic retry uses the submitted endpoints instead of the
 * synchronizer's default endpoint.
 */
interface Broadcaster {
    /**
     * Creates and stores the transactions in [proposal] without submitting them.
     *
     * Created transactions will not be automatically resubmitted until they are
     * submitted through this API.
     */
    suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<CreatedTransaction>

    /**
     * Finalizes and stores a separately proven and signed PCZT without submitting it.
     *
     * Created transactions will not be automatically resubmitted until they are
     * submitted through this API.
     */
    suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): List<CreatedTransaction>

    /**
     * Submits [transaction] to the provided [endpoint].
     *
     * The endpoint is also remembered for automatic retry.
     */
    suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult
}
