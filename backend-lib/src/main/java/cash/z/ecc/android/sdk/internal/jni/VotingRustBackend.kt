package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Keep
@Suppress("TooManyFunctions", "LongParameterList")
class VotingRustBackend private constructor() {
    @Throws(RuntimeException::class)
    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray =
        withContext(Dispatchers.IO) {
            computeShareNullifierNative(voteCommitment, shareIndex, blind)
        }

    @Throws(RuntimeException::class)
    suspend fun computeBundleSetup(notesJson: String): JniBundleSetupResult =
        withContext(Dispatchers.IO) {
            computeBundleSetupNative(notesJson)
                ?: error("computeBundleSetup returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun warmProvingCaches() =
        withContext(Dispatchers.IO) {
            warmProvingCachesNative()
        }

    @Throws(RuntimeException::class)
    suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            extractPcztSighashNative(pcztBytes)
                ?: error("extractPcztSighash returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            extractSpendAuthSigNative(signedPcztBytes, actionIndex)
                ?: error("extractSpendAuthSig returned null")
        }

    suspend fun openVotingDb(dbPath: String, walletId: String): VotingDb =
        withContext(SdkDispatchers.DATABASE_IO) {
            openVotingDbNative(dbPath, walletId).let { dbHandle ->
                check(dbHandle != 0L) {
                    "openVotingDb failed for dbPath=$dbPath"
                }
                VotingDb(dbHandle)
            }
        }

    @Suppress("TooManyFunctions", "LongParameterList")
    class VotingDb internal constructor(
        private var dbHandle: Long?
    ) {
        private val accessMutex = Mutex()

        suspend fun close() {
            accessMutex.withLock {
                dbHandle?.let { handle ->
                    withContext(SdkDispatchers.DATABASE_IO) {
                        closeVotingDbNative(handle)
                    }
                    dbHandle = null
                }
            }
        }

        @Throws(RuntimeException::class)
        suspend fun initRound(
            roundId: String,
            snapshotHeight: Long,
            eaPK: ByteArray,
            ncRoot: ByteArray,
            nullifierIMTRoot: ByteArray,
            sessionJson: String?
        ) = withHandle { handle ->
            initRoundNative(
                handle,
                roundId,
                snapshotHeight,
                eaPK,
                ncRoot,
                nullifierIMTRoot,
                sessionJson
            )
        }

        @Throws(RuntimeException::class)
        suspend fun getRoundState(roundId: String): JniRoundState? =
            withHandle { handle -> getRoundStateNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun listRounds(): Array<JniRoundSummary> =
            withHandle { handle -> listRoundsNative(handle) }

        @Throws(RuntimeException::class)
        suspend fun getBundleCount(roundId: String): Int =
            withHandle { handle ->
                getBundleCountNative(handle, roundId).also { count ->
                    check(count >= 0) {
                        "getBundleCount failed for roundId=$roundId"
                    }
                }
            }

        @Throws(RuntimeException::class)
        suspend fun getVotes(roundId: String): Array<JniVoteRecord> =
            withHandle { handle -> getVotesNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun clearRound(roundId: String) =
            withHandle { handle -> clearRoundNative(handle, roundId) }

        @Throws(RuntimeException::class)
        suspend fun deleteSkippedBundles(
            roundId: String,
            keepCount: Int
        ): Long =
            withHandle { handle -> deleteSkippedBundlesNative(handle, roundId, keepCount) }

        @Throws(RuntimeException::class)
        suspend fun setupBundles(
            roundId: String,
            notesJson: String
        ): JniBundleSetupResult =
            withHandle { handle ->
                setupBundlesNative(handle, roundId, notesJson)
                    ?: error("setupBundles returned null for roundId=$roundId")
            }

        @Throws(RuntimeException::class)
        suspend fun generateHotkey(
            roundId: String,
            seed: ByteArray
        ): JniVotingHotkey =
            withHandle { handle ->
                generateHotkeyNative(handle, roundId, seed)
                    ?: error("generateHotkey returned null for roundId=$roundId")
            }

        @Throws(RuntimeException::class)
        suspend fun buildGovernancePcztJson(
            roundId: String,
            bundleIndex: Int,
            ufvk: String,
            networkId: Int,
            accountIndex: Int,
            notesJson: String,
            walletSeed: ByteArray,
            seedFingerprint: ByteArray,
            roundName: String,
            addressIndex: Int
        ): String =
            withHandle { handle ->
                buildGovernancePcztJsonNative(
                    handle,
                    roundId,
                    bundleIndex,
                    ufvk,
                    networkId,
                    accountIndex,
                    notesJson,
                    walletSeed,
                    seedFingerprint,
                    roundName,
                    addressIndex
                ) ?: error("buildGovernancePczt returned null")
            }

        private suspend fun <T> withHandle(block: (Long) -> T): T =
            accessMutex.withLock {
                val handle =
                    checkNotNull(dbHandle) {
                        "Voting DB handle is closed"
                    }
                withContext(SdkDispatchers.DATABASE_IO) {
                    block(handle)
                }
            }
    }

    companion object {
        suspend fun new(): VotingRustBackend {
            RustBackend.loadLibrary()

            return VotingRustBackend()
        }

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun computeShareNullifierNative(
            voteCommitment: ByteArray,
            shareIndex: Int,
            blind: ByteArray
        ): ByteArray

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun warmProvingCachesNative()

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun openVotingDbNative(dbPath: String, walletId: String): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun closeVotingDbNative(dbHandle: Long)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun initRoundNative(
            dbHandle: Long,
            roundId: String,
            snapshotHeight: Long,
            eaPK: ByteArray,
            ncRoot: ByteArray,
            nullifierIMTRoot: ByteArray,
            sessionJson: String?
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getRoundStateNative(dbHandle: Long, roundId: String): JniRoundState?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun listRoundsNative(dbHandle: Long): Array<JniRoundSummary>

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getBundleCountNative(dbHandle: Long, roundId: String): Int

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getVotesNative(dbHandle: Long, roundId: String): Array<JniVoteRecord>

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun clearRoundNative(dbHandle: Long, roundId: String)

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deleteSkippedBundlesNative(
            dbHandle: Long,
            roundId: String,
            keepCount: Int
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun computeBundleSetupNative(notesJson: String): JniBundleSetupResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun setupBundlesNative(
            dbHandle: Long,
            roundId: String,
            notesJson: String
        ): JniBundleSetupResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun generateHotkeyNative(
            dbHandle: Long,
            roundId: String,
            seed: ByteArray
        ): JniVotingHotkey?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildGovernancePcztJsonNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            ufvk: String,
            networkId: Int,
            accountIndex: Int,
            notesJson: String,
            walletSeed: ByteArray,
            seedFingerprint: ByteArray,
            roundName: String,
            addressIndex: Int
        ): String?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractPcztSighashNative(pcztBytes: ByteArray): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractSpendAuthSigNative(
            signedPcztBytes: ByteArray,
            actionIndex: Int
        ): ByteArray?
    }
}
