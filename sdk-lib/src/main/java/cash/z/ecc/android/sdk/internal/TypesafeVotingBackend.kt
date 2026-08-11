package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteSubmission
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

    suspend fun extractOrchardFvkFromUfvk(ufvk: String, networkId: Int): ByteArray

    /**
     * Recomputes the raw Orchard address of the voting hotkey owning [hotkeyStoredSecret].
     *
     * This is the only way to recover a hotkey's address: the round state never reports it, and
     * it cannot be derived from the wallet seed. It requires the secret the application persisted
     * when the hotkey was generated — see [JniVotingHotkey].
     *
     * The hotkey address index is fixed by the voting backend to match the vote-signing path and
     * is not caller-selectable; otherwise delegation could be built for a hotkey that later vote
     * construction cannot sign for.
     */
    suspend fun deriveHotkeyRawAddress(
        hotkeyStoredSecret: ByteArray,
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

// Semgrep only honours a `nosemgrep` annotation on the finding's own line or the line directly
// above it, so the suppressions below have to sit between each KDoc block and its declaration.
// That adjacency is what ktlint's no-consecutive-comments rule forbids, and an annotation moved
// somewhere semgrep will not read it is worse than useless, so the rule is waived here instead.
@Suppress("TooManyFunctions", "LongParameterList", "ktlint:standard:no-consecutive-comments")
internal interface TypesafeVotingDb {
    suspend fun close()

    /**
     * Creates a round and binds it to [networkId].
     *
     * [roundId] must be 64 lowercase hex characters encoding a canonical Pallas field element.
     */
    suspend fun initRound(
        roundId: String,
        snapshotHeight: Long,
        eaPK: ByteArray,
        ncRoot: ByteArray,
        nullifierIMTRoot: ByteArray,
        networkId: Int,
        sessionJson: String?
    )

    /**
     * Returns the round's lifecycle state.
     *
     * [JniRoundState.hotkeyAddress] and [JniRoundState.delegatedWeight] are always null; use
     * [TypesafeVotingBackend.deriveHotkeyRawAddress] and the bundle weights from [setupBundles]
     * respectively.
     */
    suspend fun getRoundState(roundId: String): JniRoundState?

    suspend fun listRounds(): List<JniRoundSummary>

    suspend fun getBundleCount(roundId: String): Int

    suspend fun getVotes(roundId: String): List<JniVoteRecord>

    suspend fun clearRound(roundId: String)

    suspend fun deleteSkippedBundles(
        roundId: String,
        keepCount: Int
    ): Long

    /**
     * Chunks [notes] into voting bundles. Rejects an empty note set.
     */
    suspend fun setupBundles(
        roundId: String,
        notes: List<VotingNoteInfo>
    ): JniBundleSetupResult

    /**
     * Generates a fresh voting hotkey for the round.
     *
     * The hotkey is app-owned random material, not a derivation of the wallet seed, so every call
     * returns a different one. **The caller must persist the returned
     * [JniVotingHotkey.storedSecret] in platform secure storage before delegating to it.** It
     * cannot be re-derived from the seed phrase, restoring the wallet from its seed phrase does
     * not restore it, and losing it forfeits the voting power already delegated to the hotkey.
     */
    // nosemgrep: kotlin-typesafe-returns-jni-model -- voting internals consume this JNI carrier.
    suspend fun generateHotkey(
        roundId: String,
        networkId: Int
    ): JniVotingHotkey

    /**
     * Builds a governance PCZT for hardware-wallet flows.
     *
     * This explicit form trusts [fvkBytes] as caller-derived Keystone input; it does not validate
     * a wallet seed against it. Software-wallet callers that have the wallet seed should use
     * [buildGovernancePcztFromSeed] to retain that invariant.
     *
     * [hotkeyStoredSecret] is the persisted secret from [generateHotkey]; the raw hotkey address
     * alone is no longer accepted.
     */
    suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        fvkBytes: ByteArray,
        hotkeyStoredSecret: ByteArray,
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
     * [ufvk]. [hotkeyStoredSecret] is the persisted secret from [generateHotkey]; the hotkey is
     * app-owned random material and is not derived from [walletSeed].
     */
    suspend fun buildGovernancePcztFromSeed(
        roundId: String,
        bundleIndex: Int,
        ufvk: String,
        networkId: Int,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        walletSeed: ByteArray,
        hotkeyStoredSecret: ByteArray,
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
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>
    ): DelegationPirPrecomputeResult

    /**
     * Proves the delegation for a bundle.
     *
     * The delegation keys are assembled natively from [fvkBytes], [hotkeyStoredSecret],
     * [seedFingerprint], [accountIndex] and [roundName], and validated against the stored round.
     */
    suspend fun buildAndProveDelegation(
        roundId: String,
        bundleIndex: Int,
        pirServerUrl: String,
        networkId: Int,
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>,
        fvkBytes: ByteArray,
        hotkeyStoredSecret: ByteArray,
        seedFingerprint: ByteArray,
        accountIndex: Int,
        roundName: String,
        proofProgress: ((Double) -> Unit)? = null
    ): DelegationProofResult

    /**
     * Assembles the delegation submission from a caller-supplied SpendAuth signature.
     *
     * This is the single path for both software and hardware signers: the voting backend no
     * longer derives account keys or signs on the caller's behalf, so every signer hands back a
     * 64-byte [spendAuthSig] over the 32-byte ZIP-244 [sighash].
     */
    suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        spendAuthSig: ByteArray,
        sighash: ByteArray
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

    /**
     * Builds, signs and stores the vote commitment for a proposal in one call.
     *
     * This replaces the former build/sign/build-payloads sequence; the helper-share payloads now
     * arrive on [JniVoteCommitResult.sharePayloads]. [hotkeyStoredSecret] is the persisted secret
     * from [TypesafeVotingDb.generateHotkey].
     */
    // nosemgrep: kotlin-typesafe-returns-jni-model -- voting internals consume this JNI carrier.
    suspend fun commitVote(
        roundId: String,
        bundleIndex: Int,
        hotkeyStoredSecret: ByteArray,
        networkId: Int,
        proposalId: Int,
        choice: Int,
        numOptions: Int,
        vcTreePosition: Long,
        witness: JniVanWitness,
        singleShare: Boolean = false,
        proofProgress: ((Double) -> Unit)? = null
    ): JniVoteCommitResult

    /**
     * Returns the chain-ready fields needed to resend a cast-vote transaction before it confirms.
     *
     * After [recordVcPosition], use [getCommitmentBundle] instead: it also yields fresh
     * helper-share payloads.
     */
    // nosemgrep: kotlin-typesafe-returns-jni-model -- voting internals consume this JNI carrier.
    suspend fun voteSubmission(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): JniVoteSubmission

    /**
     * Records the confirmed position of the vote commitment in the vote commitment tree.
     *
     * [getCommitmentBundle] reports null until this has been called.
     */
    suspend fun recordVcPosition(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        vcTreePosition: Long
    )

    suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String)

    suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): VotingTxHashLookup

    /**
     * Records [txHash] as the cast-vote transaction for this vote.
     *
     * A vote is "submitted" by having a recorded transaction hash; there is no separate flag.
     * Recording the same hash twice is idempotent, but recording a *different* hash for a vote
     * that already has one fails, so the wallet keeps polling the transaction it first submitted.
     */
    suspend fun markVoteSubmitted(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        txHash: String
    )

    suspend fun getVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): VotingTxHashLookup

    /**
     * Reconstructs the stored vote commitment, with fresh helper-share payloads.
     *
     * Reports null until the vote reaches the confirmed phase — its transaction hash recorded via
     * [markVoteSubmitted] *and* its tree position via [recordVcPosition] — and for a vote that was
     * never stored.
     */
    suspend fun getCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): CommitmentBundleRecord?

    suspend fun clearRecoveryState(roundId: String)

    /**
     * Records that a helper share was delegated.
     *
     * The share nullifier is derived natively from the vote's own recovery state rather than
     * supplied by the caller.
     */
    suspend fun recordShareDelegation(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        sentToUrls: List<String>,
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

    /**
     * Appends [newUrls] to the sent-server list for this share, ignoring duplicates.
     */
    suspend fun addSentServers(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        shareIndex: Int,
        newUrls: List<String>
    )

    /**
     * The canonical, per-bundle delegation phase for every bundle with recorded progress in
     * [roundId] — see [cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase]'s doc.
     */
    suspend fun delegationPhases(roundId: String): List<JniDelegationPhase>

    /**
     * Clears a bundle's unsigned delegation setup fields (PCZT/rk/sighash) and any stale proof
     * row so a subsequent construct call starts clean. Does not delete round-level state.
     */
    suspend fun resetVotingSessionState(roundId: String)
}

/**
 * The typesafe view of a spendable note the voting backend may draw voting weight from.
 *
 * [toString] is redacted: [rseed] and [rho] reconstruct the note's spending randomness,
 * [nullifier] links the note to its spend, and [ufvk] is a full viewing key that discloses
 * the entire account's transaction history. The generated `data class` rendering would print
 * all four into any log line that interpolates a note.
 */
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
    override fun toString(): String = "VotingNoteInfo(redacted)"

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
    val tx1Effects: ByteArray,
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
            tx1Effects.contentEquals(other.tx1Effects) &&
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
        result = 31 * result + tx1Effects.contentHashCode()
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
    val commitment: JniVoteCommitResult,
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
