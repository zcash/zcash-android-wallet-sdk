package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.ext.deleteRecursivelySuspend
import cash.z.ecc.android.sdk.internal.ext.deleteSuspend
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
import cash.z.ecc.android.sdk.internal.model.RustLogging
import cash.z.ecc.android.sdk.internal.model.isNotLoggingInProduction
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Serves as the JNI boundary between the Kotlin and Rust layers. Functions in this class should
 * not be called directly by code outside of the SDK. Instead, one of the higher-level components
 * should be used such as WalletCoordinator.kt or CompactBlockProcessor.kt.
 */
@Suppress("TooManyFunctions")
class RustBackend private constructor(
    override val networkId: Int,
    override val dataDbFile: File,
    private val fsBlockDbRoot: File,
    private val saplingSpendFile: File,
    private val saplingOutputFile: File,
) : Backend {
    /**
     * This function deletes the data database file and the cache directory (compact blocks files) if set by input
     * parameters.
     *
     * @param clearCache to request the cache directory and its content deletion
     * @param clearDataDb to request the data database file deletion
     *
     * @return false in case of any required and failed deletion, true otherwise.
     */
    suspend fun clear(
        clearCache: Boolean = true,
        clearDataDb: Boolean = true
    ): Boolean {
        var cacheClearResult = true
        var dataClearResult = true
        if (clearCache) {
            fsBlockDbRoot.deleteRecursivelySuspend().also { result ->
                cacheClearResult = result
            }
        }
        if (clearDataDb) {
            dataDbFile.deleteSuspend().also { result ->
                dataClearResult = result
            }
        }
        return cacheClearResult && dataClearResult
    }

    //
    // Wrapper Functions
    //

    override suspend fun initBlockMetaDb() =
        withContext(SdkDispatchers.DATABASE_IO) {
            initBlockMetaDb(
                fsBlockDbRoot.absolutePath,
            )
        }

    override suspend fun initDataDb(seed: ByteArray?) =
        withContext(SdkDispatchers.DATABASE_IO) {
            initDataDb(
                dataDbFile.absolutePath,
                seed,
                networkId = networkId
            )
        }

    override suspend fun getAccounts(): List<JniAccount> =
        withContext(SdkDispatchers.DATABASE_IO) {
            getAccounts(
                dbDataPath = dataDbFile.absolutePath,
                networkId = networkId
            ).asList()
        }

    override suspend fun getAccountForUfvk(ufvk: String): JniAccount? =
        withContext(SdkDispatchers.DATABASE_IO) {
            getAccountForUfvk(
                dbDataPath = dataDbFile.absolutePath,
                networkId = networkId,
                ufvk = ufvk,
            )
        }

    override suspend fun createAccount(
        accountName: String,
        keySource: String?,
        seed: ByteArray,
        treeState: ByteArray,
        recoverUntil: Long?,
    ): JniAccountUsk =
        withContext(SdkDispatchers.DATABASE_IO) {
            createAccount(
                dbDataPath = dataDbFile.absolutePath,
                networkId = networkId,
                accountName = accountName,
                keySource = keySource,
                seed = seed,
                treeState = treeState,
                recoverUntil = recoverUntil ?: -1,
            )
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
    ): JniAccount =
        withContext(SdkDispatchers.DATABASE_IO) {
            importAccountUfvk(
                dbDataPath = dataDbFile.absolutePath,
                networkId = networkId,
                accountName = accountName,
                keySource = keySource,
                ufvk = ufvk,
                treeState = treeState,
                recoverUntil = recoverUntil ?: -1,
                purpose = purpose,
                seedFingerprint = seedFingerprint,
                zip32AccountIndex = zip32AccountIndex ?: -1,
            )
        }

    override suspend fun isSeedRelevantToAnyDerivedAccounts(seed: ByteArray): Boolean =
        withContext(SdkDispatchers.DATABASE_IO) {
            isSeedRelevantToAnyDerivedAccounts(
                dataDbFile.absolutePath,
                seed,
                networkId = networkId
            )
        }

    override suspend fun getCurrentAddress(accountUuid: ByteArray) =
        withContext(SdkDispatchers.DATABASE_IO) {
            getCurrentAddress(
                dataDbFile.absolutePath,
                accountUuid,
                networkId = networkId
            )
        }

    override suspend fun getSingleUseTransparentAddress(accountUuid: ByteArray) =
        withContext(SdkDispatchers.DATABASE_IO) {
            getSingleUseTaddr(
                dataDbFile.absolutePath,
                networkId,
                accountUuid,
            )
        }

    override suspend fun getNextAvailableAddress(
        accountUuid: ByteArray,
        receiverFlags: Int
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        getNextAvailableAddress(
            dataDbFile.absolutePath,
            accountUuid,
            receiverFlags,
            networkId = networkId
        )
    }

    override fun getTransparentReceiver(ua: String) = getTransparentReceiverForUnifiedAddress(ua)

    override fun getSaplingReceiver(ua: String) = getSaplingReceiverForUnifiedAddress(ua)

    override suspend fun listTransparentReceivers(accountUuid: ByteArray): List<String> =
        withContext(SdkDispatchers.DATABASE_IO) {
            listTransparentReceivers(
                dbDataPath = dataDbFile.absolutePath,
                accountUuid = accountUuid,
                networkId = networkId
            ).asList()
        }

    override suspend fun getMemoAsUtf8(
        txId: ByteArray,
        protocol: Int,
        outputIndex: Int
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        getMemoAsUtf8(
            dataDbFile.absolutePath,
            txId,
            protocol,
            outputIndex,
            networkId = networkId
        )
    }

    override suspend fun writeBlockMetadata(blockMetadata: List<JniBlockMeta>) =
        withContext(SdkDispatchers.DATABASE_IO) {
            writeBlockMetadata(
                fsBlockDbRoot.absolutePath,
                blockMetadata.toTypedArray()
            )
        }

    override suspend fun getLatestCacheHeight() =
        withContext(SdkDispatchers.DATABASE_IO) {
            val height = getLatestCacheHeight(fsBlockDbRoot.absolutePath)

            if (-1L == height) {
                null
            } else {
                height
            }
        }

    override suspend fun findBlockMetadata(height: Long) =
        withContext(SdkDispatchers.DATABASE_IO) {
            findBlockMetadata(
                fsBlockDbRoot.absolutePath,
                height
            )
        }

    override suspend fun rewindBlockMetadataToHeight(height: Long) =
        withContext(SdkDispatchers.DATABASE_IO) {
            rewindBlockMetadataToHeight(
                fsBlockDbRoot.absolutePath,
                height
            )
        }

    override suspend fun getTotalTransparentBalance(address: String): Long =
        withContext(SdkDispatchers.DATABASE_IO) {
            getTotalTransparentBalance(
                dataDbFile.absolutePath,
                address,
                networkId = networkId
            )
        }

    /**
     * Rewinds the data database to at most the given height.
     */
    override suspend fun rewindToHeight(height: Long): JniRewindResult =
        withContext(SdkDispatchers.DATABASE_IO) {
            rewindToHeight(
                dataDbFile.absolutePath,
                height,
                networkId = networkId
            )
        }

    override suspend fun rewindToChainState(chainState: ByteArray) =
        withContext(SdkDispatchers.DATABASE_IO) {
            rewindToChainState(
                dataDbFile.absolutePath,
                chainState,
                networkId = networkId
            )
        }

    override suspend fun putSubtreeRoots(
        saplingStartIndex: Long,
        saplingRoots: List<JniSubtreeRoot>,
        orchardStartIndex: Long,
        orchardRoots: List<JniSubtreeRoot>,
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        putSubtreeRoots(
            dataDbFile.absolutePath,
            saplingStartIndex,
            saplingRoots.toTypedArray(),
            orchardStartIndex,
            orchardRoots.toTypedArray(),
            networkId = networkId
        )
    }

    override suspend fun updateChainTip(height: Long) =
        withContext(SdkDispatchers.DATABASE_IO) {
            updateChainTip(
                dataDbFile.absolutePath,
                height,
                networkId = networkId
            )
        }

    override suspend fun getFullyScannedHeight() =
        withContext(SdkDispatchers.DATABASE_IO) {
            val height =
                getFullyScannedHeight(
                    dataDbFile.absolutePath,
                    networkId = networkId
                )

            if (-1L == height) {
                null
            } else {
                height
            }
        }

    override suspend fun getMaxScannedHeight() =
        withContext(SdkDispatchers.DATABASE_IO) {
            val height =
                getMaxScannedHeight(
                    dataDbFile.absolutePath,
                    networkId = networkId
                )

            if (-1L == height) {
                null
            } else {
                height
            }
        }

    override suspend fun getWalletSummary(): JniWalletSummary? =
        withContext(SdkDispatchers.DATABASE_IO) {
            getWalletSummary(
                dataDbFile.absolutePath,
                networkId = networkId
            )
        }

    override suspend fun suggestScanRanges(): List<JniScanRange> =
        withContext(SdkDispatchers.DATABASE_IO) {
            suggestScanRanges(
                dataDbFile.absolutePath,
                networkId = networkId
            ).asList()
        }

    override suspend fun scanBlocks(
        fromHeight: Long,
        fromState: ByteArray,
        limit: Long
    ): JniScanSummary =
        withContext(SdkDispatchers.DATABASE_IO) {
            scanBlocks(
                fsBlockDbRoot.absolutePath,
                dataDbFile.absolutePath,
                fromHeight,
                fromState,
                limit,
                networkId = networkId
            )
        }

    override suspend fun transactionDataRequests(): List<JniTransactionDataRequest> =
        withContext(SdkDispatchers.DATABASE_IO) {
            transactionDataRequests(
                dbDataPath = dataDbFile.absolutePath,
                networkId = networkId
            ).asList()
        }

    override suspend fun fixWitnesses() =
        withContext(SdkDispatchers.DATABASE_IO) {
            fixWitnesses(
                dbDataPath = dataDbFile.absolutePath,
                networkId = networkId
            )
        }

    override suspend fun decryptAndStoreTransaction(
        tx: ByteArray,
        minedHeight: Long?
    ): ByteArray =
        withContext(SdkDispatchers.DATABASE_IO) {
            decryptAndStoreTransaction(
                dataDbFile.absolutePath,
                tx,
                minedHeight = minedHeight ?: -1,
                networkId = networkId
            )
        }

    override suspend fun proposeTransferFromUri(
        accountUuid: ByteArray,
        uri: String
    ): ProposalUnsafe =
        withContext(SdkDispatchers.DATABASE_IO) {
            ProposalUnsafe.parse(
                proposeTransferFromUri(
                    dataDbFile.absolutePath,
                    accountUuid,
                    uri,
                    networkId = networkId,
                )
            )
        }

    override suspend fun proposeTransfer(
        accountUuid: ByteArray,
        to: String,
        value: Long,
        memo: ByteArray?
    ): ProposalUnsafe =
        withContext(SdkDispatchers.DATABASE_IO) {
            ProposalUnsafe.parse(
                proposeTransfer(
                    dataDbFile.absolutePath,
                    accountUuid,
                    to,
                    value,
                    memo,
                    networkId = networkId,
                )
            )
        }

    override suspend fun proposeShielding(
        accountUuid: ByteArray,
        shieldingThreshold: Long,
        memo: ByteArray?,
        transparentReceiver: String?
    ): ProposalUnsafe? =
        withContext(SdkDispatchers.DATABASE_IO) {
            proposeShielding(
                dataDbFile.absolutePath,
                accountUuid,
                shieldingThreshold,
                memo,
                transparentReceiver,
                networkId = networkId,
            )?.let {
                ProposalUnsafe.parse(
                    it
                )
            }
        }

    override suspend fun createProposedTransactions(
        proposal: ProposalUnsafe,
        unifiedSpendingKey: ByteArray
    ): List<ByteArray> =
        withContext(SdkDispatchers.DATABASE_IO) {
            createProposedTransactions(
                dataDbFile.absolutePath,
                proposal.toByteArray(),
                unifiedSpendingKey,
                spendParamsPath = saplingSpendFile.absolutePath,
                outputParamsPath = saplingOutputFile.absolutePath,
                networkId = networkId
            ).asList()
        }

    override suspend fun createPcztFromProposal(
        accountUuid: ByteArray,
        proposal: ProposalUnsafe
    ): ByteArray =
        withContext(SdkDispatchers.DATABASE_IO) {
            createPcztFromProposal(
                dbDataPath = dataDbFile.absolutePath,
                accountUuid = accountUuid,
                proposal = proposal.toByteArray(),
                networkId = networkId
            )
        }

    override suspend fun redactPcztForSigner(pczt: ByteArray): ByteArray = redactPcztForSignerRole(pczt = pczt)

    override suspend fun pcztRequiresSaplingProofs(pczt: ByteArray): Boolean = requiresSaplingProofs(pczt = pczt)

    override suspend fun addProofsToPczt(pczt: ByteArray): ByteArray =
        addProofsToPczt(
            pczt = pczt,
            spendParamsPath = saplingSpendFile.absolutePath,
            outputParamsPath = saplingOutputFile.absolutePath
        )

    override suspend fun extractAndStoreTxFromPczt(
        pcztWithProofs: ByteArray,
        pcztWithSignatures: ByteArray
    ): ByteArray =
        withContext(SdkDispatchers.DATABASE_IO) {
            extractAndStoreTxFromPczt(
                dbDataPath = dataDbFile.absolutePath,
                pcztWithProofs = pcztWithProofs,
                pcztWithSignatures = pcztWithSignatures,
                spendParamsPath = saplingSpendFile.absolutePath,
                outputParamsPath = saplingOutputFile.absolutePath,
                networkId = networkId
            )
        }

    override suspend fun putUtxo(
        txId: ByteArray,
        index: Int,
        script: ByteArray,
        value: Long,
        height: Long
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        putUtxo(
            dataDbFile.absolutePath,
            txId,
            index,
            script,
            value,
            height,
            networkId = networkId
        )
    }

    override suspend fun setTransactionStatus(
        txId: ByteArray,
        status: Long
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        setTransactionStatus(
            dataDbFile.absolutePath,
            txId,
            status,
            networkId = networkId
        )
    }

    override fun isValidSaplingAddr(addr: String) = isValidSaplingAddress(addr, networkId = networkId)

    override fun isValidTransparentAddr(addr: String) = isValidTransparentAddress(addr, networkId = networkId)

    override fun isValidUnifiedAddr(addr: String) = isValidUnifiedAddress(addr, networkId = networkId)

    override fun isValidTexAddr(addr: String) = isValidTexAddress(addr, networkId = networkId)

    override fun getBranchIdForHeight(height: Long): Long = branchIdForHeight(height, networkId = networkId)

    /**
     * Exposes all of the librustzcash functions along with helpers for loading the static library.
     */
    companion object {
        internal val rustLibraryLoader = NativeLibraryLoader("zcashwalletsdk")

        private val rustLogging: RustLogging = RustLogging.Off

        suspend fun loadLibrary() {
            rustLibraryLoader.load {
                require(rustLogging.isNotLoggingInProduction()) {
                    "Rust layer logging must be turned off in production build"
                }

                initOnLoad(rustLogging.identifier)
            }
        }

        /**
         * Loads the library and initializes path variables. Although it is best to only call this
         * function once, it is idempotent.
         */
        suspend fun new(
            fsBlockDbRoot: File,
            dataDbFile: File,
            saplingSpendFile: File,
            saplingOutputFile: File,
            zcashNetworkId: Int,
        ): RustBackend {
            loadLibrary()

            return RustBackend(
                zcashNetworkId,
                dataDbFile = dataDbFile,
                fsBlockDbRoot = fsBlockDbRoot,
                saplingSpendFile = saplingSpendFile,
                saplingOutputFile = saplingOutputFile
            )
        }

        //
        // External Functions
        //

        @JvmStatic
        private external fun initOnLoad(logLevel: String)

        @JvmStatic
        private external fun initBlockMetaDb(fsBlockDbRoot: String): Int

        @JvmStatic
        private external fun initDataDb(
            dbDataPath: String,
            seed: ByteArray?,
            networkId: Int
        ): Int

        @JvmStatic
        private external fun getAccounts(
            dbDataPath: String,
            networkId: Int
        ): Array<JniAccount>

        @JvmStatic
        private external fun getAccountForUfvk(
            dbDataPath: String,
            networkId: Int,
            ufvk: String,
        ): JniAccount?

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun createAccount(
            dbDataPath: String,
            networkId: Int,
            accountName: String,
            keySource: String?,
            seed: ByteArray,
            treeState: ByteArray,
            recoverUntil: Long,
        ): JniAccountUsk

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun importAccountUfvk(
            dbDataPath: String,
            networkId: Int,
            accountName: String,
            keySource: String?,
            ufvk: String,
            treeState: ByteArray,
            recoverUntil: Long,
            purpose: Int,
            seedFingerprint: ByteArray?,
            zip32AccountIndex: Long,
        ): JniAccount

        @JvmStatic
        private external fun isSeedRelevantToAnyDerivedAccounts(
            dbDataPath: String,
            seed: ByteArray,
            networkId: Int
        ): Boolean

        @JvmStatic
        private external fun getCurrentAddress(
            dbDataPath: String,
            accountUuid: ByteArray,
            networkId: Int
        ): String

        @JvmStatic
        private external fun getSingleUseTaddr(
            dbDataPath: String,
            networkId: Int,
            accountUuid: ByteArray,
        ): JniSingleUseTransparentAddress

        @JvmStatic
        private external fun getNextAvailableAddress(
            dbDataPath: String,
            accountUuid: ByteArray,
            receiverFlags: Int,
            networkId: Int
        ): String

        @JvmStatic
        private external fun getTransparentReceiverForUnifiedAddress(ua: String): String?

        @JvmStatic
        private external fun getSaplingReceiverForUnifiedAddress(ua: String): String?

        @JvmStatic
        private external fun listTransparentReceivers(
            dbDataPath: String,
            accountUuid: ByteArray,
            networkId: Int
        ): Array<String>

        fun validateUnifiedSpendingKey(bytes: ByteArray) = isValidSpendingKey(bytes)

        @JvmStatic
        private external fun isValidSpendingKey(bytes: ByteArray): Boolean

        @JvmStatic
        private external fun isValidSaplingAddress(
            addr: String,
            networkId: Int
        ): Boolean

        @JvmStatic
        private external fun isValidTransparentAddress(
            addr: String,
            networkId: Int
        ): Boolean

        @JvmStatic
        private external fun isValidUnifiedAddress(
            addr: String,
            networkId: Int
        ): Boolean

        @JvmStatic
        private external fun isValidTexAddress(
            addr: String,
            networkId: Int
        ): Boolean

        @JvmStatic
        private external fun getTotalTransparentBalance(
            pathDataDb: String,
            taddr: String,
            networkId: Int
        ): Long

        @JvmStatic
        private external fun getMemoAsUtf8(
            dbDataPath: String,
            txId: ByteArray,
            poolType: Int,
            outputIndex: Int,
            networkId: Int
        ): String?

        @JvmStatic
        private external fun writeBlockMetadata(
            dbCachePath: String,
            blockMeta: Array<JniBlockMeta>
        )

        @JvmStatic
        private external fun getLatestCacheHeight(dbCachePath: String): Long

        @JvmStatic
        private external fun findBlockMetadata(
            dbCachePath: String,
            height: Long
        ): JniBlockMeta?

        @JvmStatic
        private external fun rewindBlockMetadataToHeight(
            dbCachePath: String,
            height: Long
        )

        @JvmStatic
        private external fun rewindToHeight(
            dbDataPath: String,
            height: Long,
            networkId: Int
        ): JniRewindResult

        @JvmStatic
        private external fun rewindToChainState(
            dbDataPath: String,
            chainState: ByteArray,
            networkId: Int
        )

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun putSubtreeRoots(
            dbDataPath: String,
            saplingStartIndex: Long,
            saplingRoots: Array<JniSubtreeRoot>,
            orchardStartIndex: Long,
            orchardRoots: Array<JniSubtreeRoot>,
            networkId: Int
        )

        @JvmStatic
        private external fun updateChainTip(
            dbDataPath: String,
            height: Long,
            networkId: Int
        )

        @JvmStatic
        private external fun getFullyScannedHeight(
            dbDataPath: String,
            networkId: Int
        ): Long

        @JvmStatic
        private external fun getMaxScannedHeight(
            dbDataPath: String,
            networkId: Int
        ): Long

        @JvmStatic
        private external fun getWalletSummary(
            dbDataPath: String,
            networkId: Int
        ): JniWalletSummary?

        @JvmStatic
        private external fun suggestScanRanges(
            dbDataPath: String,
            networkId: Int
        ): Array<JniScanRange>

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun scanBlocks(
            dbCachePath: String,
            dbDataPath: String,
            fromHeight: Long,
            fromState: ByteArray,
            limit: Long,
            networkId: Int
        ): JniScanSummary

        @JvmStatic
        private external fun transactionDataRequests(
            dbDataPath: String,
            networkId: Int
        ): Array<JniTransactionDataRequest>

        @JvmStatic
        private external fun fixWitnesses(
            dbDataPath: String,
            networkId: Int
        )

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun putUtxo(
            dbDataPath: String,
            txId: ByteArray,
            index: Int,
            script: ByteArray,
            value: Long,
            height: Long,
            networkId: Int
        )

        @JvmStatic
        private external fun decryptAndStoreTransaction(
            dbDataPath: String,
            tx: ByteArray,
            minedHeight: Long,
            networkId: Int
        ): ByteArray

        @JvmStatic
        private external fun setTransactionStatus(
            dbDataPath: String,
            txId: ByteArray,
            status: Long,
            networkId: Int
        )

        @JvmStatic
        private external fun proposeTransferFromUri(
            dbDataPath: String,
            accountUuid: ByteArray,
            uri: String,
            networkId: Int,
        ): ByteArray

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun proposeTransfer(
            dbDataPath: String,
            accountUuid: ByteArray,
            to: String,
            value: Long,
            memo: ByteArray?,
            networkId: Int,
        ): ByteArray

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun proposeShielding(
            dbDataPath: String,
            accountUuid: ByteArray,
            shieldingThreshold: Long,
            memo: ByteArray?,
            transparentReceiver: String?,
            networkId: Int,
        ): ByteArray?

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun createProposedTransactions(
            dbDataPath: String,
            proposal: ByteArray,
            usk: ByteArray,
            spendParamsPath: String,
            outputParamsPath: String,
            networkId: Int
        ): Array<ByteArray>

        @JvmStatic
        private external fun createPcztFromProposal(
            dbDataPath: String,
            accountUuid: ByteArray,
            proposal: ByteArray,
            networkId: Int,
        ): ByteArray

        @JvmStatic
        private external fun redactPcztForSignerRole(pczt: ByteArray): ByteArray

        @JvmStatic
        private external fun requiresSaplingProofs(pczt: ByteArray): Boolean

        @JvmStatic
        private external fun addProofsToPczt(
            pczt: ByteArray,
            spendParamsPath: String,
            outputParamsPath: String,
        ): ByteArray

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun extractAndStoreTxFromPczt(
            dbDataPath: String,
            pcztWithProofs: ByteArray,
            pcztWithSignatures: ByteArray,
            spendParamsPath: String,
            outputParamsPath: String,
            networkId: Int,
        ): ByteArray

        @JvmStatic
        private external fun branchIdForHeight(
            height: Long,
            networkId: Int
        ): Long
    }
}
