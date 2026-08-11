package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.VotingDbSession
import cash.z.ecc.android.sdk.VotingSdk
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.voting.VotingBundleSetupResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitResult
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentBundleRecord
import cash.z.ecc.android.sdk.model.voting.VotingCommitmentResult
import cash.z.ecc.android.sdk.model.voting.VotingCommittedVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPhase
import cash.z.ecc.android.sdk.model.voting.VotingDelegationPirPrecomputeResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationProofResult
import cash.z.ecc.android.sdk.model.voting.VotingDelegationSubmissionResult
import cash.z.ecc.android.sdk.model.voting.VotingEncryptedShare
import cash.z.ecc.android.sdk.model.voting.VotingGovernancePczt
import cash.z.ecc.android.sdk.model.voting.VotingHotkey
import cash.z.ecc.android.sdk.model.voting.VotingNoteInfo
import cash.z.ecc.android.sdk.model.voting.VotingNoteScope
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import cash.z.ecc.android.sdk.model.voting.VotingRoundState
import cash.z.ecc.android.sdk.model.voting.VotingRoundSummary
import cash.z.ecc.android.sdk.model.voting.VotingShareDelegationRecord
import cash.z.ecc.android.sdk.model.voting.VotingSharePayload
import cash.z.ecc.android.sdk.model.voting.VotingTxHashLookup
import cash.z.ecc.android.sdk.model.voting.VotingVanWitness
import cash.z.ecc.android.sdk.model.voting.VotingVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingWitness
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions", "LongParameterList")
internal class VotingSdkImpl(
    private val backend: TypesafeVotingBackend = TypesafeVotingBackendImpl()
) : VotingSdk {
    private val isAvailableMutex = Mutex()

    @Volatile
    private var cachedIsAvailable: Boolean? = null

    // Probing availability warms the (expensive) Halo2 proving caches as a side effect, so the
    // result is computed at most once per process and cached here rather than on every call.
    // Any failure -- not just UnsatisfiedLinkError -- means unavailable: NativeLibraryLoader
    // wraps a failed System.loadLibrary in AssertionError, not UnsatisfiedLinkError, so a
    // `!is UnsatisfiedLinkError` check would previously report "available" for exactly the
    // missing-native-library case this gate exists to catch.
    override suspend fun isAvailable(): Boolean =
        cachedIsAvailable ?: isAvailableMutex.withLock {
            cachedIsAvailable ?: runCatching { backend.warmProvingCaches() }.isSuccess.also {
                cachedIsAvailable = it
            }
        }

    override suspend fun openDb(dbPath: String, walletId: String, networkId: Int): VotingDbSession =
        VotingDbSessionImpl(backend.openVotingDb(dbPath, walletId, networkId))

    override suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray = backend.computeShareNullifier(voteCommitment, shareIndex, blind)

    override suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): VotingBundleSetupResult =
        backend.computeBundleSetup(notes.map { it.toInternal() }).toPublic()

    override suspend fun warmProvingCaches() = backend.warmProvingCaches()

    override suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long = backend.scheduledShareSubmitAt(nowSeconds, ceremonyStartSeconds, voteEndTimeSeconds, singleShare)

    override suspend fun buildSharePayloads(
        commitment: VotingCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): List<VotingSharePayload> =
        backend
            .buildSharePayloads(commitment.toInternal(), voteDecision, numOptions, vcTreePosition, singleShareMode)
            .map { it.toPublic() }

    override suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray =
        backend.extractOrchardFvkFromUfvk(ufvk, networkId)

    override suspend fun deriveHotkeyRawAddress(hotkeySeed: ByteArray, networkId: Int): ByteArray =
        backend.deriveHotkeyRawAddress(hotkeySeed, networkId)

    override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray = backend.extractNcRoot(treeStateBytes)

    override suspend fun verifyWitness(witness: VotingWitness): Boolean = backend.verifyWitness(witness.toInternal())

    override suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: BlockHeight,
        networkId: Int,
        accountUuid: AccountUuid
    ): List<VotingNoteInfo> =
        backend.getWalletNotes(walletDbPath, snapshotHeight, networkId, accountUuid).map { it.toPublic() }

    override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray = backend.extractPcztSighash(pcztBytes)

    override suspend fun extractSpendAuthSig(signedPcztBytes: ByteArray, actionIndex: Int): ByteArray =
        backend.extractSpendAuthSig(signedPcztBytes, actionIndex)
}

