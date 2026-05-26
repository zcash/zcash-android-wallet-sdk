package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.lightwallet.client.CombinedWalletClient

@Suppress("TooManyFunctions")
internal class OutboundTransactionManagerImpl(
    internal val encoder: TransactionEncoder,
    private val walletClient: CombinedWalletClient,
    private val sdkFlags: SdkFlags
) : OutboundTransactionManager {
    /**
     * Creates a proposal for transferring funds from a ZIP-321 compliant payment URI
     *
     * @param account the account from which to transfer funds.
     * @param uri a ZIP-321 compliant payment URI
     *
     * @return the proposal or an exception
     */
    override suspend fun proposeTransferFromUri(
        account: Account,
        uri: String
    ): Proposal = encoder.proposeTransferFromUri(account, uri)

    override suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: String
    ): Proposal {
        val memoBytes =
            if (memo.isBlank()) {
                null
            } else {
                memo.toByteArray()
            }
        return encoder.proposeTransfer(account, recipient, amount, memoBytes)
    }

    override suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: String,
        transparentReceiver: String?
    ): Proposal? {
        val memoBytes =
            if (memo.isBlank()) {
                null
            } else {
                memo.toByteArray()
            }
        return encoder.proposeShielding(account, shieldingThreshold, memoBytes, transparentReceiver)
    }

    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<EncodedTransaction> = encoder.createProposedTransactions(proposal, usk)

    override suspend fun submit(encodedTransaction: EncodedTransaction): TransactionSubmitResult =
        walletClient.submitTransaction(encodedTransaction.raw, encodedTransaction.txId, sdkFlags)

    override suspend fun createPcztFromProposal(
        accountUuid: AccountUuid,
        proposal: Proposal
    ) = encoder.createPcztFromProposal(accountUuid, proposal)

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt = encoder.redactPcztForSigner(pczt)

    override suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean = encoder.pcztRequiresSaplingProofs(pczt)

    override suspend fun addProofsToPczt(pczt: Pczt) = encoder.addProofsToPczt(pczt)

    override suspend fun extractAndStoreTxFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ) = encoder.extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures)

    override suspend fun isValidShieldedAddress(address: String) = encoder.isValidShieldedAddress(address)

    override suspend fun isValidTransparentAddress(address: String) = encoder.isValidTransparentAddress(address)

    override suspend fun isValidUnifiedAddress(address: String) = encoder.isValidUnifiedAddress(address)

    override suspend fun isValidTexAddress(address: String) = encoder.isValidTexAddress(address)

    //
    // Helper functions
    //

    companion object {
        fun new(
            encoder: TransactionEncoder,
            lightWalletClient: CombinedWalletClient,
            sdkFlags: SdkFlags
        ): OutboundTransactionManager =
            OutboundTransactionManagerImpl(
                encoder,
                lightWalletClient,
                sdkFlags
            )
    }
}
