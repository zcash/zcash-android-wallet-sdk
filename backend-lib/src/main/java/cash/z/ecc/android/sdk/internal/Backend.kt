package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.JniAccount
import cash.z.ecc.android.sdk.internal.model.JniAccountUsk
import cash.z.ecc.android.sdk.internal.model.JniBlockMeta
import cash.z.ecc.android.sdk.internal.model.JniRewindResult
import cash.z.ecc.android.sdk.internal.model.JniScanRange
import cash.z.ecc.android.sdk.internal.model.JniScanSummary
import cash.z.ecc.android.sdk.internal.model.JniSingleUseTransparentAddress
import cash.z.ecc.android.sdk.internal.model.JniSubtreeRoot
import cash.z.ecc.android.sdk.internal.model.JniTransactionDataRequest
import cash.z.ecc.android.sdk.internal.model.JniWalletSummary
import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Contract defining the exposed capabilities of the Rust backend.
 * This is what welds the SDK to the Rust layer.
 * It is not documented because it is not intended to be used, directly.
 * Instead, use the synchronizer or one of its subcomponents.
 */
@Suppress("TooManyFunctions")
interface Backend {
    val networkId: Int

    val dataDbFile: File

    suspend fun initBlockMetaDb(): Int

    suspend fun proposeTransfer(
        accountUuid: ByteArray,
        to: String,
        value: Long,
        memo: ByteArray? = null
    ): ProposalUnsafe

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun proposeTransferFromUri(
        accountUuid: ByteArray,
        uri: String
    ): ProposalUnsafe

    suspend fun proposeShielding(
        accountUuid: ByteArray,
        shieldingThreshold: Long,
        memo: ByteArray? = null,
        transparentReceiver: String? = null
    ): ProposalUnsafe?

    suspend fun createProposedTransactions(
        proposal: ProposalUnsafe,
        unifiedSpendingKey: ByteArray
    ): List<ByteArray>

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
    @Throws(RuntimeException::class)
    suspend fun createPcztFromProposal(
        accountUuid: ByteArray,
        proposal: ProposalUnsafe
    ): ByteArray

