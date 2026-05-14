@file:Suppress("TooManyFunctions")

package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.JNI_DELEGATION_PUBLIC_INPUT_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_GOVERNANCE_NULLIFIER_COUNT
import cash.z.ecc.android.sdk.internal.jni.JNI_PROTOCOL_FIELD_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_SPEND_AUTH_SIG_BYTES_SIZE
import cash.z.ecc.android.sdk.internal.jni.JNI_VAN_WITNESS_PATH_DEPTH
import cash.z.ecc.android.sdk.internal.jni.JNI_VOTE_SHARE_COUNT
import cash.z.ecc.android.sdk.internal.jni.VotingProofProgressCallback
import cash.z.ecc.android.sdk.internal.jni.VotingRustBackend
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
import cash.z.ecc.android.sdk.internal.model.voting.JniWireEncryptedShare
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight

@Suppress("TooManyFunctions", "LongParameterList")
internal class TypesafeVotingBackendImpl(
    private val rustBackendFactory: suspend () -> VotingBackendBridge = {
        RustVotingBackendBridge(VotingRustBackend.new())
    }
) : TypesafeVotingBackend {
    private val rustBackendLazy =
        SuspendingLazy<Unit, VotingBackendBridge> {
            rustBackendFactory()
        }

    override suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray =
        rustBackend().computeShareNullifier(voteCommitment, shareIndex, blind)

    override suspend fun openVotingDb(dbPath: String, walletId: String): TypesafeVotingDb =
        TypesafeVotingDbImpl(
            rustBackend().openVotingDb(dbPath, walletId)
        )

    override suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): JniBundleSetupResult =
        rustBackend().computeBundleSetup(notes.toJniNoteInfos())

    override suspend fun warmProvingCaches() =
        rustBackend().warmProvingCaches()

    override suspend fun decomposeWeight(weight: Long): List<Long> =
        rustBackend().decomposeWeight(weight).asList()

    override suspend fun buildSharePayloads(
        commitment: JniVoteCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): List<JniSharePayload> {
        commitment.requireValid()
        return rustBackend()
            .buildSharePayloads(
                commitment,
                voteDecision,
                numOptions,
                vcTreePosition,
                singleShareMode
            ).also { payloads ->
                val expectedCount = if (singleShareMode) 1 else JNI_VOTE_SHARE_COUNT
                require(payloads.size == expectedCount) {
                    "sharePayloads must contain $expectedCount entries, got ${payloads.size}"
                }
            }.onEach { payload ->
                payload.requireValid()
            }.asList()
    }

    override suspend fun signCastVote(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int,
        commitment: JniVoteCommitmentResult
    ): ByteArray {
        commitment.requireValid()
        return rustBackend()
            .signCastVote(
                hotkeySeed,
                networkId,
                accountIndex,
                commitment
            ).also { sig ->
                sig.requireByteArraySize("voteAuthSig", JNI_SPEND_AUTH_SIG_BYTES_SIZE)
            }
    }

    override suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray =
        rustBackend().extractOrchardFvkFromUfvk(ufvk, networkId)

    override suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): ByteArray =
        rustBackend().deriveHotkeyRawAddress(hotkeySeed, networkId, accountIndex)

    override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray =
        rustBackend().extractNcRoot(treeStateBytes)

    override suspend fun verifyWitness(witness: JniWitnessData): Boolean =
        rustBackend().verifyWitness(witness)

    override suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: BlockHeight,
        networkId: Int,
        accountUuid: AccountUuid
    ): List<VotingNoteInfo> =
        rustBackend()
            .getWalletNotes(
                walletDbPath,
                snapshotHeight.value,
                networkId,
                accountUuid.value
            ).map { it.toVotingNoteInfo() }

    override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray =
        rustBackend().extractPcztSighash(pcztBytes)

    override suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray =
        rustBackend().extractSpendAuthSig(signedPcztBytes, actionIndex)

    private suspend fun rustBackend() = rustBackendLazy.getInstance(Unit)
}