@Suppress("TooManyFunctions", "LongParameterList")
internal class VotingDbSessionImpl(
    private val db: TypesafeVotingDb
) : VotingDbSession {
    override suspend fun close() = db.close()

    override suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    ) = db.initRound(roundId, snapshotHeight, eaPK, ncRoot, nullifierIMTRoot, sessionJson)

    override suspend fun getRoundState(roundId: String): VotingRoundState? = db.getRoundState(roundId)?.toPublic()

    override suspend fun listRounds(): List<VotingRoundSummary> = db.listRounds().map { it.toPublic() }

    override suspend fun getBundleCount(roundId: String): Int = db.getBundleCount(roundId)

    override suspend fun getVotes(roundId: String): List<VotingVoteRecord> = db.getVotes(roundId).map { it.toPublic() }

    override suspend fun clearRound(roundId: String) = db.clearRound(roundId)

    override suspend fun deleteSkippedBundles(roundId: String, keepCount: Int): Long =
        db.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(roundId: String, notes: List<VotingNoteInfo>): VotingBundleSetupResult =
        db.setupBundles(roundId, notes.map { it.toInternal() }).toPublic()

    override suspend fun generateHotkey(storedSecret: ByteArray): VotingHotkey =
        db.generateHotkey(storedSecret).toPublic()

    override suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        fvkBytes: ByteArray,
        hotkeySecret: ByteArray,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        seedFingerprint: ByteArray,
        roundName: String
    ): VotingGovernancePczt =
        db
            .buildGovernancePczt(
                roundId,
                bundleIndex,
                fvkBytes,
                hotkeySecret,
                accountIndex,
                notes.map { it.toInternal() },
                seedFingerprint,
                roundName
            ).toPublic()

    override suspend fun buildGovernancePcztFromSeed(
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        walletSeed: ByteArray,
        hotkeySecret: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String
    ): VotingGovernancePczt =
        db
            .buildGovernancePcztFromSeed(
                roundId,
                bundleIndex,
                ufvk,
                networkId,
                accountIndex,
                notes.map { it.toInternal() },
                walletSeed,
                hotkeySecret,
                seedFingerprint,
                roundName
            ).toPublic()

    override suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<VotingNoteInfo>,
        witnesses: List<VotingWitness>
    ) = db.storeWitnesses(roundId, bundleIndex, notes.map { it.toInternal() }, witnesses.map { it.toInternal() })

    override suspend fun delegationPhases(roundId: String): List<VotingDelegationPhase> =
        db.delegationPhases(roundId).map { it.toPublic() }

    override suspend fun resetVotingSessionState(roundId: String) = db.resetVotingSessionState(roundId)

    override suspend fun storeKeystoneSignature(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray,
        rk: ByteArray
    ) = db.storeKeystoneSignature(roundId, bundleIndex, keystoneSig, keystoneSighash, rk)

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>
    ): VotingDelegationPirPrecomputeResult =
        db
            .precomputeDelegationPir(
                roundId,
                bundleIndex,
                pirServerUrl,
                pirDepth,
                pirTier0Layers,
                pirTier1Layers,
                notes.map { it.toInternal() }
            ).toPublic()

    override suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>,
        fvkBytes: ByteArray,
        hotkeySecret: ByteArray,
        seedFingerprint: ByteArray,
        accountIndex: Int,
        roundName: String,
        proofProgress: ((Double) -> Unit)?
    ): VotingDelegationProofResult =
        db
            .buildAndProveDelegation(
                roundId,
                bundleIndex,
                pirServerUrl,
                pirDepth,
                pirTier0Layers,
                pirTier1Layers,
                notes.map { it.toInternal() },
                fvkBytes,
                hotkeySecret,
                seedFingerprint,
                accountIndex,
                roundName,
                proofProgress
            ).toPublic()

    override suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        accountUuid: String,
        hotkeySecret: ByteArray,
        roundName: String,
        senderSeed: ByteArray
    ): VotingDelegationSubmissionResult =
        db
            .getDelegationSubmission(
                roundId,
                bundleIndex,
                walletDbPath,
                accountUuid,
                hotkeySecret,
                roundName,
                senderSeed
            ).toPublic()

    override suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): VotingDelegationSubmissionResult =
        db.getDelegationSubmissionWithKeystoneSig(roundId, bundleIndex, keystoneSig, keystoneSighash).toPublic()

    override suspend fun storeTreeState(roundId: String, treeStateBytes: ByteArray) =
        db.storeTreeState(roundId, treeStateBytes)

    override suspend fun generateNoteWitnesses(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        networkId: Int,
        notes: List<VotingNoteInfo>
    ): List<VotingWitness> =
        db
            .generateNoteWitnesses(roundId, bundleIndex, walletDbPath, networkId, notes.map { it.toInternal() })
            .map { it.toPublic() }

    override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long = db.syncVoteTree(roundId, nodeUrl)

    override suspend fun resetTreeClient(roundId: String) = db.resetTreeClient(roundId)

    override suspend fun resetAllTreeClients() = db.resetAllTreeClients()

    override suspend fun storeVanPosition(roundId: String, bundleIndex: Int, position: Long) =
        db.storeVanPosition(roundId, bundleIndex, position)

    override suspend fun generateVanWitness(roundId: String, bundleIndex: Int, anchorHeight: Long): VotingVanWitness =
        db.generateVanWitness(roundId, bundleIndex, anchorHeight).toPublic()

    override suspend fun buildVoteCommitment(
        roundId: String,
        bundleIndex: Int,
        hotkeySecret: ByteArray,
        proposalId: Int,
        choice: Int,
        numOptions: Int,
        witness: VotingVanWitness,
        singleShare: Boolean,
        proofProgress: ((Double) -> Unit)?
    ): VotingCommitResult =
        db
            .buildVoteCommitment(
                roundId,
                bundleIndex,
                hotkeySecret,
                proposalId,
                choice,
                numOptions,
                witness.toInternal(),
                singleShare,
                proofProgress
            ).toPublic()

    override suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String) =
        db.storeDelegationTxHash(roundId, bundleIndex, txHash)

    override suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): VotingTxHashLookup =
        db.getDelegationTxHash(roundId, bundleIndex).toPublic()

    override suspend fun storeVoteTxHash(roundId: String, bundleIndex: Int, proposalId: Int, txHash: String) =
        db.storeVoteTxHash(roundId, bundleIndex, proposalId, txHash)

    override suspend fun markVoteSubmitted(roundId: String, bundleIndex: Int, proposalId: Int) =
        db.markVoteSubmitted(roundId, bundleIndex, proposalId)

    override suspend fun getVoteTxHash(roundId: String, bundleIndex: Int, proposalId: Int): VotingTxHashLookup =
        db.getVoteTxHash(roundId, bundleIndex, proposalId).toPublic()

    override suspend fun getCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): VotingCommitmentBundleRecord? = db.getCommitmentBundle(roundId, bundleIndex, proposalId)?.toPublic()

    override suspend fun recordVcPosition(roundId: String, bundleIndex: Int, proposalId: Int, vcTreePosition: Long) =
        db.recordVcPosition(roundId, bundleIndex, proposalId, vcTreePosition)

    override suspend fun recoverCommittedVote(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): VotingCommittedVoteRecord = db.recoverCommittedVote(roundId, bundleIndex, proposalId).toPublic()

    override suspend fun clearRecoveryState(roundId: String) = db.clearRecoveryState(roundId)

    override suspend fun recordShareDelegation(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: List<String>,
        nullifier: ByteArray,
        submitAt: Long
    ) = db.recordShareDelegation(roundId, bundleIndex, proposalId, shareIndex, sentToUrls, nullifier, submitAt)

    override suspend fun getShareDelegations(roundId: String): List<VotingShareDelegationRecord> =
        db.getShareDelegations(roundId).map { it.toPublic() }

    override suspend fun getUnconfirmedDelegations(roundId: String): List<VotingShareDelegationRecord> =
        db.getUnconfirmedDelegations(roundId).map { it.toPublic() }

    override suspend fun markShareConfirmed(roundId: String, bundleIndex: Int, proposalId: Int, shareIndex: Int) =
        db.markShareConfirmed(roundId, bundleIndex, proposalId, shareIndex)

    override suspend fun addSentServers(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        newUrls: List<String>
    ) = db.addSentServers(roundId, bundleIndex, proposalId, shareIndex, newUrls)
}
