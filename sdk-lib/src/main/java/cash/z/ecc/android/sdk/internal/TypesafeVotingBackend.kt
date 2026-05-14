package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniSharePayload
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight

@Suppress("TooManyFunctions", "LongParameterList")
internal interface TypesafeVotingBackend {
    suspend fun openVotingDb(dbPath: String, walletId: String): TypesafeVotingDb

    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray

    suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): JniBundleSetupResult

    suspend fun warmProvingCaches()

    suspend fun decomposeWeight(weight: Long): List<Long>

    suspend fun buildSharePayloads(
        commitment: JniVoteCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean = false
    ): List<JniSharePayload>

    suspend fun signCastVote(
        hotkeySeed: ByteArray,
        networkId: Int,
        accountIndex: Int,
        commitment: JniVoteCommitmentResult
    ): ByteArray

    suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray

    /**
     * Derives the raw Orchard address for the voting hotkey.
     *
     * The hotkey account index is intentionally fixed by the Rust voting backend to match the
     * vote-signing path. Do not add an `accountIndex` parameter unless that path changes with it;
     * otherwise delegation can be built for a hotkey that later vote construction cannot sign for.
     */
    suspend fun deriveHotkeyRawAddress(
        hotkeySeed: ByteArray,
        networkId: Int
    ): ByteArray

    suspend fun extractNcRoot(treeStateBytes: ByteArray): ByteArray

    suspend fun verifyWitness(witness: JniWitnessData): Boolean

    suspend fun getWalletNotes(
        walletDbPath: String,
        snapshotHeight: BlockHeight,
        networkId: Int,
        accountUuid: AccountUuid
    ): List<VotingNoteInfo>

    suspend fun extractPcztSighash(pcztBytes: ByteArray): ByteArray

    suspend fun extractSpendAuthSig(
        signedPcztBytes: ByteArray,
        actionIndex: Int
    ): ByteArray
}

@Suppress("TooManyFunctions", "LongParameterList")
internal interface TypesafeVotingDb {
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

    suspend fun listRounds(): List<JniRoundSummary>

    suspend fun getBundleCount(roundId: String): Int

    suspend fun getVotes(roundId: String): List<JniVoteRecord>

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long

    suspend fun setupBundles(
        roundId: String,
        notes: List<VotingNoteInfo>
    ): JniBundleSetupResult

    // nosemgrep: kotlin-typesafe-returns-jni-model -- voting internals consume this JNI carrier.
    suspend fun generateHotkey(
        roundId: String,
        seed: ByteArray
    ): JniVotingHotkey

    /**
     * Builds a governance PCZT for hardware-wallet flows.
     *
     * This explicit form trusts [fvkBytes] and [hotkeyRawAddress] as caller-derived Keystone input.
     * It does not validate a wallet seed against [fvkBytes]. Software-wallet callers that have the
     * wallet seed should use [buildGovernancePcztFromSeed] to retain that invariant.
     */
    suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        fvkBytes: ByteArray,
        hotkeyRawAddress: ByteArray,
        networkId: Int,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        seedFingerprint: ByteArray,
        roundName: String
    ): GovernancePcztResult

    /**
     * Builds a governance PCZT for software-wallet flows.
     *
     * This path derives the Orchard FVK from [walletSeed] and rejects calls where it does not match
     * [ufvk]. It also derives the hotkey raw address from [hotkeySeed] using the fixed hotkey
     * account index expected by the vote-signing path.
     */
    suspend fun buildGovernancePcztFromSeed(
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
    ): GovernancePcztResult

    suspend fun storeWitnesses(
        roundId: String,
        bundleIndex: Int,
        notes: List<VotingNoteInfo>,
        witnesses: List<JniWitnessData>
    )

    suspend fun precomputeDelegationPir(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<VotingNoteInfo>
    ): DelegationPirPrecomputeResult

    suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        notes: List<VotingNoteInfo>,
        hotkeyRawAddress: ByteArray,
        proofProgress: ((Double) -> Unit)? = null
    ): DelegationProofResult

    suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        senderSeed: ByteArray,
        networkId: Int,
        accountIndex: Int
    ): DelegationSubmissionResult

    suspend fun getDelegationSubmissionWithKeystoneSig(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray
    ): DelegationSubmissionResult

    suspend fun storeTreeState(roundId: String, treeStateBytes: ByteArray)

    suspend fun generateNoteWitnesses(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        networkId: Int,
        notes: List<VotingNoteInfo>
    ): List<JniWitnessData>

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
        singleShare: Boolean = false,
        proofProgress: ((Double) -> Unit)? = null
    ): JniVoteCommitmentResult

    suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String)

    suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): VotingTxHashLookup

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
    ): VotingTxHashLookup

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
    ): CommitmentBundleRecord?

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

    suspend fun getShareDelegations(roundId: String): List<ShareDelegationRecord>

    suspend fun getUnconfirmedDelegations(roundId: String): List<ShareDelegationRecord>

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

internal data class VotingNoteInfo(
    val commitment: ByteArray,
    val nullifier: ByteArray,
    val value: Long,
    val position: Long,
    val diversifier: ByteArray,
    val rho: ByteArray,
    val rseed: ByteArray,
    val scope: VotingNoteScope,
    val ufvk: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VotingNoteInfo) return false
        return commitment.contentEquals(other.commitment) &&
            nullifier.contentEquals(other.nullifier) &&
            value == other.value &&
            position == other.position &&
            diversifier.contentEquals(other.diversifier) &&
            rho.contentEquals(other.rho) &&
            rseed.contentEquals(other.rseed) &&
            scope == other.scope &&
            ufvk == other.ufvk
    }

    override fun hashCode(): Int {
        var result = commitment.contentHashCode()
        result = 31 * result + nullifier.contentHashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + diversifier.contentHashCode()
        result = 31 * result + rho.contentHashCode()
        result = 31 * result + rseed.contentHashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + ufvk.hashCode()
        return result
    }
}