@Suppress("TooManyFunctions")
internal interface VotingBackendBridge {
    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray

    suspend fun openVotingDb(dbPath: String, walletId: String): VotingDbBackend

    suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult

    suspend fun warmProvingCaches()

    suspend fun decomposeWeight(weight: Long): LongArray

    suspend fun buildSharePayloads(
        commitment: JniVoteCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): Array<JniSharePayload>

    suspend fun signCastVote(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int,
        commitment: JniVoteCommitmentResult
    ): ByteArray

    suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray

    suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): ByteArray

    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray

    suspend fun verifyWitness(witness: JniWitnessData): Boolean

    suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Array<JniNoteInfo>

    suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray

    suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray
}

private class RustVotingBackendBridge(
    private val rustBackend: VotingRustBackend
) : VotingBackendBridge {
    override suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray =
        rustBackend.computeShareNullifier(voteCommitment, shareIndex, blind)

    override suspend fun openVotingDb(dbPath: String, walletId: String): VotingDbBackend =
        RustVotingDbBackend(rustBackend.openVotingDb(dbPath, walletId))

    override suspend fun computeBundleSetup(notes: List<JniNoteInfo>): JniBundleSetupResult =
        rustBackend.computeBundleSetup(notes)

    override suspend fun warmProvingCaches() =
        rustBackend.warmProvingCaches()

    override suspend fun decomposeWeight(weight: Long): LongArray =
        rustBackend.decomposeWeight(weight)

    override suspend fun buildSharePayloads(
        commitment: JniVoteCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean
    ): Array<JniSharePayload> =
        rustBackend.buildSharePayloads(
            commitment,
            voteDecision,
            numOptions,
            vcTreePosition,
            singleShareMode
        )

    override suspend fun signCastVote(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int,
        commitment: JniVoteCommitmentResult
    ): ByteArray =
        rustBackend.signCastVote(hotkeySeed, networkId, accountIndex, commitment)

    override suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray =
        rustBackend.extractOrchardFvkFromUfvk(ufvk, networkId)

    override suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): ByteArray =
        rustBackend.deriveHotkeyRawAddress(hotkeySeed, networkId, accountIndex)

    override suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray =
        rustBackend.extractNcRoot(treeStateBytes)

    override suspend fun verifyWitness(witness: JniWitnessData): Boolean =
        rustBackend.verifyWitness(witness)

    override suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: Long,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Array<JniNoteInfo> =
        rustBackend.getWalletNotes(walletDbPath, snapshotHeight, networkId, accountUuidBytes)

    override suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray =
        rustBackend.extractPcztSighash(pcztBytes)

    override suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray =
        rustBackend.extractSpendAuthSig(signedPcztBytes, actionIndex)
}

@Suppress("TooManyFunctions", "LongParameterList")
internal interface VotingDbBackend {
    suspend fun close()

    suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    )

    suspend fun getRoundState(roundId: String): JniRoundState?

    suspend fun listRounds(): Array<JniRoundSummary>

    suspend fun getBundleCount(roundId: String): Int

    suspend fun getVotes(roundId: String): Array<JniVoteRecord>

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long

    suspend fun setupBundles(
        roundId: String,
        notes: List<JniNoteInfo>
    ): JniBundleSetupResult

    suspend fun generateHotkey(
        roundId: String,
        seed: ByteArray
    ): JniVotingHotkey

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
    ): JniGovernancePczt

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
    ): JniGovernancePczt

    suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<JniNoteInfo>,
        witnesses: List<JniWitnessData>
    )

    suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>
    ): JniDelegationPirPrecomputeResult

    suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>,
        hotkeyRawAddress: ByteArray,
        proofProgress: VotingProofProgressCallback?
    ): JniDelegationProofResult

    suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): JniDelegationSubmissionResult

    suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): JniDelegationSubmissionResult

    suspend fun storeTreeState(roundId: String, treeStateBytes: ByteArray)

    suspend fun generateNoteWitnesses(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        networkId: Int,
        notes: List<JniNoteInfo>
    ): Array<JniWitnessData>

    suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long

    suspend fun resetTreeClient(roundId: String)

    suspend fun resetAllTreeClients()

    suspend fun storeVanPosition(roundId: String, bundleIndex: Int, position: Long)

    suspend fun generateVanWitness(
        roundId: String,
        bundleIndex: Int,
        anchorHeight: Long
    ): JniVanWitness

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
    ): JniVoteCommitmentResult

    suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String)

    suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): String?

    suspend fun storeVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        txHash: String
    )

    suspend fun markVoteSubmitted(roundId: String, bundleIndex: Int, proposalId: Int)

    suspend fun getVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): String?

    suspend fun storeCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        commitment: JniVoteCommitmentResult,
        vcTreePosition: Long
    )

    suspend fun getCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): JniCommitmentBundleRecord?

    suspend fun clearRecoveryState(roundId: String)

    suspend fun recordShareDelegation(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: List<String>,
        nullifier: ByteArray,
        submitAt: Long
    )

    suspend fun getShareDelegations(roundId: String): Array<JniShareDelegationRecord>

    suspend fun getUnconfirmedDelegations(roundId: String): Array<JniShareDelegationRecord>

    suspend fun markShareConfirmed(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int
    )

    suspend fun addSentServers(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        newUrls: List<String>
    )
}

