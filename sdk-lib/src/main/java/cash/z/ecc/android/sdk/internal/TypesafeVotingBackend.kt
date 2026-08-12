package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniBundleSetupResult
import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundSummary
import cash.z.ecc.android.sdk.internal.model.voting.JniSharePayload
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.internal.model.voting.JniVotingHotkey
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight

@Suppress("TooManyFunctions", "LongParameterList")
internal interface TypesafeVotingBackend {
    suspend fun openVotingDb(dbPath: String, walletId: String, networkId: Int): TypesafeVotingDb

    suspend fun computeShareNullifier(
        voteCommitment: ByteArray,
        shareIndex: Int,
        blind: ByteArray
    ): ByteArray

    suspend fun computeBundleSetup(notes: List<VotingNoteInfo>): JniBundleSetupResult

    suspend fun warmProvingCaches()

    // nosemgrep: kotlin-typesafe-returns-jni-model -- voting internals consume this JNI carrier.

    /**
     * Computes when a delegated helper share should submit, honoring the ceremony's
     * last-moment buffer window. A passthrough to the Rust backend, which sources its own
     * entropy. Returns unix seconds; `0` means "submit immediately".
     */
    suspend fun scheduledShareSubmitAt(
        nowSeconds: Long,
        ceremonyStartSeconds: Long,
        voteEndTimeSeconds: Long,
        singleShare: Boolean
    ): Long

    suspend fun buildSharePayloads(
        commitment: JniVoteCommitmentResult,
        voteDecision: Int,
        numOptions: Int,
        vcTreePosition: Long,
        singleShareMode: Boolean = false
    ): List<JniSharePayload>

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

    /**
     * Mints or reconstructs a voting hotkey.
     *
     * An empty [storedSecret] mints a fresh, app-owned random hotkey; a previously persisted
     * [JniVotingHotkey.storedSecret] deterministically reconstructs the same hotkey. This call
     * is not scoped to a round.
     */
    suspend fun generateHotkey(storedSecret: ByteArray): JniVotingHotkey

    /**
     * Builds a governance PCZT for hardware-wallet flows.
     *
     * This explicit form trusts [fvkBytes] and [hotkeySecret] as caller-derived Keystone input.
     * It does not validate a wallet seed against [fvkBytes]. Software-wallet callers that have the
     * wallet seed should use [buildGovernancePcztFromSeed] to retain that invariant.
     */
    suspend fun buildGovernancePczt(
        roundId: String,
        bundleIndex: Int,
        fvkBytes: ByteArray,
        hotkeySecret: ByteArray,
        accountIndex: Int,
        notes: List<VotingNoteInfo>,
        seedFingerprint: ByteArray,
        roundName: String
    ): GovernancePcztResult

    /**
     * Builds a governance PCZT for software-wallet flows.
     *
     * This path derives the Orchard FVK from [walletSeed] and rejects calls where it does not match
     * [ufvk]. It also reconstructs the hotkey from [hotkeySecret] using the fixed hotkey
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
        hotkeySecret: ByteArray,
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
        pirDepth: Int,
        pirTier0Layers: Int,
        pirTier1Layers: Int,
        notes: List<VotingNoteInfo>
    ): DelegationPirPrecomputeResult

    suspend fun buildAndProveDelegation(
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
        proofProgress: ((Double) -> Unit)? = null
    ): DelegationProofResult

    /**
     * Reconstructs the delegation signing keys from wallet state at [walletDbPath] and returns
     * a spend-authorization-signed delegation submission.
     *
     * [hotkeySecret] and [roundName] must match those originally used to build this bundle's
     * governance PCZT.
     */
    suspend fun getDelegationSubmission(
        roundId: String,
        bundleIndex: Int,
        walletDbPath: String,
        accountUuid: String,
        hotkeySecret: ByteArray,
        roundName: String,
        senderSeed: ByteArray
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

    /**
     * Builds, signs and stores the vote commitment for a proposal in one call.
     *
     * The helper-share payloads arrive on [JniVoteCommitResult.sharePayloads]. [hotkeySecret] is
     * the persisted secret from [TypesafeVotingDb.generateHotkey].
     */
    suspend fun buildVoteCommitment(
        roundId: String,
        bundleIndex: Int,
        hotkeySecret: ByteArray,
        proposalId: Int,
        choice: Int,
        numOptions: Int,
        witness: JniVanWitness,
        singleShare: Boolean = false,
        proofProgress: ((Double) -> Unit)? = null
    ): JniVoteCommitResult

    suspend fun storeDelegationTxHash(roundId: String, bundleIndex: Int, txHash: String)

    suspend fun getDelegationTxHash(roundId: String, bundleIndex: Int): VotingTxHashLookup

    /**
     * Records [txHash] as the cast-vote transaction for this vote and marks it submitted.
     *
     * A vote is "submitted" by having a recorded transaction hash; there is no separate flag.
     * Recording the same hash twice is idempotent, but recording a *different* hash for a vote
     * that already has one fails, so the wallet keeps polling the transaction it first submitted.
     */
    suspend fun storeVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        txHash: String
    )