internal enum class VotingNoteScope(
    val jniValue: Int
) {
    EXTERNAL(0),
    INTERNAL(1);

    companion object {
        fun fromJniValue(value: Int) =
            entries.firstOrNull { it.jniValue == value }
                ?: error("Unknown voting note scope: $value")
    }
}

internal data class GovernancePcztResult(
    val pcztBytes: ByteArray,
    val rk: ByteArray,
    val sighash: ByteArray,
    val actionIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GovernancePcztResult) return false
        return pcztBytes.contentEquals(other.pcztBytes) &&
            rk.contentEquals(other.rk) &&
            sighash.contentEquals(other.sighash) &&
            actionIndex == other.actionIndex
    }

    override fun hashCode(): Int {
        var result = pcztBytes.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + actionIndex
        return result
    }
}

internal data class DelegationPirPrecomputeResult(
    val cachedCount: Long,
    val fetchedCount: Long
)

internal data class DelegationProofResult(
    val proof: ByteArray,
    val publicInputs: List<ByteArray>,
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govNullifiers: List<ByteArray>,
    val vanComm: ByteArray,
    val rk: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DelegationProofResult) return false
        return proof.contentEquals(other.proof) &&
            publicInputs.contentDeepEquals(other.publicInputs) &&
            nfSigned.contentEquals(other.nfSigned) &&
            cmxNew.contentEquals(other.cmxNew) &&
            govNullifiers.contentDeepEquals(other.govNullifiers) &&
            vanComm.contentEquals(other.vanComm) &&
            rk.contentEquals(other.rk)
    }

    override fun hashCode(): Int {
        var result = proof.contentHashCode()
        result = 31 * result + publicInputs.contentDeepHashCode()
        result = 31 * result + nfSigned.contentHashCode()
        result = 31 * result + cmxNew.contentHashCode()
        result = 31 * result + govNullifiers.contentDeepHashCode()
        result = 31 * result + vanComm.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        return result
    }
}

internal data class DelegationSubmissionResult(
    val proof: ByteArray,
    val rk: ByteArray,
    val spendAuthSig: ByteArray,
    val sighash: ByteArray,
    val nfSigned: ByteArray,
    val cmxNew: ByteArray,
    val govComm: ByteArray,
    val govNullifiers: List<ByteArray>,
    val voteRoundId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DelegationSubmissionResult) return false
        return proof.contentEquals(other.proof) &&
            rk.contentEquals(other.rk) &&
            spendAuthSig.contentEquals(other.spendAuthSig) &&
            sighash.contentEquals(other.sighash) &&
            nfSigned.contentEquals(other.nfSigned) &&
            cmxNew.contentEquals(other.cmxNew) &&
            govComm.contentEquals(other.govComm) &&
            govNullifiers.contentDeepEquals(other.govNullifiers) &&
            voteRoundId == other.voteRoundId
    }

    override fun hashCode(): Int {
        var result = proof.contentHashCode()
        result = 31 * result + rk.contentHashCode()
        result = 31 * result + spendAuthSig.contentHashCode()
        result = 31 * result + sighash.contentHashCode()
        result = 31 * result + nfSigned.contentHashCode()
        result = 31 * result + cmxNew.contentHashCode()
        result = 31 * result + govComm.contentHashCode()
        result = 31 * result + govNullifiers.contentDeepHashCode()
        result = 31 * result + voteRoundId.hashCode()
        return result
    }
}

internal sealed interface VotingTxHashLookup {
    data object Missing : VotingTxHashLookup

    data class Found(
        val txHash: String
    ) : VotingTxHashLookup
}

internal data class CommitmentBundleRecord(
    val commitment: JniVoteCommitmentResult,
    val vcTreePosition: Long
)

internal data class ShareDelegationRecord(
    val roundId: String,
    val bundleIndex: Int,
    val proposalId: Int,
    val shareIndex: Int,
    val sentToUrls: List<String>,
    val nullifier: ByteArray,
    val confirmed: Boolean,
    val submitAt: Long,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShareDelegationRecord) return false
        return roundId == other.roundId &&
            bundleIndex == other.bundleIndex &&
            proposalId == other.proposalId &&
            shareIndex == other.shareIndex &&
            sentToUrls == other.sentToUrls &&
            nullifier.contentEquals(other.nullifier) &&
            confirmed == other.confirmed &&
            submitAt == other.submitAt &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = roundId.hashCode()
        result = 31 * result + bundleIndex
        result = 31 * result + proposalId
        result = 31 * result + shareIndex
        result = 31 * result + sentToUrls.hashCode()
        result = 31 * result + nullifier.contentHashCode()
        result = 31 * result + confirmed.hashCode()
        result = 31 * result + submitAt.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

private fun List<ByteArray>.contentDeepEquals(other: List<ByteArray>): Boolean =
    size == other.size && zip(other).all { (left, right) -> left.contentEquals(right) }

private fun List<ByteArray>.contentDeepHashCode(): Int =
    toTypedArray().contentDeepHashCode()