@Suppress("TooManyFunctions", "LongParameterList")
private class RustVotingDbBackend(
    private val votingDb: VotingRustBackend.VotingDb
) : VotingDbBackend {
    override suspend fun close() = votingDb.close()

    override suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    ) = votingDb.initRound(
        roundId,
        snapshotHeight,
        eaPK,
        ncRoot,
        nullifierIMTRoot,
        sessionJson
    )

    override suspend fun getRoundState(roundId: String): JniRoundState? =
        votingDb.getRoundState(roundId)

    override suspend fun listRounds(): Array<JniRoundSummary> =
        votingDb.listRounds()

    override suspend fun getBundleCount(roundId: String): Int =
        votingDb.getBundleCount(roundId)

    override suspend fun getVotes(roundId: String): Array<JniVoteRecord> =
        votingDb.getVotes(roundId)

    override suspend fun clearRound(roundId: String) =
        votingDb.clearRound(roundId)

    override suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long = votingDb.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(
        roundId: String,
        notes: List<JniNoteInfo>
    ): JniBundleSetupResult = votingDb.setupBundles(roundId, notes)

    override suspend fun generateHotkey(
        roundId: String,
        seed: ByteArray
    ): JniVotingHotkey = votingDb.generateHotkey(roundId, seed)

    override suspend fun buildGovernancePczt(
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
        votingDb.buildGovernancePczt(
            roundId,
            bundleIndex,
            fvkBytes,
            hotkeyRawAddress,
            networkId,
            accountIndex,
            notes,
            seedFingerprint,
            roundName
        )

    override suspend fun buildGovernancePcztFromSeed(
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
        votingDb.buildGovernancePcztFromSeed(
            roundId,
            bundleIndex,
            ufvk,
            networkId,
            accountIndex,
            notes,
            walletSeed,
            hotkeySeed,
            seedFingerprint,
            roundName
        )

    override suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<JniNoteInfo>,
        witnesses: List<JniWitnessData>
    ) = votingDb.storeWitnesses(roundId, bundleIndex, notes, witnesses)

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>
    ): JniDelegationPirPrecomputeResult =
        votingDb.precomputeDelegationPir(
            roundId,
            bundleIndex,
            pirServerUrl,
            networkId,
            notes
        )

    override suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<JniNoteInfo>,
        hotkeyRawAddress: ByteArray,
        proofProgress: VotingProofProgressCallback?
    ): JniDelegationProofResult =
        votingDb.buildAndProveDelegation(
            roundId,
            bundleIndex,
            pirServerUrl,
            networkId,
            notes,
            hotkeyRawAddress,
            proofProgress
        )

    override suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): JniDelegationSubmissionResult =
        votingDb.getDelegationSubmission(
            roundId,
            bundleIndex,
            senderSeed,
            networkId,
            accountIndex
        )

    override suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): JniDelegationSubmissionResult =
        votingDb.getDelegationSubmissionWithKeystoneSig(
            roundId,
            bundleIndex,
            keystoneSig,
            keystoneSighash
        )

    override suspend fun storeTreeState(roundId: String, treeStateBytes: ByteArray) =
        votingDb.storeTreeState(roundId, treeStateBytes)

    override suspend fun generateNoteWitnesses(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        networkId: Int,
        notes: List<JniNoteInfo>
    ): Array<JniWitnessData> =
        votingDb.generateNoteWitnesses(
            roundId,
            bundleIndex,
            walletDbPath,
            networkId,
            notes
        )

    override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long =
        votingDb.syncVoteTree(roundId, nodeUrl)

    override suspend fun resetTreeClient(roundId: String) =
        votingDb.resetTreeClient(roundId)

    override suspend fun resetAllTreeClients() =
        votingDb.resetTreeClient("")

    override suspend fun storeVanPosition(roundId: String, bundleIndex: Int, position: Long) =
        votingDb.storeVanPosition(roundId, bundleIndex, position)

    override suspend fun generateVanWitness(
        roundId: String,
        bundleIndex: Int,
        anchorHeight: Long
    ): JniVanWitness =
        votingDb.generateVanWitness(roundId, bundleIndex, anchorHeight)

    override suspend fun buildVoteCommitment(
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
        votingDb.buildVoteCommitment(
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
            proofProgress
        )

    override suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String) =
        votingDb.storeDelegationTxHash(roundId, bundleIndex, txHash)

    override suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): String? =
        votingDb.getDelegationTxHash(roundId, bundleIndex)

    override suspend fun storeVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        txHash: String
    ) = votingDb.storeVoteTxHash(roundId, bundleIndex, proposalId, txHash)

    override suspend fun markVoteSubmitted(roundId: String, bundleIndex: Int, proposalId: Int) =
        votingDb.markVoteSubmitted(roundId, bundleIndex, proposalId)

    override suspend fun getVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): String? =
        votingDb.getVoteTxHash(roundId, bundleIndex, proposalId)

    override suspend fun storeCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        commitment: JniVoteCommitmentResult,
        vcTreePosition: Long
    ) = votingDb.storeCommitmentBundle(
        roundId,
        bundleIndex,
        proposalId,
        commitment,
        vcTreePosition
    )

    override suspend fun getCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): JniCommitmentBundleRecord? =
        votingDb.getCommitmentBundle(roundId, bundleIndex, proposalId)

    override suspend fun clearRecoveryState(roundId: String) =
        votingDb.clearRecoveryState(roundId)

    override suspend fun recordShareDelegation(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: List<String>,
        nullifier: ByteArray,
        submitAt: Long
    ) = votingDb.recordShareDelegation(
        roundId,
        bundleIndex,
        proposalId,
        shareIndex,
        sentToUrls,
        nullifier,
        submitAt
    )

    override suspend fun getShareDelegations(roundId: String): Array<JniShareDelegationRecord> =
        votingDb.getShareDelegations(roundId)

    override suspend fun getUnconfirmedDelegations(roundId: String): Array<JniShareDelegationRecord> =
        votingDb.getUnconfirmedDelegations(roundId)

    override suspend fun markShareConfirmed(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int
    ) = votingDb.markShareConfirmed(roundId, bundleIndex, proposalId, shareIndex)

    override suspend fun addSentServers(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        newUrls: List<String>
    ) = votingDb.addSentServers(roundId, bundleIndex, proposalId, shareIndex, newUrls)
}

