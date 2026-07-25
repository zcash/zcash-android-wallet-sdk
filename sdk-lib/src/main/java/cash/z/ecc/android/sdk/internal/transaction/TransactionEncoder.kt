package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi

@Suppress("TooManyFunctions")
internal interface TransactionEncoder {
    /**
     * Creates a proposal for transferring from a valid ZIP-321 Payment URI string
     *
     * @param account the account from which to transfer funds.
     * @param uri a valid ZIP-321 Payment URI string
     *
     * @return the proposal or an exception
     *
     * @throws TransactionEncoderException.ProposalFromUriException
     */
    @Throws(TransactionEncoderException.ProposalFromUriException::class)
    suspend fun proposeTransferFromUri(
        account: Account,
        uri: String
    ): Proposal

    /**
     * Creates a proposal for transferring funds to the given recipient.
     *
     * @param account the account from which to transfer funds.
     * @param recipient the recipient's address.
     * @param amount the amount of zatoshi to send.
     * @param memo the optional memo to include as part of the proposal's transactions.
     *
     * @return the proposal or an exception
     *
     * @throws TransactionEncoderException.ProposalFromParametersException
     */
    @Throws(TransactionEncoderException.ProposalFromParametersException::class)
    suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: ByteArray? = byteArrayOf()
    ): Proposal

    /**
     * Creates a proposal migrating the account's entire Orchard balance into Ironwood.
     */
    @Throws(TransactionEncoderException.ProposalFromParametersException::class)
    suspend fun proposeOrchardToIronwoodMigration(account: Account): Proposal

    /**
     * Creates a proposal for shielding any transparent funds sent to the given account.
     *
     * @param account the account for which to shield funds.
     * @param shieldingThreshold the minimum transparent balance required before a
     *                           proposal will be created.
     * @param memo the optional memo to include as part of the proposal's transactions.
     * @param transparentReceiver a specific transparent receiver within the account that
     *                            should be the source of transparent funds. Default is
     *                            null which will select whichever of the account's
     *                            transparent receivers has funds to shield.
     *
     * @return the proposal, or null if the transparent balance that would be shielded is
     *         zero or below `shieldingThreshold`.
     *
     * @throws TransactionEncoderException.ProposalShieldingException if `transparentReceiver` is null and there are
     * transparent funds in more than one of the account's transparent receivers.
     */
    @Throws(TransactionEncoderException.ProposalShieldingException::class)
    suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: ByteArray? = byteArrayOf(),
        transparentReceiver: String? = null
    ): Proposal?

    /**
     * Creates the transactions in the given proposal.
     *
     * @param proposal the proposal to create.
     * @param usk the unified spending key associated with the notes that will be spent.
     *
     * @return the successfully encoded transactions or an exception
     *
     * @throws TransactionEncoderException.TransactionNotCreatedException
     * @throws TransactionEncoderException.TransactionNotFoundException
     */
    @Throws(
        TransactionEncoderException.TransactionNotCreatedException::class,
        TransactionEncoderException.TransactionNotFoundException::class,
    )
    suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<EncodedTransaction>

    suspend fun createPcztFromProposal(
        accountUuid: AccountUuid,
        proposal: Proposal
    ): Pczt

    suspend fun redactPcztForSigner(pczt: Pczt): Pczt

    suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean

    suspend fun addProofsToPczt(pczt: Pczt): Pczt

    suspend fun extractAndStoreTxFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): EncodedTransaction

    /**
     * Utility function to help with validation.
     *
     * @param address the address to validate
     *
     * @return true when the given address is a valid z-addr
     */
    suspend fun isValidShieldedAddress(address: String): Boolean

    /**
     * Utility function to help with validation.
     *
     * @param address the address to validate
     *
     * @return true when the given address is a valid t-addr
     */
    suspend fun isValidTransparentAddress(address: String): Boolean

    /**
     * Utility function to help with validation.
     *
     * @param address the address to validate
     *
     * @return true when the given address is a valid ZIP 316 Unified Address
     */
    suspend fun isValidUnifiedAddress(address: String): Boolean

    /**
     * Utility function to help with validation. This is not called during [createTransaction]
     * because this class asserts that all validation is done externally by the UI, for now.
     *
     * @param address the address to validate
     *
     * @return true when the given address is a valid ZIP 320 TEX address
     */
    suspend fun isValidTexAddress(address: String): Boolean

    /**
     * Return the consensus branch that the encoder is using when making transactions.
     *
     * @param height the height at which we want to get the consensus branch
     *
     * @return id of consensus branch
     */
    suspend fun getConsensusBranchId(height: BlockHeight): Long
}
