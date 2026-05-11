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

/**
 * Synchronous native proof progress callback.
 *
 * This callback runs while the owning voting DB handle is locked by the in-flight
 * proof operation. Implementations must not call back into voting DB methods.
 * Native code treats callback failures as best-effort progress reporting and
 * continues proof generation after logging the failure.
 */
@Keep
fun interface VotingProofProgressCallback {
    @Keep
    fun onProgress(progress: Double)
}

private val proofProgressCallbackDepth = ThreadLocal<Int>()

private fun isInProofProgressCallback() = (proofProgressCallbackDepth.get() ?: 0) > 0

private fun <T> runInProofProgressCallback(block: () -> T): T {
    val depth = proofProgressCallbackDepth.get() ?: 0
    proofProgressCallbackDepth.set(depth + 1)
    try {
        return block()
    } finally {
        if (depth == 0) {
            proofProgressCallbackDepth.remove()
        } else {
            proofProgressCallbackDepth.set(depth)
        }
    }
}

private fun VotingProofProgressCallback.withVotingDbReentryGuard() =
    VotingProofProgressCallback { progress ->
        runInProofProgressCallback {
            onProgress(progress)
        }
    }

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
            withHandle { handle -> getBundleCountNative(handle, roundId) }

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

        @Throws(RuntimeException::class)
        suspend fun storeWitnesses(
            roundId: String,
            bundleIndex: Int,
            witnessesJson: String
        ) = withHandle { handle ->
            storeWitnessesNative(handle, roundId, bundleIndex, witnessesJson)
        }

        @Throws(RuntimeException::class)
        suspend fun precomputeDelegationPirJson(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notesJson: String
        ): String =
            withHandle { handle ->
                precomputeDelegationPirJsonNative(
                    handle,
                    roundId,
                    bundleIndex,
                    pirServerUrl,
                    networkId,
                    notesJson
                ) ?: error("precomputeDelegationPir returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun buildAndProveDelegationJson(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notesJson: String,
            walletSeed: ByteArray,
            accountIndex: Int,
            addressIndex: Int,
            proofProgress: VotingProofProgressCallback?
        ): String =
            withHandle { handle ->
                buildAndProveDelegationJsonNative(
                    handle,
                    roundId,
                    bundleIndex,
                    pirServerUrl,
                    networkId,
                    notesJson,
                    walletSeed,
                    accountIndex,
                    addressIndex,
                    proofProgress?.withVotingDbReentryGuard()
                ) ?: error("buildAndProveDelegation returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun getDelegationSubmissionJson(
            roundId: String,
            bundleIndex: Int,
            senderSeed: ByteArray,
            networkId: Int,
            accountIndex: Int
        ): String =
            withHandle { handle ->
                getDelegationSubmissionJsonNative(
                    handle,
                    roundId,
                    bundleIndex,
                    senderSeed,
                    networkId,
                    accountIndex
                ) ?: error("getDelegationSubmission returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun getDelegationSubmissionWithKeystoneSigJson(
            roundId: String,
            bundleIndex: Int,
            keystoneSig: ByteArray,
            keystoneSighash: ByteArray
        ): String =
            withHandle { handle ->
                getDelegationSubmissionWithKeystoneSigJsonNative(
                    handle,
                    roundId,
                    bundleIndex,
                    keystoneSig,
                    keystoneSighash
                ) ?: error("getDelegationSubmissionWithKeystoneSig returned null")
            }

        private suspend fun <T> withHandle(block: (Long) -> T): T {
            check(!isInProofProgressCallback()) {
                "Voting DB methods must not be called from a proof progress callback"
            }

            return accessMutex.withLock {
                val handle =
                    checkNotNull(dbHandle) {
                        "Voting DB handle is closed"
                    }
                withContext(SdkDispatchers.DATABASE_IO) {
                    block(handle)
                }
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

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeWitnessesNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            witnessesJson: String
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun precomputeDelegationPirJsonNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notesJson: String
        ): String?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildAndProveDelegationJsonNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notesJson: String,
            walletSeed: ByteArray,
            accountIndex: Int,
            addressIndex: Int,
            proofProgress: VotingProofProgressCallback?
        ): String?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getDelegationSubmissionJsonNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            senderSeed: ByteArray,
            networkId: Int,
            accountIndex: Int
        ): String?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getDelegationSubmissionWithKeystoneSigJsonNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            keystoneSig: ByteArray,
            keystoneSighash: ByteArray
        ): String?
    }
}