@Suppress("TooManyFunctions", "LongParameterList")
internal class TypesafeVotingDbImpl(
    private val votingDb: VotingDbBackend
) : TypesafeVotingDb {
    override suspend fun close() = votingDb.close()

    override suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        sessionJson: String?
    ) = votingDb.initRound(
        roundId,
        snapshotHeight,
        eaPK,
        ncRoot,
        nullifierIMTRoot,
        sessionJson
    )

    override suspend fun getRoundState(roundId: String): JniRoundState? =
        votingDb.getRoundState(roundId)

    override suspend fun listRounds(): List<JniRoundSummary> =
        votingDb.listRounds().asList()

    override suspend fun getBundleCount(roundId: String): Int =
        votingDb.getBundleCount(roundId)

    override suspend fun getVotes(roundId: String): List<JniVoteRecord> =
        votingDb.getVotes(roundId).asList()

    override suspend fun clearRound(roundId: String) =
        votingDb.clearRound(roundId)

    override suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long = votingDb.deleteSkippedBundles(roundId, keepCount)

    override suspend fun setupBundles(
        roundId: String,
        notes: List<VotingNoteInfo>
    ): JniBundleSetupResult =
        votingDb.setupBundles(roundId, notes.toJniNoteInfos())

    override suspend fun generateHotkey(
        roundId: String,
        seed: ByteArray
    ): JniVotingHotkey =
        votingDb.generateHotkey(roundId, seed)

    override suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        fvkBytes: ByteArray,
        hotkeyRawAddress: ByteArray,
        networkId: Int,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        seedFingerprint: ByteArray,
        roundName: String
    ): GovernancePcztResult =
        votingDb
            .buildGovernancePczt(
                roundId,
                bundleIndex,
                fvkBytes,
                hotkeyRawAddress,
                networkId,
                accountIndex,
                notes.toJniNoteInfos(),
                seedFingerprint,
                roundName
            ).toGovernancePcztResult()

    override suspend fun buildGovernancePcztFromSeed(
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        walletSeed: ByteArray,
        hotkeySeed: ByteArray,
        seedFingerprint: ByteArray,
        roundName: String
    ): GovernancePcztResult =
        votingDb
            .buildGovernancePcztFromSeed(
                roundId,
                bundleIndex,
                ufvk,
                networkId,
                accountIndex,
                notes.toJniNoteInfos(),
                walletSeed,
                hotkeySeed,
                seedFingerprint,
                roundName
            ).toGovernancePcztResult()

    override suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<VotingNoteInfo>,
        witnesses: List<JniWitnessData>
    ) = votingDb.storeWitnesses(roundId, bundleIndex, notes.toJniNoteInfos(), witnesses)

    override suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<VotingNoteInfo>
    ): DelegationPirPrecomputeResult =
        votingDb
            .precomputeDelegationPir(
                roundId,
                bundleIndex,
                pirServerUrl,
                networkId,
                notes.toJniNoteInfos()
            ).toDelegationPirPrecomputeResult()

    override suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<VotingNoteInfo>,
        hotkeyRawAddress: ByteArray,
        proofProgress: ((Double) -> Unit)?
    ): DelegationProofResult =
        votingDb
            .buildAndProveDelegation(
                roundId,
                bundleIndex,
                pirServerUrl,
                networkId,
                notes.toJniNoteInfos(),
                hotkeyRawAddress,
                proofProgress?.asVotingProgressCallback()
            ).toDelegationProofResult()

    override suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): DelegationSubmissionResult =
        votingDb
            .getDelegationSubmission(
                roundId,
                bundleIndex,
                senderSeed,
                networkId,
                accountIndex
            ).toDelegationSubmissionResult()

    override suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): DelegationSubmissionResult =
        votingDb
            .getDelegationSubmissionWithKeystoneSig(
                roundId,
                bundleIndex,
                keystoneSig,
                keystoneSighash
            ).toDelegationSubmissionResult()

    override suspend fun storeTreeState(roundId: String, treeStateBytes: ByteArray) =
        votingDb.storeTreeState(roundId, treeStateBytes)

    override suspend fun generateNoteWitnesses(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        networkId: Int,
        notes: List<VotingNoteInfo>
    ): List<JniWitnessData> {
        val witnesses =
            votingDb.generateNoteWitnesses(
                roundId,
                bundleIndex,
                walletDbPath,
                networkId,
                notes.toJniNoteInfos()
            )
        return witnesses.asList()
    }

    override suspend fun syncVoteTree(roundId: String, nodeUrl: String): Long =
        votingDb.syncVoteTree(roundId, nodeUrl)

    override suspend fun resetTreeClient(roundId: String) =
        votingDb.resetTreeClient(roundId)

    override suspend fun resetAllTreeClients() =
        votingDb.resetAllTreeClients()

    override suspend fun storeVanPosition(roundId: String, bundleIndex: Int, position: Long) =
        votingDb.storeVanPosition(roundId, bundleIndex, position)

    override suspend fun generateVanWitness(
        roundId: String,
        bundleIndex: Int,
        anchorHeight: Long
    ): JniVanWitness =
        votingDb.generateVanWitness(roundId, bundleIndex, anchorHeight).also { witness ->
            witness.requireValid()
        }

    override suspend fun buildVoteCommitment(
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
        proofProgress: ((Double) -> Unit)?
    ): JniVoteCommitmentResult =
        votingDb
            .buildVoteCommitment(
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
                proofProgress?.asVotingProgressCallback()
            ).also { commitment ->
                commitment.requireValid()
            }

    override suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String) =
        votingDb.storeDelegationTxHash(roundId, bundleIndex, txHash)

    override suspend fun getDelegationTxHash(
        roundId: String,
        bundleIndex: Int
    ): VotingTxHashLookup =
        runExpectedMissingRowLookup {
            votingDb.getDelegationTxHash(roundId, bundleIndex).toVotingTxHashLookup()
        } ?: VotingTxHashLookup.Missing

    override suspend fun storeVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        txHash: String
    ) = votingDb.storeVoteTxHash(roundId, bundleIndex, proposalId, txHash)

    override suspend fun markVoteSubmitted(roundId: String, bundleIndex: Int, proposalId: Int) =
        votingDb.markVoteSubmitted(roundId, bundleIndex, proposalId)

    override suspend fun getVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): VotingTxHashLookup =
        runExpectedMissingRowLookup {
            votingDb.getVoteTxHash(roundId, bundleIndex, proposalId).toVotingTxHashLookup()
        } ?: VotingTxHashLookup.Missing

    override suspend fun storeCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        commitment: JniVoteCommitmentResult,
        vcTreePosition: Long
    ) {
        commitment.requireValid()
        votingDb.storeCommitmentBundle(
            roundId,
            bundleIndex,
            proposalId,
            commitment,
            vcTreePosition
        )
    }

    override suspend fun getCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): CommitmentBundleRecord? =
        runExpectedMissingRowLookup {
            votingDb
                .getCommitmentBundle(roundId, bundleIndex, proposalId)
                ?.toCommitmentBundleRecord()
        }

    override suspend fun clearRecoveryState(roundId: String) =
        votingDb.clearRecoveryState(roundId)

    override suspend fun recordShareDelegation(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: List<String>,
        nullifier: ByteArray,
        submitAt: Long
    ) {
        nullifier.requireByteArraySize("nullifier", JNI_PROTOCOL_FIELD_BYTES_SIZE)
        votingDb.recordShareDelegation(
            roundId,
            bundleIndex,
            proposalId,
            shareIndex,
            sentToUrls,
            nullifier,
            submitAt
        )
    }

    override suspend fun getShareDelegations(roundId: String): List<ShareDelegationRecord> =
        votingDb
            .getShareDelegations(roundId)
            .map { it.toShareDelegationRecord() }

    override suspend fun getUnconfirmedDelegations(roundId: String): List<ShareDelegationRecord> =
        votingDb
            .getUnconfirmedDelegations(roundId)
            .map { it.toShareDelegationRecord() }

    override suspend fun markShareConfirmed(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int
    ) = votingDb.markShareConfirmed(roundId, bundleIndex, proposalId, shareIndex)

    override suspend fun addSentServers(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        newUrls: List<String>
    ) = votingDb.addSentServers(roundId, bundleIndex, proposalId, shareIndex, newUrls)
}

