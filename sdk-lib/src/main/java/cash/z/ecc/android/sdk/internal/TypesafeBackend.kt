package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.RustLayerException
import cash.z.ecc.android.sdk.internal.model.JniBlockMeta
import cash.z.ecc.android.sdk.internal.model.RewindResult
import cash.z.ecc.android.sdk.internal.model.ScanRange
import cash.z.ecc.android.sdk.internal.model.ScanSummary
import cash.z.ecc.android.sdk.internal.model.SubtreeRoot
import cash.z.ecc.android.sdk.internal.model.TransactionDataRequest
import cash.z.ecc.android.sdk.internal.model.TransactionStatus
import cash.z.ecc.android.sdk.internal.model.TreeState
import cash.z.ecc.android.sdk.internal.model.WalletSummary
import cash.z.ecc.android.sdk.internal.model.ZcashProtocol
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountUsk
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SingleUseTransparentAddress
import cash.z.ecc.android.sdk.model.UnifiedAddressRequest
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork

@Suppress("TooManyFunctions")
internal interface TypesafeBackend {
    val backend: Backend

    val network: ZcashNetwork

    suspend fun getAccounts(): List<Account>

    suspend fun createAccountAndGetSpendingKey(
        accountName: String,
        keySource: String?,
        seed: FirstClassByteArray,
        treeState: TreeState,
        recoverUntil: BlockHeight?
    ): AccountUsk

    suspend fun importAccountUfvk(
        recoverUntil: BlockHeight?,
        setup: AccountImportSetup,
        treeState: TreeState,
    ): Account

    suspend fun getAccountForUfvk(ufvk: UnifiedFullViewingKey): Account?

    suspend fun proposeTransferFromUri(
        account: Account,
        uri: String
    ): Proposal

    @Suppress("LongParameterList")
    suspend fun proposeTransfer(
        account: Account,
        to: String,
        value: Long,
        memo: ByteArray? = null
    ): Proposal

    suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Long,
        memo: ByteArray? = null,
        transparentReceiver: String? = null
    ): Proposal?

    suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<FirstClassByteArray>

    /**
     * Creates a partially-created (unsigned without proofs) transaction from the given proposal.
     *
     * Do not call this multiple times in parallel, or you will generate PCZT instances that, if
     * finalized, would double-spend the same notes.
     *
     * @return the partially created transaction in its serialized format.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    suspend fun createPcztFromProposal(
        account: Account,
        proposal: Proposal
    ): Pczt

    /**
     * Redacts information from the given PCZT that is unnecessary for the Signer role.
     *
     * @return the updated PCZT in its serialized format.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    suspend fun redactPcztForSigner(pczt: Pczt): Pczt

    /**
     * Checks whether the caller needs to have downloaded the Sapling parameters.
     *
     * @param pczt The partially created transaction in its serialized format.
     *
     * @return `true` if this PCZT requires Sapling proofs.
     *
     * @throws PcztException.RedactPcztForSignerException as a common indicator of the operation failure
     */
    suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean

    /**
     * Adds proofs to the given PCZT.
     *
     * @return the updated PCZT in its serialized format.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    suspend fun addProofsToPczt(pczt: Pczt): Pczt

    /**
     * Takes a PCZT that has been separately proven and signed, finalizes it, and stores
     * it in the wallet.
     *
     * @return the txid of the completed transaction.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    suspend fun extractAndStoreTxFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt,
    ): FirstClassByteArray

    @Throws(RustLayerException.GetAddressException::class)
    suspend fun getCurrentAddress(account: Account): String

    /**
     * Generates and returns an ephemeral address for one-time use, such as when receiving a swap
     * from a decentralized exchange.
     */
    @Throws(RustLayerException.GetAddressException::class)
    suspend fun getSingleUseTransparentAddress(accountUuid: AccountUuid): SingleUseTransparentAddress

    @Throws(RustLayerException.GetAddressException::class)
    suspend fun getNextAvailableAddress(
        account: Account,
        request: UnifiedAddressRequest
    ): String

    suspend fun listTransparentReceivers(account: Account): List<String>

    fun getBranchIdForHeight(height: BlockHeight): Long

    suspend fun rewindToHeight(height: BlockHeight): RewindResult

    /**
     * Truncates the data database to the specified chain state.
     *
     * In contrast to [rewindToHeight], this method allows the caller to truncate the wallet
     * database to a precise height by providing additional chain state information needed for
     * note commitment tree maintenance after the truncation.
     */
    suspend fun truncateToChainState(chainState: TreeState)

    suspend fun getLatestCacheHeight(): BlockHeight?

    suspend fun findBlockMetadata(height: BlockHeight): JniBlockMeta?

    suspend fun rewindBlockMetadataToHeight(height: BlockHeight)

    suspend fun getDownloadedUtxoBalance(address: String): Zatoshi

    @Suppress("LongParameterList")
    suspend fun putUtxo(
        txId: ByteArray,
        index: Int,
        script: ByteArray,
        value: Long,
        height: BlockHeight
    )

    suspend fun getMemoAsUtf8(
        txId: ByteArray,
        protocol: ZcashProtocol,
        outputIndex: Int
    ): String?

    @Throws(InitializeException::class)
    suspend fun initDataDb(seed: FirstClassByteArray?)

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun putSubtreeRoots(
        saplingStartIndex: UInt,
        saplingRoots: List<SubtreeRoot>,
        orchardStartIndex: UInt,
        orchardRoots: List<SubtreeRoot>,
    )

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun updateChainTip(height: BlockHeight)

    /**
     * Returns the height to which the wallet has been fully scanned.
     *
     * This is the height for which the wallet has fully trial-decrypted this and all
     * preceding blocks above the wallet's birthday height.
     *
     * @return The height to which the wallet has been fully scanned, or Null if no blocks have been scanned.
     * @throws RustLayerException.GetFullyScannedHeight as a common indicator of the operation failure
     */
    @Throws(RustLayerException.GetFullyScannedHeight::class)
    suspend fun getFullyScannedHeight(): BlockHeight?

    /**
     * Returns the maximum height that the wallet has scanned.
     *
     * If the wallet is fully synced, this will be equivalent to `getFullyScannedHeight`;
     * otherwise the maximal scanned height is likely to be greater than the fully scanned
     * height due to the fact that out-of-order scanning can leave gaps.
     *
     * @return The maximum height that the wallet has scanned, or Null if no blocks have been scanned.
     * @throws RustLayerException.GetMaxScannedHeight as a common indicator of the operation failure
     */
    @Throws(RustLayerException.GetMaxScannedHeight::class)
    suspend fun getMaxScannedHeight(): BlockHeight?

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun scanBlocks(
        fromHeight: BlockHeight,
        fromState: TreeState,
        limit: Long
    ): ScanSummary

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun transactionDataRequests(): List<TransactionDataRequest>

    /**
     * This function calls `check_witnesses` and `queue_rescans` from the underlying librustzcash internally. This
     * method is intended for repairing wallets that broke due to bugs in `shardtree`.
     *
     * `check_witnesses`: It attempts to construct a witness for each note belonging to the wallet that is believed by
     * the wallet to currently be spendable, and returns a vector of the ranges that must be
     * rescanned in order to correct missing witness data.
     *
     * `queue_rescans`: Updates the scan queue by inserting scan ranges for the given range of block heights, with
     * the specified scanning priority.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun fixWitnesses()

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun getWalletSummary(): WalletSummary?

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun suggestScanRanges(): List<ScanRange>

    suspend fun decryptAndStoreTransaction(
        tx: ByteArray,
        minedHeight: BlockHeight?
    ): FirstClassByteArray

    suspend fun setTransactionStatus(
        txId: ByteArray,
        status: TransactionStatus,
    )

    fun getSaplingReceiver(ua: String): String?

    fun getTransparentReceiver(ua: String): String?

    suspend fun initBlockMetaDb(): Int

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun writeBlockMetadata(blockMetadata: List<JniBlockMeta>)

    fun isValidSaplingAddr(addr: String): Boolean

    fun isValidTransparentAddr(addr: String): Boolean

    fun isValidUnifiedAddr(addr: String): Boolean

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    fun isValidTexAddr(addr: String): Boolean

    suspend fun deleteAccount(accountUuid: AccountUuid): Boolean
}
