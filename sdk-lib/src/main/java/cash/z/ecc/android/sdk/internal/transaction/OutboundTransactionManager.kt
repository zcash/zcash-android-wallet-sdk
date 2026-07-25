package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi

/**
 * Manage outbound transactions with the main purpose of reporting which ones are still pending,
 * particularly after failed attempts or dropped connectivity. The intent is to help see outbound
 * transactions through to completion.
 */
@Suppress("TooManyFunctions")
internal interface OutboundTransactionManager {
    /**
     * Creates a proposal for transferring funds from a ZIP-321 compliant payment URI
     *
     * @param account the account from which to transfer funds.
     * @param uri a ZIP-321 compliant payment URI
     *
     * @return the proposal or an exception
     */
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
     */
    suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: String
    ): Proposal

    /**
     * Creates a proposal migrating the account's entire Orchard balance into Ironwood.
     *
     * @param account the account whose Orchard funds to migrate.
     *
     * @return the proposal or an exception
     */
    suspend fun proposeOrchardToIronwoodMigration(account: Account): Proposal

    /**
     * Creates a proposal for shielding any transparent funds received by the given account.
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
     * @throws Exception if `transparentReceiver` is null and there are transparent funds
     *         in more than one of the account's transparent receivers.
     */
    suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: String,
        transparentReceiver: String?
    ): Proposal?

    /**
     * Creates the transactions in the given proposal.
     *
     * @param proposal the proposal for which to create transactions.
     * @param usk the unified spending key associated with the account for which the
     *            proposal was created.
     *
     * @return the successfully encoded transactions or an exception
     */
    suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<EncodedTransaction>

    /**
     * Submits the transaction represented by [encodedTransaction] to lightwalletd to broadcast to the
     * network and, hopefully, include in the next block.
     *
     * @param encodedTransaction the transaction information containing the raw bytes that will be submitted
     * to lightwalletd.
     * @return true if the transaction was successfully submitted to lightwalletd.
     */
    suspend fun submit(encodedTransaction: EncodedTransaction): TransactionSubmitResult

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
     * Return true when the given address is a valid t-addr.
     *
     * @param address the address to validate.
     *
     * @return true when the given address is a valid t-addr.
     */
    suspend fun isValidShieldedAddress(address: String): Boolean

    /**
     * Return true when the given address is a valid z-addr.
     *
     * @param address the address to validate.
     *
     * @return true when the given address is a valid z-addr.
     */
    suspend fun isValidTransparentAddress(address: String): Boolean

    /**
     * Return true when the given address is a valid ZIP 316 Unified Address.
     *
     * @param address the address to validate.
     *
     * @return true when the given address is a valid ZIP 316 Unified Address.
     */
    suspend fun isValidUnifiedAddress(address: String): Boolean

    /**
     * Return true when the given address is a valid ZIP 320 TEX address.
     *
     * @param address the address to validate.
     *
     * @return true when the given address is a valid ZIP 320 TEX address.
     */
    suspend fun isValidTexAddress(address: String): Boolean
}

/**
 * Interface for transaction errors.
 */
interface TransactionError {
    /**
     * The message associated with this error.
     */
    val message: String
}
