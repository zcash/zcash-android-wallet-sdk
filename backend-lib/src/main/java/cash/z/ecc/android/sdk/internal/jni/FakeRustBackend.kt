package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.JniAccount
import cash.z.ecc.android.sdk.internal.model.JniAccountUsk
import cash.z.ecc.android.sdk.internal.model.JniBlockMeta
import cash.z.ecc.android.sdk.internal.model.JniRewindResult
import cash.z.ecc.android.sdk.internal.model.JniScanRange
import cash.z.ecc.android.sdk.internal.model.JniSingleUseTransparentAddress
import cash.z.ecc.android.sdk.internal.model.JniSubtreeRoot
import cash.z.ecc.android.sdk.internal.model.JniTransactionDataRequest
import cash.z.ecc.android.sdk.internal.model.JniWalletSummary
import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import java.io.File

@Suppress("TooManyFunctions")
class FakeRustBackend(
    override val networkId: Int,
    val metadata: MutableList<JniBlockMeta>
) : Backend {
    override val dataDbFile: File
        get() = error("Intentionally not implemented yet.")

    override suspend fun writeBlockMetadata(blockMetadata: List<JniBlockMeta>) {
        metadata.addAll(blockMetadata)
    }

    override suspend fun rewindToHeight(height: Long): JniRewindResult {
        metadata.removeAll { it.height > height }
        return JniRewindResult.Success(height)
    }

    override suspend fun putSubtreeRoots(
        saplingStartIndex: Long,
        saplingRoots: List<JniSubtreeRoot>,
        orchardStartIndex: Long,
        orchardRoots: List<JniSubtreeRoot>,
    ) {
        error("Intentionally not implemented yet.")
    }

    override suspend fun updateChainTip(height: Long) {
        error("Intentionally not implemented yet.")
    }

    override suspend fun getFullyScannedHeight(): Long? {
        error("Intentionally not implemented yet.")
    }

    override suspend fun getMaxScannedHeight(): Long? {
        error("Intentionally not implemented yet.")
    }

    override suspend fun getWalletSummary(): JniWalletSummary {
        error("Intentionally not implemented yet.")
    }

    override suspend fun suggestScanRanges(): List<JniScanRange> {
        error("Intentionally not implemented yet.")
    }

    override suspend fun getLatestCacheHeight(): Long = metadata.maxOf { it.height }

    override suspend fun getTotalTransparentBalance(address: String): Long {
        error("Intentionally not implemented yet.")
    }

    override suspend fun putUtxo(
        txId: ByteArray,
        index: Int,
        script: ByteArray,
        value: Long,
        height: Long
    ) {
        error("Intentionally not implemented yet.")
    }

    override suspend fun setTransactionStatus(
        txId: ByteArray,
        status: Long
    ) {
        error("Intentionally not implemented yet.")
    }

    override suspend fun findBlockMetadata(height: Long): JniBlockMeta? = metadata.findLast { it.height == height }

    override suspend fun rewindBlockMetadataToHeight(height: Long) {
        metadata.removeAll { it.height > height }
    }

    override suspend fun initBlockMetaDb(): Int {
        error("Intentionally not implemented yet.")
    }

    override suspend fun proposeTransferFromUri(
        accountUuid: ByteArray,
        uri: String
    ): ProposalUnsafe {
        error("Intentionally not implemented yet.")
    }

    override suspend fun proposeTransfer(
        accountUuid: ByteArray,
        to: String,
        value: Long,
        memo: ByteArray?
    ): ProposalUnsafe {
        error("Intentionally not implemented yet.")
    }

    override suspend fun proposeShielding(
        accountUuid: ByteArray,
        shieldingThreshold: Long,
        memo: ByteArray?,
        transparentReceiver: String?
    ): ProposalUnsafe? {
        error("Intentionally not implemented yet.")
    }

    override suspend fun createProposedTransactions(
        proposal: ProposalUnsafe,
        unifiedSpendingKey: ByteArray
    ): List<ByteArray> {
        error("Intentionally not implemented yet.")
    }

    override suspend fun createPcztFromProposal(
        accountUuid: ByteArray,
        proposal: ProposalUnsafe
    ): ByteArray {
        error("Intentionally not implemented yet.")
    }

    override suspend fun redactPcztForSigner(pczt: ByteArray): ByteArray {
        error("Intentionally not implemented yet.")
    }

    override suspend fun pcztRequiresSaplingProofs(pczt: ByteArray): Boolean {
        error("Intentionally not implemented yet.")
    }

    override suspend fun addProofsToPczt(pczt: ByteArray): ByteArray {
        error("Intentionally not implemented yet.")
    }

    override suspend fun extractAndStoreTxFromPczt(
        pcztWithProofs: ByteArray,
        pcztWithSignatures: ByteArray
    ): ByteArray {
        error("Intentionally not implemented yet.")
    }

    override suspend fun decryptAndStoreTransaction(tx: ByteArray, minedHeight: Long?): ByteArray {
        error("Intentionally not implemented yet.")
    }

    override suspend fun initDataDb(seed: ByteArray?): Int {
        error("Intentionally not implemented yet.")
    }

    override suspend fun getAccounts(): List<JniAccount> {
        error("Intentionally not implemented yet.")
    }

    override suspend fun getAccountForUfvk(ufvk: String): JniAccount? {
        error("Intentionally not implemented yet.")
    }

    override suspend fun createAccount(
        accountName: String,
        keySource: String?,
        seed: ByteArray,
        treeState: ByteArray,
        recoverUntil: Long?,
    ): JniAccountUsk {
        error("Intentionally not implemented yet.")
    }

    override suspend fun importAccountUfvk(
        accountName: String,
        keySource: String?,
        ufvk: String,
        treeState: ByteArray,
        recoverUntil: Long?,
        purpose: Int,
        seedFingerprint: ByteArray?,
        zip32AccountIndex: Long?,
    ): JniAccount {
        error("Intentionally not implemented yet.")
    }

    override suspend fun isSeedRelevantToAnyDerivedAccounts(seed: ByteArray): Boolean =
        error("Intentionally not implemented in mocked FakeRustBackend implementation.")

    override suspend fun deleteAccount(accountUuid: ByteArray): Boolean =
        error("Intentionally not implemented in mocked FakeRustBackend implementation.")

    override fun isValidSaplingAddr(addr: String): Boolean =
        error("Intentionally not implemented in mocked FakeRustBackend implementation.")

    override fun isValidTransparentAddr(addr: String): Boolean =
        error("Intentionally not implemented in mocked FakeRustBackend implementation.")

    override fun isValidUnifiedAddr(addr: String): Boolean =
        error("Intentionally not implemented in mocked FakeRustBackend implementation.")

    override fun isValidTexAddr(addr: String): Boolean {
        error("Intentionally not implemented in mocked FakeRustBackend implementation.")
    }

    override suspend fun getCurrentAddress(accountUuid: ByteArray): String {
        error("Intentionally not implemented yet.")
    }

    override suspend fun getSingleUseTransparentAddress(accountUuid: ByteArray): JniSingleUseTransparentAddress {
        error("Intentionally not implemented in mocked FakeRustBackend implementation.")
    }

    override suspend fun getNextAvailableAddress(
        accountUuid: ByteArray,
        receiverFlags: Int
    ): String {
        error("Intentionally not implemented yet.")
    }

    override fun getTransparentReceiver(ua: String): String? {
        error("Intentionally not implemented yet.")
    }

    override fun getSaplingReceiver(ua: String): String? {
        error("Intentionally not implemented yet.")
    }

    override suspend fun listTransparentReceivers(accountUuid: ByteArray): List<String> {
        error("Intentionally not implemented yet.")
    }

    override fun getBranchIdForHeight(height: Long): Long {
        error("Intentionally not implemented yet.")
    }

    override suspend fun getMemoAsUtf8(
        txId: ByteArray,
        protocol: Int,
        outputIndex: Int
    ): String? {
        error("Intentionally not implemented yet.")
    }

    override suspend fun scanBlocks(
        fromHeight: Long,
        fromState: ByteArray,
        limit: Long
    ) = error("Intentionally not implemented in mocked FakeRustBackend implementation.")

    override suspend fun transactionDataRequests(): List<JniTransactionDataRequest> {
        error("Intentionally not implemented yet.")
    }

    override suspend fun fixWitnesses() {
        error("Intentionally not implemented yet.")
    }
}
