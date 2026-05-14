package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniCommitmentBundleRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationProofResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationSubmissionResult
import cash.z.ecc.android.sdk.internal.model.voting.JniGovernancePczt
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniShareDelegationRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniSharePayload
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synchronous native proof progress callback.
 *
 * Native proof generation currently reports coarse progress from the proof call
 * thread before and after the spawned Halo2 proving worker. The JNI bridge
 * attaches whichever native thread invokes this callback, so callers must not
 * assume Android main-thread or coroutine-dispatcher affinity.
 *
 * This callback runs while the owning voting DB handle is locked by the in-flight
 * proof operation. Implementations must not call back into this VotingDb's methods.
 * Native code treats callback failures as best-effort progress reporting and
 * continues proof generation after logging the failure.
 */
@Keep
fun interface VotingProofProgressCallback {
    @Keep
    fun onProgress(progress: Double)
}

private const val PROOF_PROGRESS_REENTRY_ERROR =
    "This VotingDb's methods must not be called from its proof progress callback"

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
    suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult =
        withContext(Dispatchers.IO) {
            computeBundleSetupNative(notes.toTypedArray())
                ?: error("computeBundleSetup returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun warmProvingCaches() =
        withContext(Dispatchers.IO) {
            warmProvingCachesNative()
        }

    @Throws(RuntimeException::class)
    suspend fun decomposeWeight(weight: Long): LongArray =
        withContext(Dispatchers.IO) {
            decomposeWeightNative(weight)
                ?: error("decomposeWeight returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun buildSharePayloads(
        commitment: JniVoteCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): Array<JniSharePayload> =
        withContext(Dispatchers.IO) {
            buildSharePayloadsNative(
                commitment,
                voteDecision,
                numOptions,
                vcTreePosition,
                singleShareMode
            ) ?: error("buildSharePayloads returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun signCastVote(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int,
        commitment: JniVoteCommitmentResult
    ): ByteArray =
        withContext(Dispatchers.IO) {
            signCastVoteNative(
                hotkeySeed,
                networkId,
                accountIndex,
                commitment
            ) ?: error("signCastVote returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun extractOrchardFvkFromUfvk(
        ufvk: String,
        networkId: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            extractOrchardFvkFromUfvkNative(ufvk, networkId)
                ?: error("extractOrchardFvkFromUfvk returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): ByteArray =
        withContext(Dispatchers.IO) {
            deriveHotkeyRawAddressNative(hotkeySeed, networkId, accountIndex)
                ?: error("deriveHotkeyRawAddress returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            extractNcRootNative(treeStateBytes)
                ?: error("extractNcRoot returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun verifyWitness(witness: JniWitnessData): Boolean =
        withContext(Dispatchers.IO) {
            verifyWitnessNative(witness)
        }

    @Throws(RuntimeException::class)
    suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Array<JniNoteInfo> =
        withContext(SdkDispatchers.DATABASE_IO) {
            getWalletNotesNative(
                walletDbPath,
                snapshotHeight,
                networkId,
                accountUuidBytes
            ) ?: error("getWalletNotes returned null")
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

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun delegationProofResultFixtureForTesting(): JniDelegationProofResult =
        withContext(Dispatchers.IO) {
            delegationProofResultFixtureNative()
                ?: error("delegationProofResultFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun noteInfoArrayFixtureForTesting(): Array<JniNoteInfo> =
        withContext(Dispatchers.IO) {
            noteInfoArrayFixtureNative()
                ?: error("noteInfoArrayFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun witnessDataArrayFixtureForTesting(): Array<JniWitnessData> =
        withContext(Dispatchers.IO) {
            witnessDataArrayFixtureNative()
                ?: error("witnessDataArrayFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun treeStateFixtureForTesting(): ByteArray =
        withContext(Dispatchers.IO) {
            treeStateFixtureNative()
                ?: error("treeStateFixture returned null")
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun nonEmptyTreeStateFixtureForTesting(): ByteArray =
        withContext(Dispatchers.IO) {
            nonEmptyTreeStateFixtureNative()
                ?: error("nonEmptyTreeStateFixture returned null")
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
        private val proofProgressCallbackDepth = AtomicInteger(0)

        suspend fun close() {
            checkNotInProofProgressCallback()

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
            notes: List<JniNoteInfo>
        ): JniBundleSetupResult =
            withHandle { handle ->
                setupBundlesNative(handle, roundId, notes.toTypedArray())
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
        suspend fun buildGovernancePczt(
            roundId: String,
            bundleIndex: Int,
            fvkBytes: ByteArray,
            hotkeyRawAddress: ByteArray,
            networkId: Int,
            accountIndex: Int,
            notes: List<JniNoteInfo>,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt =
            withHandle { handle ->
                buildGovernancePcztNative(
                    handle,
                    roundId,
                    bundleIndex,
                    fvkBytes,
                    hotkeyRawAddress,
                    networkId,
                    accountIndex,
                    notes.toTypedArray(),
                    seedFingerprint,
                    roundName
                ) ?: error("buildGovernancePczt returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun buildGovernancePcztFromSeed(
            roundId: String,
            bundleIndex: Int,
            ufvk: String,
            networkId: Int,
            accountIndex: Int,
            notes: List<JniNoteInfo>,
            walletSeed: ByteArray,
            hotkeySeed: ByteArray,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt =
            withHandle { handle ->
                buildGovernancePcztFromSeedNative(
                    handle,
                    roundId,
                    bundleIndex,
                    ufvk,
                    networkId,
                    accountIndex,
                    notes.toTypedArray(),
                    walletSeed,
                    hotkeySeed,
                    seedFingerprint,
                    roundName
                ) ?: error("buildGovernancePcztFromSeed returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun storeWitnesses(
            roundId: String,
            bundleIndex: Int,
            notes: List<JniNoteInfo>,
            witnesses: List<JniWitnessData>
        ) = withHandle { handle ->
            storeWitnessesNative(
                handle,
                roundId,
                bundleIndex,
                notes.toTypedArray(),
                witnesses.toTypedArray()
            )
        }

        @Throws(RuntimeException::class)
        suspend fun precomputeDelegationPir(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notes: List<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult =
            withHandle { handle ->
                precomputeDelegationPirNative(
                    handle,
                    roundId,
                    bundleIndex,
                    pirServerUrl,
                    networkId,
                    notes.toTypedArray()
                ) ?: error("precomputeDelegationPir returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun buildAndProveDelegation(
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notes: List<JniNoteInfo>,
            hotkeyRawAddress: ByteArray,
            proofProgress: VotingProofProgressCallback?
        ): JniDelegationProofResult =
            withHandle { handle ->
                buildAndProveDelegationNative(
                    handle,
                    roundId,
                    bundleIndex,
                    pirServerUrl,
                    networkId,
                    notes.toTypedArray(),
                    hotkeyRawAddress,
                    proofProgress?.withVotingDbReentryGuard()
                ) ?: error("buildAndProveDelegation returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun getDelegationSubmission(
            roundId: String,
            bundleIndex: Int,
            senderSeed: ByteArray,
            networkId: Int,
            accountIndex: Int
        ): JniDelegationSubmissionResult =
            withHandle { handle ->
                getDelegationSubmissionNative(
                    handle,
                    roundId,
                    bundleIndex,
                    senderSeed,
                    networkId,
                    accountIndex
                ) ?: error("getDelegationSubmission returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun getDelegationSubmissionWithKeystoneSig(
            roundId: String,
            bundleIndex: Int,
            keystoneSig: ByteArray,
            keystoneSighash: ByteArray
        ): JniDelegationSubmissionResult =
            withHandle { handle ->
                getDelegationSubmissionWithKeystoneSigNative(
                    handle,
                    roundId,
                    bundleIndex,
                    keystoneSig,
                    keystoneSighash
                ) ?: error("getDelegationSubmissionWithKeystoneSig returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun storeTreeState(
            roundId: String,
            treeStateBytes: ByteArray
        ) = withHandle { handle ->
            storeTreeStateNative(handle, roundId, treeStateBytes)
        }

        @Throws(RuntimeException::class)
        suspend fun generateNoteWitnesses(
            roundId: String,
            bundleIndex: Int,
            walletDbPath: String,
            networkId: Int,
            notes: List<JniNoteInfo>
        ): Array<JniWitnessData> =
            withHandle { handle ->
                generateNoteWitnessesNative(
                    handle,
                    roundId,
                    bundleIndex,
                    walletDbPath,
                    networkId,
                    notes.toTypedArray()
                ) ?: error("generateNoteWitnesses returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long =
            withHandle { handle ->
                syncVoteTreeNative(handle, roundId, nodeUrl).also { height ->
                    check(height >= 0) {
                        "syncVoteTree failed for roundId=$roundId"
                    }
                }
            }

        @Throws(RuntimeException::class)
        suspend fun resetTreeClient(roundId: String) =
            withHandle { handle ->
                check(resetTreeClientNative(handle, roundId)) {
                    "resetTreeClient failed for roundId=$roundId"
                }
            }

        @Throws(RuntimeException::class)
        suspend fun storeVanPosition(
            roundId: String,
            bundleIndex: Int,
            position: Long
        ) = withHandle { handle ->
            check(storeVanPositionNative(handle, roundId, bundleIndex, position)) {
                "storeVanPosition failed for roundId=$roundId bundleIndex=$bundleIndex"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun generateVanWitness(
            roundId: String,
            bundleIndex: Int,
            anchorHeight: Long
        ): JniVanWitness =
            withHandle { handle ->
                generateVanWitnessNative(handle, roundId, bundleIndex, anchorHeight)
                    ?: error("generateVanWitness returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun buildVoteCommitment(
            roundId: String,
            bundleIndex: Int,
            hotkeySeed: ByteArray,
            proposalId: Int,
            choice: Int,
            numOptions: Int,
            witness: JniVanWitness,
            networkId: Int,
            accountIndex: Int,
            singleShare: Boolean,
            proofProgress: VotingProofProgressCallback?
        ): JniVoteCommitmentResult =
            withHandle { handle ->
                buildVoteCommitmentNative(
                    handle,
                    roundId,
                    bundleIndex,
                    hotkeySeed,
                    proposalId,
                    choice,
                    numOptions,
                    witness,
                    networkId,
                    accountIndex,
                    singleShare,
                    proofProgress?.withVotingDbReentryGuard()
                ) ?: error("buildVoteCommitment returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun storeDelegationTxHash(
            roundId: String,
            bundleIndex: Int,
            txHash: String
        ) = withHandle { handle ->
            check(storeDelegationTxHashNative(handle, roundId, bundleIndex, txHash)) {
                "storeDelegationTxHash failed for roundId=$roundId bundleIndex=$bundleIndex"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun getDelegationTxHash(
            roundId: String,
            bundleIndex: Int
        ): String? =
            withHandle { handle ->
                getDelegationTxHashNative(handle, roundId, bundleIndex)
            }

        @Throws(RuntimeException::class)
        suspend fun storeVoteTxHash(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            txHash: String
        ) = withHandle { handle ->
            check(storeVoteTxHashNative(handle, roundId, bundleIndex, proposalId, txHash)) {
                "storeVoteTxHash failed for roundId=$roundId bundleIndex=$bundleIndex proposalId=$proposalId"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun markVoteSubmitted(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ) = withHandle { handle ->
            check(markVoteSubmittedNative(handle, roundId, bundleIndex, proposalId)) {
                "markVoteSubmitted failed for roundId=$roundId bundleIndex=$bundleIndex proposalId=$proposalId"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun getVoteTxHash(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): String? =
            withHandle { handle ->
                getVoteTxHashNative(handle, roundId, bundleIndex, proposalId)
            }

        @Throws(RuntimeException::class)
        suspend fun storeCommitmentBundle(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            commitment: JniVoteCommitmentResult,
            vcTreePosition: Long
        ) = withHandle { handle ->
            check(
                storeCommitmentBundleNative(
                    handle,
                    roundId,
                    bundleIndex,
                    proposalId,
                    commitment,
                    vcTreePosition
                )
            ) {
                "storeCommitmentBundle failed for roundId=$roundId bundleIndex=$bundleIndex proposalId=$proposalId"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun getCommitmentBundle(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniCommitmentBundleRecord? =
            withHandle { handle ->
                getCommitmentBundleNative(handle, roundId, bundleIndex, proposalId)
            }

        @Throws(RuntimeException::class)
        suspend fun clearRecoveryState(roundId: String) =
            withHandle { handle ->
                check(clearRecoveryStateNative(handle, roundId)) {
                    "clearRecoveryState failed for roundId=$roundId"
                }
            }

        @Throws(RuntimeException::class)
        suspend fun recordShareDelegation(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            sentToUrls: List<String>,
            nullifier: ByteArray,
            submitAt: Long
        ) = withHandle { handle ->
            check(
                recordShareDelegationNative(
                    handle,
                    roundId,
                    bundleIndex,
                    proposalId,
                    shareIndex,
                    sentToUrls.toTypedArray(),
                    nullifier,
                    submitAt
                )
            ) {
                "recordShareDelegation failed for roundId=$roundId " +
                    "bundleIndex=$bundleIndex proposalId=$proposalId shareIndex=$shareIndex"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun getShareDelegations(roundId: String): Array<JniShareDelegationRecord> =
            withHandle { handle ->
                getShareDelegationsNative(handle, roundId)
                    ?: error("getShareDelegations returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun getUnconfirmedDelegations(roundId: String): Array<JniShareDelegationRecord> =
            withHandle { handle ->
                getUnconfirmedDelegationsNative(handle, roundId)
                    ?: error("getUnconfirmedDelegations returned null")
            }

        @Throws(RuntimeException::class)
        suspend fun markShareConfirmed(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int
        ) = withHandle { handle ->
            check(markShareConfirmedNative(handle, roundId, bundleIndex, proposalId, shareIndex)) {
                "markShareConfirmed failed for roundId=$roundId " +
                    "bundleIndex=$bundleIndex proposalId=$proposalId shareIndex=$shareIndex"
            }
        }

        @Throws(RuntimeException::class)
        suspend fun addSentServers(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            newUrls: List<String>
        ) = withHandle { handle ->
            check(
                addSentServersNative(
                    handle,
                    roundId,
                    bundleIndex,
                    proposalId,
                    shareIndex,
                    newUrls.toTypedArray()
                )
            ) {
                "addSentServers failed for roundId=$roundId " +
                    "bundleIndex=$bundleIndex proposalId=$proposalId shareIndex=$shareIndex"
            }
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal suspend fun storeDelegationProofFixtureForTesting(
            roundId: String,
            bundleIndex: Int,
            proof: ByteArray
        ) = withHandle { handle ->
            storeDelegationProofFixtureNative(handle, roundId, bundleIndex, proof)
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal suspend fun storeVoteFixtureForTesting(
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            choice: Int
        ) = withHandle { handle ->
            storeVoteFixtureNative(handle, roundId, bundleIndex, proposalId, choice)
        }

        private suspend fun <T> withHandle(block: (Long) -> T): T {
            checkNotInProofProgressCallback()

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

        private fun checkNotInProofProgressCallback() {
            check(proofProgressCallbackDepth.get() == 0) {
                PROOF_PROGRESS_REENTRY_ERROR
            }
        }

        private fun VotingProofProgressCallback.withVotingDbReentryGuard() =
            VotingProofProgressCallback { progress ->
                proofProgressCallbackDepth.incrementAndGet()
                try {
                    onProgress(progress)
                } finally {
                    proofProgressCallbackDepth.decrementAndGet()
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
        private external fun decomposeWeightNative(weight: Long): LongArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildSharePayloadsNative(
            commitment: JniVoteCommitmentResult,
            voteDecision: Int,
            numOptions: Int,
            vcTreePosition: Long,
            singleShareMode: Boolean
        ): Array<JniSharePayload>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun signCastVoteNative(
            hotkeySeed: ByteArray,
            networkId: Int,
            accountIndex: Int,
            commitment: JniVoteCommitmentResult
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractOrchardFvkFromUfvkNative(
            ufvk: String,
            networkId: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun deriveHotkeyRawAddressNative(
            hotkeySeed: ByteArray,
            networkId: Int,
            accountIndex: Int
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractNcRootNative(treeStateBytes: ByteArray): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun verifyWitnessNative(witness: JniWitnessData): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getWalletNotesNative(
            walletDbPath: String,
            snapshotHeight: Long,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Array<JniNoteInfo>?

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
        private external fun computeBundleSetupNative(notes: Array<JniNoteInfo>): JniBundleSetupResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun setupBundlesNative(
            dbHandle: Long,
            roundId: String,
            notes: Array<JniNoteInfo>
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
        private external fun buildGovernancePcztNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            fvkBytes: ByteArray,
            hotkeyRawAddress: ByteArray,
            networkId: Int,
            accountIndex: Int,
            notes: Array<JniNoteInfo>,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildGovernancePcztFromSeedNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            ufvk: String,
            networkId: Int,
            accountIndex: Int,
            notes: Array<JniNoteInfo>,
            walletSeed: ByteArray,
            hotkeySeed: ByteArray,
            seedFingerprint: ByteArray,
            roundName: String
        ): JniGovernancePczt?

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
        private external fun delegationProofResultFixtureNative(): JniDelegationProofResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun noteInfoArrayFixtureNative(): Array<JniNoteInfo>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun witnessDataArrayFixtureNative(): Array<JniWitnessData>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun treeStateFixtureNative(): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun nonEmptyTreeStateFixtureNative(): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeWitnessesNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            notes: Array<JniNoteInfo>,
            witnesses: Array<JniWitnessData>
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun precomputeDelegationPirNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notes: Array<JniNoteInfo>
        ): JniDelegationPirPrecomputeResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildAndProveDelegationNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            pirServerUrl: String,
            networkId: Int,
            notes: Array<JniNoteInfo>,
            hotkeyRawAddress: ByteArray,
            proofProgress: VotingProofProgressCallback?
        ): JniDelegationProofResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getDelegationSubmissionNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            senderSeed: ByteArray,
            networkId: Int,
            accountIndex: Int
        ): JniDelegationSubmissionResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getDelegationSubmissionWithKeystoneSigNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            keystoneSig: ByteArray,
            keystoneSighash: ByteArray
        ): JniDelegationSubmissionResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeTreeStateNative(
            dbHandle: Long,
            roundId: String,
            treeStateBytes: ByteArray
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun generateNoteWitnessesNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            walletDbPath: String,
            networkId: Int,
            notes: Array<JniNoteInfo>
        ): Array<JniWitnessData>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun syncVoteTreeNative(
            dbHandle: Long,
            roundId: String,
            nodeUrl: String
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun resetTreeClientNative(
            dbHandle: Long,
            roundId: String
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeVanPositionNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            position: Long
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun generateVanWitnessNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            anchorHeight: Long
        ): JniVanWitness?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildVoteCommitmentNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            hotkeySeed: ByteArray,
            proposalId: Int,
            choice: Int,
            numOptions: Int,
            witness: JniVanWitness,
            networkId: Int,
            accountIndex: Int,
            singleShare: Boolean,
            proofProgress: VotingProofProgressCallback?
        ): JniVoteCommitmentResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeDelegationTxHashNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            txHash: String
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getDelegationTxHashNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int
        ): String?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeVoteTxHashNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            txHash: String
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun markVoteSubmittedNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getVoteTxHashNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): String?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeCommitmentBundleNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            commitment: JniVoteCommitmentResult,
            vcTreePosition: Long
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getCommitmentBundleNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int
        ): JniCommitmentBundleRecord?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun clearRecoveryStateNative(dbHandle: Long, roundId: String): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun recordShareDelegationNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            sentToUrls: Array<String>,
            nullifier: ByteArray,
            submitAt: Long
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getShareDelegationsNative(
            dbHandle: Long,
            roundId: String
        ): Array<JniShareDelegationRecord>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getUnconfirmedDelegationsNative(
            dbHandle: Long,
            roundId: String
        ): Array<JniShareDelegationRecord>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun markShareConfirmedNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun addSentServersNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            shareIndex: Int,
            newUrls: Array<String>
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeDelegationProofFixtureNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proof: ByteArray
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeVoteFixtureNative(
            dbHandle: Long,
            roundId: String,
            bundleIndex: Int,
            proposalId: Int,
            choice: Int
        )
    }
}