    /**
     * Redacts information from the given PCZT that is unnecessary for the Signer role.
     *
     * @return the updated PCZT in its serialized format.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun redactPcztForSigner(pczt: ByteArray): ByteArray

    /**
     * Checks whether the caller needs to have downloaded the Sapling parameters.
     *
     * @return `true` if this PCZT requires Sapling proofs.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun pcztRequiresSaplingProofs(pczt: ByteArray): Boolean

    /**
     * Adds proofs to the given PCZT.
     *
     * @return the updated PCZT in its serialized format.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun addProofsToPczt(pczt: ByteArray): ByteArray

    /**
     * Takes a PCZT that has been separately proven and signed, finalizes it, and stores
     * it in the wallet.
     *
     * @return the txid of the completed transaction.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun extractAndStoreTxFromPczt(
        pcztWithProofs: ByteArray,
        pcztWithSignatures: ByteArray,
    ): ByteArray

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun decryptAndStoreTransaction(
        tx: ByteArray,
        minedHeight: Long?
    ): ByteArray

    /**
     * Sets up the internal structure of the data database.
     *
     * If `seed` is `null`, database migrations will be attempted without it.
     *
     * @return 0 if successful, 1 if the seed must be provided in order to execute the
     *         requested migrations, 2 if the provided seed is not relevant to any of the
     *         derived accounts in the wallet.
     *
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun initDataDb(seed: ByteArray?): Int

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun getAccounts(): List<JniAccount>

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun getAccountForUfvk(ufvk: String): JniAccount?

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun createAccount(
        accountName: String,
        keySource: String?,
        seed: ByteArray,
        treeState: ByteArray,
        recoverUntil: Long?,
    ): JniAccountUsk

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    @Suppress("LongParameterList")
    suspend fun importAccountUfvk(
        accountName: String,
        keySource: String?,
        ufvk: String,
        treeState: ByteArray,
        recoverUntil: Long?,
        purpose: Int,
        seedFingerprint: ByteArray?,
        zip32AccountIndex: Long?,
    ): JniAccount

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun isSeedRelevantToAnyDerivedAccounts(seed: ByteArray): Boolean

    fun isValidSaplingAddr(addr: String): Boolean

    fun isValidTransparentAddr(addr: String): Boolean

    fun isValidUnifiedAddr(addr: String): Boolean

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    fun isValidTexAddr(addr: String): Boolean

    @Throws(RuntimeException::class)
    suspend fun getCurrentAddress(accountUuid: ByteArray): String

    @Throws(RuntimeException::class)
    suspend fun getSingleUseTransparentAddress(accountUuid: ByteArray): JniSingleUseTransparentAddress

    @Throws(RuntimeException::class)
    suspend fun getNextAvailableAddress(
        accountUuid: ByteArray,
        receiverFlags: Int
    ): String

    fun getTransparentReceiver(ua: String): String?

    fun getSaplingReceiver(ua: String): String?

    suspend fun listTransparentReceivers(accountUuid: ByteArray): List<String>

    fun getBranchIdForHeight(height: Long): Long

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun getMemoAsUtf8(
        txId: ByteArray,
        protocol: Int,
        outputIndex: Int
    ): String?

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun rewindToHeight(height: Long): JniRewindResult

    /**
     * Truncates the data database to the specified chain state.
     *
     * In contrast to [rewindToHeight], this method allows the caller to truncate the wallet
     * database to a precise height by providing additional chain state information needed for
     * note commitment tree maintenance after the truncation.
     *
     * @param chainState protobuf-encoded TreeState at the height to truncate to.
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun rewindToChainState(chainState: ByteArray)

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun putSubtreeRoots(
        saplingStartIndex: Long,
        saplingRoots: List<JniSubtreeRoot>,
        orchardStartIndex: Long,
        orchardRoots: List<JniSubtreeRoot>,
    )

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun updateChainTip(height: Long)

    /**
     * Returns the height to which the wallet has been fully scanned.
     *
     * This is the height for which the wallet has fully trial-decrypted this and all
     * preceding blocks above the wallet's birthday height.
     *
     * @return The height to which the wallet has been fully scanned, or Null if no blocks have been scanned.
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun getFullyScannedHeight(): Long?

    /**
     * Returns the maximum height that the wallet has scanned.
     *
     * If the wallet is fully synced, this will be equivalent to `getFullyScannedHeight`;
     * otherwise the maximal scanned height is likely to be greater than the fully scanned
     * height due to the fact that out-of-order scanning can leave gaps.
     *
     * @return The maximum height that the wallet has scanned, or Null if no blocks have been scanned.
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun getMaxScannedHeight(): Long?

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun getWalletSummary(): JniWalletSummary?

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun suggestScanRanges(): List<JniScanRange>

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun scanBlocks(
        fromHeight: Long,
        fromState: ByteArray,
        limit: Long
    ): JniScanSummary

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun transactionDataRequests(): List<JniTransactionDataRequest>

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
    suspend fun writeBlockMetadata(blockMetadata: List<JniBlockMeta>)

    /**
     * @return The latest height in the CompactBlock cache metadata DB, or Null if no blocks have been cached.
     */
    suspend fun getLatestCacheHeight(): Long?

    suspend fun findBlockMetadata(height: Long): JniBlockMeta?

    suspend fun rewindBlockMetadataToHeight(height: Long)

    suspend fun getTotalTransparentBalance(address: String): Long

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Suppress("LongParameterList")
    @Throws(RuntimeException::class)
    suspend fun putUtxo(
        txId: ByteArray,
        index: Int,
        script: ByteArray,
        value: Long,
        height: Long
    )

    /**
     * @throws RuntimeException as a common indicator of the operation failure
     */
    @Throws(RuntimeException::class)
    suspend fun setTransactionStatus(
        txId: ByteArray,
        status: Long,
    )

    //
    // Helper Functions
    //

    suspend fun <T> withWallet(
        block: suspend (dataDbFile: File, networkId: Int) -> T
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        block(dataDbFile, networkId)
    }
}