internal fun JniGovernancePczt.toGovernancePcztResult(): GovernancePcztResult {
    rk.requireByteArraySize("rk", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    sighash.requireByteArraySize("sighash", JNI_PROTOCOL_FIELD_BYTES_SIZE)

    return GovernancePcztResult(
        pcztBytes = pcztBytes,
        rk = rk,
        sighash = sighash,
        actionIndex = actionIndex
    )
}

internal fun JniDelegationPirPrecomputeResult.toDelegationPirPrecomputeResult() =
    DelegationPirPrecomputeResult(
        cachedCount = cachedCount,
        fetchedCount = fetchedCount
    )

internal fun JniDelegationProofResult.toDelegationProofResult(): DelegationProofResult {
    proof.requireByteArrayNotEmpty("proof")
    publicInputs.requireByteArrayCount(
        "publicInputs",
        JNI_DELEGATION_PUBLIC_INPUT_COUNT
    )
    publicInputs.requireEachByteArraySize("publicInputs", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    nfSigned.requireByteArraySize("nfSigned", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    cmxNew.requireByteArraySize("cmxNew", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    govNullifiers.requireByteArrayCount(
        "govNullifiers",
        JNI_GOVERNANCE_NULLIFIER_COUNT
    )
    govNullifiers.requireEachByteArraySize("govNullifiers", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    vanComm.requireByteArraySize("vanComm", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    rk.requireByteArraySize("rk", JNI_PROTOCOL_FIELD_BYTES_SIZE)

    return DelegationProofResult(
        proof = proof,
        publicInputs = publicInputs,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govNullifiers = govNullifiers,
        vanComm = vanComm,
        rk = rk
    )
}

internal fun JniDelegationSubmissionResult.toDelegationSubmissionResult(): DelegationSubmissionResult {
    proof.requireByteArrayNotEmpty("proof")
    rk.requireByteArraySize("rk", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    spendAuthSig.requireByteArraySize("spendAuthSig", JNI_SPEND_AUTH_SIG_BYTES_SIZE)
    sighash.requireByteArraySize("sighash", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    nfSigned.requireByteArraySize("nfSigned", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    cmxNew.requireByteArraySize("cmxNew", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    govComm.requireByteArraySize("govComm", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    govNullifiers.requireByteArrayCount(
        "govNullifiers",
        JNI_GOVERNANCE_NULLIFIER_COUNT
    )
    govNullifiers.requireEachByteArraySize("govNullifiers", JNI_PROTOCOL_FIELD_BYTES_SIZE)

    return DelegationSubmissionResult(
        proof = proof,
        rk = rk,
        spendAuthSig = spendAuthSig,
        sighash = sighash,
        nfSigned = nfSigned,
        cmxNew = cmxNew,
        govComm = govComm,
        govNullifiers = govNullifiers,
        voteRoundId = voteRoundId
    )
}

private fun String?.toVotingTxHashLookup(): VotingTxHashLookup =
    if (this == null) {
        VotingTxHashLookup.Missing
    } else {
        VotingTxHashLookup.Found(this)
    }

private fun JniCommitmentBundleRecord.toCommitmentBundleRecord(): CommitmentBundleRecord {
    commitment.requireValid()
    return CommitmentBundleRecord(
        commitment = commitment,
        vcTreePosition = vcTreePosition
    )
}

private fun JniShareDelegationRecord.toShareDelegationRecord(): ShareDelegationRecord {
    nullifier.requireByteArraySize("nullifier", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    return ShareDelegationRecord(
        roundId = roundId,
        bundleIndex = bundleIndex,
        proposalId = proposalId,
        shareIndex = shareIndex,
        sentToUrls = sentToUrls,
        nullifier = nullifier,
        confirmed = confirmed,
        submitAt = submitAt,
        createdAt = createdAt
    )
}

private fun JniVanWitness.requireValid() {
    authPath.requireByteArrayCount("authPath", JNI_VAN_WITNESS_PATH_DEPTH)
    authPath.requireEachByteArraySize("authPath", JNI_PROTOCOL_FIELD_BYTES_SIZE)
}

private fun JniWireEncryptedShare.requireValid(name: String) {
    c1.requireByteArraySize("$name.c1", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    c2.requireByteArraySize("$name.c2", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    require(shareIndex in 0 until JNI_VOTE_SHARE_COUNT) {
        "$name.shareIndex must be in 0..${JNI_VOTE_SHARE_COUNT - 1}, got $shareIndex"
    }
}

private fun JniVoteCommitmentResult.requireValid() {
    vanNullifier.requireByteArraySize("vanNullifier", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    voteAuthorityNoteNew.requireByteArraySize("voteAuthorityNoteNew", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    voteCommitment.requireByteArraySize("voteCommitment", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    proof.requireByteArrayNotEmpty("proof")
    encShares.requireCount("encShares", JNI_VOTE_SHARE_COUNT)
    encShares.forEachIndexed { index, share -> share.requireValid("encShares[$index]") }
    sharesHash.requireByteArraySize("sharesHash", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    shareBlinds.requireByteArrayCount("shareBlinds", JNI_VOTE_SHARE_COUNT)
    shareBlinds.requireEachByteArraySize("shareBlinds", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    shareComms.requireByteArrayCount("shareComms", JNI_VOTE_SHARE_COUNT)
    shareComms.requireEachByteArraySize("shareComms", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    rVpk.requireByteArraySize("rVpk", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    alphaV.requireByteArraySize("alphaV", JNI_PROTOCOL_FIELD_BYTES_SIZE)
}

private fun JniSharePayload.requireValid() {
    sharesHash.requireByteArraySize("sharesHash", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    encShare.requireValid("encShare")
    allEncShares.requireCount("allEncShares", JNI_VOTE_SHARE_COUNT)
    allEncShares.forEachIndexed { index, share -> share.requireValid("allEncShares[$index]") }
    shareComms.requireByteArrayCount("shareComms", JNI_VOTE_SHARE_COUNT)
    shareComms.requireEachByteArraySize("shareComms", JNI_PROTOCOL_FIELD_BYTES_SIZE)
    primaryBlind.requireByteArraySize("primaryBlind", JNI_PROTOCOL_FIELD_BYTES_SIZE)
}

private fun ((Double) -> Unit).asVotingProgressCallback() =
    VotingProofProgressCallback { progress -> invoke(progress) }

@Suppress("TooGenericExceptionCaught")
private suspend fun <T> runExpectedMissingRowLookup(block: suspend () -> T): T? =
    try {
        block()
    } catch (exception: RuntimeException) {
        if (exception.isQueryReturnedNoRows()) {
            null
        } else {
            throw exception
        }
    }

private fun Throwable.isQueryReturnedNoRows(): Boolean =
    generateSequence(this) { throwable -> throwable.cause }
        .any { throwable ->
            throwable.message
                ?.contains("Query returned no rows", ignoreCase = true) == true
        }

private fun ByteArray.requireByteArraySize(name: String, expectedSize: Int) =
    require(size == expectedSize) {
        "$name must be $expectedSize bytes, got $size"
    }

private fun ByteArray.requireByteArrayNotEmpty(name: String) =
    require(isNotEmpty()) {
        "$name must not be empty"
    }

private fun List<ByteArray>.requireByteArrayCount(name: String, expectedCount: Int) =
    require(size == expectedCount) {
        "$name must contain $expectedCount entries, got $size"
    }

private fun <T> List<T>.requireCount(name: String, expectedCount: Int) =
    require(size == expectedCount) {
        "$name must contain $expectedCount entries, got $size"
    }

private fun List<ByteArray>.requireEachByteArraySize(name: String, expectedSize: Int) =
    forEachIndexed { index, bytes ->
        bytes.requireByteArraySize("$name[$index]", expectedSize)
    }