    /**
     * Vestigial: [storeVoteTxHash] already records the tx hash and marks the vote submitted in
     * one atomic, idempotency-checked call, so this is a no-op re-assertion of the
     * already-recorded hash on every reachable caller. Kept only because a live external caller
     * still calls this after [storeVoteTxHash] on both the fresh- and cached-bundle submission
     * paths; new code should rely on [storeVoteTxHash] alone. Throws if no tx hash has been
     * recorded yet for this vote — call [storeVoteTxHash] first.
     */
    suspend fun markVoteSubmitted(roundId: String, bundleIndex: Int, proposalId: Int)

    suspend fun getVoteTxHash(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): VotingTxHashLookup

    /**
     * Reconstructs the stored vote commitment, with fresh helper-share payloads.
     *
     * Reports null until the vote reaches the confirmed phase — its tree position recorded via
     * [recordVcPosition] — and for a vote that was never stored.
     */
    suspend fun getCommitmentBundle(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): CommitmentBundleRecord?

    /**
     * Records the confirmed vote-commitment-tree position for an already-committed vote, once
     * its cast-vote transaction has been mined.
     */
    suspend fun recordVcPosition(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int,
        vcTreePosition: Long
    )

    /**
     * Recovers the signed `vote::commit` result for an already-committed vote, together with
     * its confirmed vote-commitment-tree position recorded by [recordVcPosition].
     */
    suspend fun recoverCommittedVote(
        roundId: String,
        bundleIndex: Int,
        proposalId: Int
    ): CommittedVoteRecord

    suspend fun clearRecoveryState(roundId: String)

    /**
     * Records that share [shareIndex] was sent to [sentToUrls].
     *
     * The native side derives and persists the authoritative nullifier from the vote's own
     * recovery state; [nullifier] is only shape-validated when non-empty and is never itself
     * stored. An empty [nullifier] is the normal case for callers that do not have it yet.
     */
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
     * Clears unsigned delegation setup fields (PCZT/rk/sighash) for every bundle in [roundId]
     * that has neither a submitted delegation tx nor a persisted [storeKeystoneSignature] entry
     * — see the crate's `clear_unsigned_delegation_setup_fields` for the exact predicate — so a
     * subsequent construct call starts clean. Does not delete round-level state.
     *
     * Known gap: this does **not** clear the `proofs` table row for those bundles, only the
     * `bundles` table's setup columns — the underlying crate has no public API for that (see
     * MOB-1678 investigation notes). A bundle reset+rebuilt this way keeps its old (now
     * stale-alpha) proof row until a fresh proof overwrites it via
     * `buildAndProveDelegation`/`storeDelegationProofFixture`; callers must not treat proof-row
     * presence alone as proof-freshness for a bundle that has gone through a reset.
     */
    suspend fun resetVotingSessionState(roundId: String)

    /**
     * Persists a Keystone-signed delegation bundle's signature so a later round-wide
     * [resetVotingSessionState] preserves this bundle instead of wiping its unsigned setup
     * fields for a rebuild. Pass the `rk`/`sighash` already verified by a prior
     * [getDelegationSubmissionWithKeystoneSig] call (its returned result's `rk`), not arbitrary
     * caller-supplied values — this call does not itself re-verify the signature.
     */
    suspend fun storeKeystoneSignature(
        roundId: String,
        bundleIndex: Int,
        keystoneSig: ByteArray,
        keystoneSighash: ByteArray,
        rk: ByteArray
    )
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
    val commitment: JniVoteCommitmentResult,
    val vcTreePosition: Long
)

internal data class CommittedVoteRecord(
    val commit: JniVoteCommitResult,
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
