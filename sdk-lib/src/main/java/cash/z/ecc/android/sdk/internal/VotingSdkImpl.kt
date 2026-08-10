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

@Suppress("TooManyFunctions", "LongParameterList")
internal class VotingSdkImpl(
    private val backend: TypesafeVotingBackend = TypesafeVotingBackendImpl()
) : VotingSdk {
    override suspend fun isAvailable(): Boolean =
        runCatching { backend.warmProvingCaches() }
            .fold(onSuccess = { true }, onFailure = { it !is UnsatisfiedLinkError })

    override suspend fun openDb(dbPath: String, walletId: String, networkId: Int): VotingDbSession =
        VotingDbSessionImpl(backend.openVotingDb(dbPath, walletId, networkId))

    override suspend fun computeShareNullifier(voteCommitment: ByteArray, shareIndex: Int, blind: ByteArray): ByteArray =
        backend.computeShareNullifier(voteCommitment, shareIndex, blind)

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
    ): List<VotingNoteInfo> = backend.getWalletNotes(walletDbPath, snapshotHeight, networkId, accountUuid).map { it.toPublic() }

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

    override suspend fun generateHotkey(storedSecret: ByteArray): VotingHotkey = db.generateHotkey(storedSecret).toPublic()

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
                roundId, bundleIndex, fvkBytes, hotkeySecret, accountIndex,
                notes.map { it.toInternal() }, seedFingerprint, roundName
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
                roundId, bundleIndex, ufvk, networkId, accountIndex,
                notes.map { it.toInternal() }, walletSeed, hotkeySecret, seedFingerprint, roundName
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
}
